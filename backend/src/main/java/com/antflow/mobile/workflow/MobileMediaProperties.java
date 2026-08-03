package com.antflow.mobile.workflow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "antflow.media")
public class MobileMediaProperties {
    private String ffmpegBin = "ffmpeg";
    private String watermarkFont = "";
}
