package com.antflow.common;

import com.antflow.auth.PrincipalHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
@Order(50)
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> HEADER_NAMES = Set.of("Idempotency-Key", "X-Idempotency-Key");
    /** 不参与幂等的路径：登录/刷新/登出有自己的会话与 CSRF 语义，重放没有意义。 */
    private static final Set<String> SKIP_PREFIXES = Set.of("/api/auth/");
    /**
     * 超过这个大小就不缓冲请求体：幂等是为"小 JSON 的提交/审批"设计的，
     * 大文件上传把它整段读进内存得不偿失（上传自身的去重靠文件的 sha256）。
     */
    private static final long MAX_BUFFERED_BODY_BYTES = 1L << 20;
    private final IdempotencyService service;

    public IdempotencyFilter(IdempotencyService service) { this.service = service; }

    /**
     * 默认对所有 {@code /api/**} 的写请求开放，只显式豁免少数路径。
     *
     * <p>这里是**反向**判据而不是前缀白名单：原来用白名单（/api/mobile、/api/forms、/api/processes），
     * 结果是 /api/tasks/**（审批、转交、加签、改派）与 /api/instances/**（发起、撤回、终止）
     * 整族没有幂等保护——新端点落在白名单外就静默失去保护。
     *
     * <p>注意本方法只决定"要不要进入过滤器"：没有 Idempotency-Key 头的请求会在
     * {@link #doFilterInternal} 里立刻放行，不会读 body，所以放宽范围几乎是零成本。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        if (!METHODS.contains(req.getMethod().toUpperCase())) {
            return true;
        }
        String path = req.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        for (String skip : SKIP_PREFIXES) {
            if (path.startsWith(skip)) {
                return true;
            }
        }
        return bodyTooLargeToBuffer(req);
    }

    private static boolean bodyTooLargeToBuffer(HttpServletRequest req) {
        String contentType = req.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            return true;
        }
        return req.getContentLengthLong() > MAX_BUFFERED_BODY_BYTES;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = readKey(req);
        if (key == null) { chain.doFilter(req, res); return; }
        var principal = PrincipalHolder.current();
        if (principal.isEmpty()) { chain.doFilter(req, res); return; }

        byte[] body = req.getInputStream().readAllBytes();
        long userId = principal.get().userId();
        // 幂等键只能由「用户 + 路由 + 客户端给的 key」决定，不能掺 authzVersion：
        // 角色/菜单一变版本就变，24h 内重试同一个提交会被当成新请求，副作用重放（重复提交/重复审批）。
        // 也不能改键的形态——已落库的旧记录还在 TTL 内，换了形态就命中不到。
        // （要做到"权限变化后不重放旧响应"，应在重放前单独校验当前授权，而不是改键空间。）
        IdempotencyService.Claim claim = service.claim(userId, req.getMethod(), req.getRequestURI(),
            key, body);
        if (claim.replay() != null) {
            write(res, claim.replay(), true, null);
            return;
        }
        if (claim.conflict() != null) {
            write(res, new IdempotencyService.CachedResponse(409,
                "{\"code\":\"" + claim.conflict() + "\",\"message\":\"请求幂等键状态冲突\"}"), false,
                "IDEMPOTENCY_IN_PROGRESS".equals(claim.conflict()) ? "1" : null);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        boolean completed = false;
        try {
            chain.doFilter(new CachedBodyRequest(req, body), wrapped);
            completed = true;
        } finally {
            byte[] responseBody = wrapped.getContentAsByteArray();
            String response = new String(responseBody, StandardCharsets.UTF_8);
            if (completed && wrapped.getStatus() >= 200 && wrapped.getStatus() < 300) {
                service.succeed(claim, wrapped.getStatus(), response, body);
            } else if (completed) {
                // 明确的 4xx/5xx：业务确实没成功，允许客户端带同一个 key 重试。
                service.fail(claim, body);
            } else {
                // 处理链抛异常：可能已经提交（提交后才抛、或后续过滤器抛），结果不可知。
                // 标 UNKNOWN 而不是 FAILED，否则重试会再执行一次副作用（见 V61）。
                service.markUnknown(claim, body);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private static void write(HttpServletResponse res, IdempotencyService.CachedResponse response,
                              boolean replayed, String retryAfter) throws IOException {
        res.setStatus(response.status());
        res.setContentType("application/json;charset=UTF-8");
        if (replayed) res.setHeader("Idempotency-Replayed", "true");
        if (retryAfter != null) res.setHeader("Retry-After", retryAfter);
        byte[] body = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        res.setContentLength(body.length);
        res.getOutputStream().write(body);
    }

    private static String readKey(HttpServletRequest req) {
        for (String header : HEADER_NAMES) {
            String value = req.getHeader(header);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request); this.body = body;
        }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding() == null
                ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }
}
