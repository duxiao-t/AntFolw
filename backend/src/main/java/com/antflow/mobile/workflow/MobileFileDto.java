package com.antflow.mobile.workflow;

import java.util.UUID;

/**
 * 上传件元数据。
 *
 * <p>{@code status} 是 READY / PROCESSING / FAILED：带水印的视频是异步处理的，客户端要轮询到 READY
 * 才能提交（服务端的附件关联只接受 READY 文件）。{@code failureReason} 只在 FAILED 时给，是**已经脱敏**
 * 过的可读原因——否则客户端只知道"失败了"，不知道为什么。
 */
public record MobileFileDto(UUID id, String name, String contentType, long size,
                            String contentUrl, String status, String failureReason) {
    public MobileFileDto(UUID id, String name, String contentType, long size, String contentUrl,
                         String status) {
        this(id, name, contentType, size, contentUrl, status, null);
    }

    public MobileFileDto(UUID id, String name, String contentType, long size, String contentUrl) {
        this(id, name, contentType, size, contentUrl, "READY", null);
    }
}
