package com.antflow.integration.wecom;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 企微应用侧拆分后共用的小工具（纯函数、无状态，原 WecomAppService 的私有静态方法）。
 * 签名算法与编码规则必须保持一致：JS-SDK 的 signature 由 ticket/nonce/timestamp/url 的原始顺序拼接后 SHA-1。
 */
final class WecomAppSupport {
    private static final SecureRandom RANDOM = new SecureRandom();

    private WecomAppSupport() { }

    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String sha1(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }

    static int defaultPort(String scheme) { return "https".equalsIgnoreCase(scheme) ? 443 : 80; }
}
