package com.antflow.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等过滤器的覆盖范围必须是「默认全开 + 显式豁免」。
 *
 * <p>回归 A2：原来的判据是前缀白名单（/api/mobile、/api/forms、/api/processes），
 * 于是 {@code /api/tasks/**}（审批/转交/加签/改派）与 {@code /api/instances/**}
 * （发起/撤回/终止）整族没有幂等保护；新端点落在白名单外同样静默失去保护。
 * 这里把"协议面"钉死：改回白名单就会红。
 */
class IdempotencyFilterTest {

    private final IdempotencyFilter filter =
        new IdempotencyFilter(new IdempotencyService(new ObjectMapper()));

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    @Test
    void mutatingBusinessEndpointsAreCovered() {
        assertThat(filter.shouldNotFilter(request("POST", "/api/instances/start"))).isFalse();
        assertThat(filter.shouldNotFilter(request("POST", "/api/instances/7/withdraw"))).isFalse();
        assertThat(filter.shouldNotFilter(request("POST", "/api/instances/7/terminate"))).isFalse();
        assertThat(filter.shouldNotFilter(request("POST", "/api/tasks/42/approve"))).isFalse();
        assertThat(filter.shouldNotFilter(request("POST", "/api/tasks/42/reject"))).isFalse();
        assertThat(filter.shouldNotFilter(request("POST", "/api/tasks/42/add-assignee"))).isFalse();
        assertThat(filter.shouldNotFilter(request("POST", "/api/mobile/instances"))).isFalse();
        assertThat(filter.shouldNotFilter(request("POST", "/api/forms/data"))).isFalse();
    }

    @Test
    void authEndpointsStayOutOfScope() {
        assertThat(filter.shouldNotFilter(request("POST", "/api/auth/login"))).isTrue();
        assertThat(filter.shouldNotFilter(request("POST", "/api/auth/refresh"))).isTrue();
        assertThat(filter.shouldNotFilter(request("POST", "/api/auth/logout"))).isTrue();
    }

    @Test
    void readsAndNonApiPathsStayOutOfScope() {
        assertThat(filter.shouldNotFilter(request("GET", "/api/instances"))).isTrue();
        assertThat(filter.shouldNotFilter(request("GET", "/api/tasks"))).isTrue();
        assertThat(filter.shouldNotFilter(request("POST", "/actuator/health"))).isTrue();
    }

    @Test
    void largeOrMultipartBodiesAreNotBuffered() {
        MockHttpServletRequest multipart = request("POST", "/api/mobile/files");
        multipart.setContentType("multipart/form-data; boundary=x");
        assertThat(filter.shouldNotFilter(multipart)).isTrue();

        // MockHttpServletRequest 的 contentLength 由 body 推导，1MB 的数组没必要真分配：
        // 直接覆写读数，语义与真实请求一致（过滤器只看 getContentLengthLong）。
        MockHttpServletRequest huge = new MockHttpServletRequest("POST", "/api/forms/data") {
            @Override
            public long getContentLengthLong() {
                return (1L << 20) + 1;
            }
        };
        assertThat(filter.shouldNotFilter(huge)).isTrue();

        MockHttpServletRequest normal = request("POST", "/api/forms/data");
        normal.setContent(new byte[2048]);
        assertThat(filter.shouldNotFilter(normal)).isFalse();
    }
}
