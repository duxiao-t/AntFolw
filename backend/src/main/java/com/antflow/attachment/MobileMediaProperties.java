package com.antflow.attachment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "antflow.media")
public class MobileMediaProperties {
    private String ffmpegBin = "ffmpeg";
    private String ffprobeBin = "ffprobe";
    private String watermarkFont = "";
    /**
     * 校验时长时允许从对象存储拉取的单个文件上限（字节，默认 512MB）。
     *
     * <p>兜底探测是"先落地临时文件再 ffprobe"，没有上限的话一个几百 MB 的视频就能把
     * 校验线程堵在磁盘/网络上；超限按 fail-closed 处理（已有 duration_seconds 的文件走快路径，
     * 不受影响）。
     */
    private long maxProbeBytes = 512L * 1024 * 1024;
}
