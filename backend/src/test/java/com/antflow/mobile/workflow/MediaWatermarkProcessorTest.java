package com.antflow.mobile.workflow;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MediaWatermarkProcessorTest {
    private final MediaWatermarkProcessor processor =
        new MediaWatermarkProcessor(new MobileMediaProperties());

    @Test
    void supportsOnlyImagesAndVideos() {
        assertThat(processor.supports("image/jpeg")).isTrue();
        assertThat(processor.supports("image/png")).isTrue();
        assertThat(processor.supports("video/mp4")).isTrue();
        assertThat(processor.supports("video/quicktime")).isTrue();
        assertThat(processor.supports("application/pdf")).isFalse();
        assertThat(processor.supports("image/gif")).isFalse();
    }

    @Test
    void videoIsNormalizedToMp4ContentType() {
        assertThat(processor.resultContentType("video/quicktime")).isEqualTo("video/mp4");
        assertThat(processor.resultContentType("video/webm")).isEqualTo("video/mp4");
        assertThat(processor.resultContentType("image/png")).isEqualTo("image/png");
    }

    @Test
    void watermarksJpegImageWithRealFfmpeg() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg is not installed");
        byte[] original = jpegBytes();
        byte[] processed = processor.apply(original, "image/jpeg", "AntFlow");
        assertThat(processed).isNotEmpty();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(processed));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(64);
        assertThat(image.getHeight()).isEqualTo(48);
    }

    @Test
    void watermarksMp4VideoWithRealFfmpeg() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg is not installed");
        byte[] original = sampleMp4Bytes();
        byte[] processed = processor.apply(original, "video/mp4", "AntFlow");
        assertThat(processed).isNotEmpty();
        assertThat(processed).isNotEqualTo(original);
    }

    private static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    // ---- 缺二进制这条路：镜像里没装 ffmpeg 时，用户看到的必须是"能照着做"的话，
    //      而不是 Cannot run program "ffmpeg"。下面三条都不需要真 ffmpeg。

    @Test
    void probeReportsMissingFfmpegBinary() {
        MobileMediaProperties properties = new MobileMediaProperties();
        properties.setFfmpegBin("antflow-no-such-ffmpeg-binary");
        MediaWatermarkProcessor missing = new MediaWatermarkProcessor(properties);

        assertThat(missing.probe().ffmpegAvailable()).isFalse();
    }

    @Test
    void missingBinaryFailsWithAnActionableMessage() throws Exception {
        MobileMediaProperties properties = new MobileMediaProperties();
        properties.setFfmpegBin("antflow-no-such-ffmpeg-binary");
        MediaWatermarkProcessor missing = new MediaWatermarkProcessor(properties);

        assertThatThrownBy(() -> missing.apply(jpegBytes(), "image/jpeg", "AntFlow"))
            .isInstanceOf(com.antflow.engine.BizException.class)
            .hasMessageContaining("ffmpeg")
            .hasMessageNotContaining("Cannot run program");
    }

    @Test
    void probeReportsTheConfiguredFontAndIgnoresAMissingOne(@TempDir Path tempDir) throws Exception {
        Path font = Files.createFile(tempDir.resolve("font.ttf"));
        MobileMediaProperties properties = new MobileMediaProperties();
        properties.setWatermarkFont(font.toString());
        assertThat(new MediaWatermarkProcessor(properties).probe().fontPath())
            .isEqualTo(font.toString());

        // 配了但文件不在：当成"没有字体"，启动探测才能把它 WARN 出来（把不存在的 fontfile 交给
        // ffmpeg 是直接报错）。
        properties.setWatermarkFont(tempDir.resolve("nope.ttf").toString());
        assertThat(new MediaWatermarkProcessor(properties).probe().fontPath()).isNull();
    }

    private static byte[] jpegBytes() throws IOException {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x0B57D0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }

    private static byte[] sampleMp4Bytes() throws IOException {
        try (InputStream input = MediaWatermarkProcessorTest.class
            .getResourceAsStream("/media/sample.mp4")) {
            if (input == null) {
                throw new IllegalStateException("sample.mp4 test resource is missing");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            return output.toByteArray();
        }
    }}
