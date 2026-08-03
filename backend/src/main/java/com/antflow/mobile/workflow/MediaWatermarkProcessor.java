package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Applies a text watermark (configured text + upload time) to images and videos
 * with a local ffmpeg binary. Images are re-encoded in their original format,
 * videos are normalized to MP4 (H.264 + AAC).
 */
@Service
@RequiredArgsConstructor
public class MediaWatermarkProcessor {
    private static final int PROCESS_TIMEOUT_MINUTES = 10;
    private static final int OUTPUT_CAPTURE_LIMIT = 8 * 1024;
    private static final List<String> CJK_FONT_CANDIDATES = List.of(
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/simhei.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
    );

    private final MobileMediaProperties properties;

    public boolean supports(String contentType) {
        String type = normalize(contentType);
        return type.startsWith("image/jpeg")
            || type.startsWith("image/png")
            || type.startsWith("video/");
    }

    public String resultContentType(String contentType) {
        return normalize(contentType).startsWith("video/") ? "video/mp4" : normalize(contentType);
    }

    public byte[] apply(byte[] content, String contentType, String watermarkText) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("antflow-watermark-");
            String sourceType = normalize(contentType);
            String sourceExtension = extensionFor(sourceType);
            Path input = workDir.resolve("input" + sourceExtension);
            Files.write(input, content);

            String watermark = watermarkText.trim() + " "
                + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
            Path watermarkFile = workDir.resolve("watermark.txt");
            Files.write(watermarkFile, watermark.getBytes(StandardCharsets.UTF_8));

            Path filterScript = workDir.resolve("filter.txt");
            Files.write(filterScript, drawTextFilter(watermarkFile).getBytes(StandardCharsets.UTF_8));

            Path output = workDir.resolve(sourceType.startsWith("video/") ? "output.mp4" : "output" + sourceExtension);
            ProcessResult result = run(command(input, output, filterScript, sourceType));
            if (result.exitCode != 0) {
                throw new BizException("WATERMARK_PROCESSING_FAILED",
                    "watermark processing failed: " + result.errorTail);
            }
            return Files.readAllBytes(output);
        } catch (IOException exception) {
            throw new BizException("WATERMARK_PROCESSING_FAILED", exception.getMessage());
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    private List<String> command(Path input, Path output, Path filterScript, String contentType) {
        List<String> command = new ArrayList<>();
        command.add(properties.getFfmpegBin());
        command.add("-y");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-i");
        command.add(input.toString());
        command.add("-filter_script:v");
        command.add(filterScript.toString());
        if (contentType.startsWith("video/")) {
            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add("veryfast");
            command.add("-crf");
            command.add("23");
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("128k");
            command.add("-movflags");
            command.add("+faststart");
        } else if (contentType.startsWith("image/jpeg")) {
            command.add("-q:v");
            command.add("2");
        }
        command.add(output.toString());
        return command;
    }

    private String drawTextFilter(Path watermarkFile) {
        StringBuilder filter = new StringBuilder("drawtext=");
        String font = resolveFont();
        if (font != null) {
            filter.append("fontfile='").append(escapeFilterPath(font)).append("':");
        }
        filter.append("textfile='")
            .append(escapeFilterPath(watermarkFile.toString().replace('\\', '/')))
            .append("'")
            .append(":fontcolor=white@0.55:fontsize=h/40:shadowcolor=black@0.4:shadowx=2:shadowy=2")
            .append(":x=w-tw-24:y=h-th-24");
        return filter.toString();
    }

    private String resolveFont() {
        String configured = properties.getWatermarkFont();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        for (String candidate : CJK_FONT_CANDIDATES) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static String escapeFilterPath(String path) {
        return path.replace(":", "\\:");
    }

    private ProcessResult run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readNBytes(OUTPUT_CAPTURE_LIMIT + 1);
            boolean finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException("WATERMARK_PROCESSING_FAILED", "ffmpeg timed out");
            }
            return new ProcessResult(
                process.exitValue(),
                new String(output, 0, Math.min(output.length, OUTPUT_CAPTURE_LIMIT), StandardCharsets.UTF_8)
            );
        } catch (IOException exception) {
            throw new BizException("WATERMARK_PROCESSING_FAILED",
                "cannot start ffmpeg: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException("WATERMARK_PROCESSING_FAILED", "ffmpeg interrupted");
        }
    }

    private static String extensionFor(String contentType) {
        if (contentType.startsWith("image/png")) {
            return ".png";
        }
        if (contentType.startsWith("image/jpeg")) {
            return ".jpg";
        }
        if (contentType.startsWith("video/quicktime")) {
            return ".mov";
        }
        if (contentType.startsWith("video/webm")) {
            return ".webm";
        }
        if (contentType.startsWith("video/3gpp")) {
            return ".3gp";
        }
        return ".mp4";
    }

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private static String normalize(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }

    private record ProcessResult(int exitCode, String errorTail) {
    }
}
