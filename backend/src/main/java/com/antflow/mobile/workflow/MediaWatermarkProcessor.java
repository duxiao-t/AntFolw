package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applies a text watermark (configured text + upload time) to images and videos
 * with a local ffmpeg binary. Images are re-encoded in their original format,
 * videos are normalized to MP4 (H.264 + AAC).
 *
 * <p>图片是**在请求内同步**跑 ffmpeg 的：二进制缺失会让整个上传失败（422），所以启动时先探一次
 * （见 {@link #probeOnStartup()}），缺什么就写日志——总比让用户看到
 * {@code Cannot run program "ffmpeg"} 强。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaWatermarkProcessor {
    private static final int PROCESS_TIMEOUT_MINUTES = 10;
    private static final int PROBE_TIMEOUT_SECONDS = 3;
    /** 从 ffmpeg 日志文件末尾取多少字节当诊断。 */
    private static final int OUTPUT_CAPTURE_LIMIT = 8 * 1024;
    /** 报给用户的失败原因里最多留多少字（ffmpeg 的报错经常很长）。 */
    private static final int USER_MESSAGE_LIMIT = 200;
    private static final List<String> CJK_FONT_CANDIDATES = List.of(
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/simhei.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
    );

    private final MobileMediaProperties properties;

    /**
     * 启动探测：ffmpeg 能不能跑、中文水印有没有字体。**只写日志**，不拦启动——没装 ffmpeg 时其它
     * 功能都正常，只是"加水印的上传"会失败。
     *
     * <p>用 {@code @PostConstruct} 而不是 {@code ApplicationReadyEvent}：它不依赖数据库/对象存储，
     * 启动早期就能查到（那个事件已经被视频恢复占用了）。
     */
    @PostConstruct
    void probeOnStartup() {
        Capabilities capabilities = probe();
        if (!capabilities.ffmpegAvailable()) {
            log.warn("ffmpeg 不可用（antflow.media.ffmpeg-bin='{}'）：开了水印的图片/视频上传会失败。"
                + "镜像里装 ffmpeg（Dockerfile 已装）或把 FFMPEG_BIN 指到可执行的路径。",
                properties.getFfmpegBin());
        } else if (capabilities.fontPath() == null) {
            log.warn("没有可用的中文字体（antflow.media.watermark-font 为空且候选路径都不存在）："
                + "中文水印会显示成方框或被 ffmpeg 拒绝。装 fonts-wqy-microhei 或设置 WATERMARK_FONT。");
        } else {
            log.info("媒体水印就绪：ffmpeg='{}'，字体='{}'",
                properties.getFfmpegBin(), capabilities.fontPath());
        }
    }

    record Capabilities(boolean ffmpegAvailable, String fontPath) { }

    Capabilities probe() {
        return new Capabilities(ffmpegRuns(), resolveFont());
    }

    /**
     * 探测必须**不读管道**：{@code -version} 的输出直接丢掉，否则子进程输出一多、我们不读就会把管道
     * 填满，本该几毫秒的探测能把启动挂住（{@link #run} 曾经就是这个毛病）。
     */
    private boolean ffmpegRuns() {
        try {
            Process process = new ProcessBuilder(properties.getFfmpegBin(), "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean supports(String contentType) {
        String type = normalize(contentType);
        return type.startsWith("image/jpeg")
            || type.startsWith("image/png")
            || type.startsWith("video/");
    }

    public String resultContentType(String contentType) {
        return normalize(contentType).startsWith("video/") ? "video/mp4" : normalize(contentType);
    }

    /**
     * 水印处理的**唯一入口**：输入是磁盘上的文件，输出也落在磁盘上（`workDir/output…`），
     * 全过程不把整份媒体读进堆。
     *
     * <p>{@code workDir} 由**调用方**创建并负责清理（{@link #deleteRecursively}）——上一版这里是
     * `apply(byte[])`，`readAllBytes` 进来、`Files.readAllBytes` 回去，一个 100MB 视频在 768MB 的堆里
     * 峰值能到 300MB，两个并发就 OOM。
     *
     * @return ffmpeg 的输出文件（视频 `output.mp4`，图片 `output<ext>`）
     */
    public Path applyTo(Path input, Path workDir, String contentType, String watermarkText) {
        String sourceType = normalize(contentType);
        String sourceExtension = extensionFor(sourceType);
        try {
            String watermark = watermarkText.trim() + " "
                + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
            Path watermarkFile = workDir.resolve("watermark.txt");
            Files.write(watermarkFile, watermark.getBytes(StandardCharsets.UTF_8));

            Path filterScript = workDir.resolve("filter.txt");
            Files.write(filterScript, drawTextFilter(watermarkFile).getBytes(StandardCharsets.UTF_8));

            Path output = workDir.resolve(sourceType.startsWith("video/") ? "output.mp4"
                : "output" + sourceExtension);
            ProcessResult result = run(command(input, output, filterScript, sourceType),
                workDir.resolve("ffmpeg.log"));
            if (result.exitCode != 0) {
                log.error("ffmpeg 退出码 {}：{}", result.exitCode, result.errorTail);
                String detail = firstLine(result.errorTail);
                throw new BizException("WATERMARK_PROCESSING_FAILED",
                    (detail.isEmpty() ? "图片/视频水印处理失败。" : "水印处理失败：" + detail + "。")
                        + "详情见服务端日志。");
            }
            return output;
        } catch (IOException exception) {
            // 这里是临时文件读写的失败（不是 ffmpeg 本身）：技术细节进日志，用户看到的是"服务端故障"。
            log.error("水印处理的临时文件操作失败", exception);
            throw new BizException("WATERMARK_PROCESSING_FAILED",
                "图片/视频水印处理失败（服务端临时文件异常），详情见服务端日志。");
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
            // 配了但文件不在（打错路径/没挂进容器）：当成"没有字体"处理——把不存在的 fontfile 交给
            // ffmpeg 会直接报错，而启动探测也需要能看出这件事。
            return Files.exists(Path.of(configured)) ? configured : null;
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

    /**
     * 跑 ffmpeg。
     *
     * <p>输出**重定向到文件**再等进程：原来先 {@code readNBytes} 再 {@code waitFor}，ffmpeg 输出一多
     * （超过 8KB）管道就会填满、进程卡住不退出，最后被误判成超时——本来能完成的转码白等 10 分钟。
     * 失败时读日志文件末尾当诊断，用户那边只看到一个短句。
     */
    private ProcessResult run(List<String> command, Path logFile) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        } catch (IOException exception) {
            log.error("启动 ffmpeg 失败（antflow.media.ffmpeg-bin='{}'）",
                properties.getFfmpegBin(), exception);
            throw new BizException("WATERMARK_PROCESSING_FAILED",
                "服务器缺少 ffmpeg，无法为图片/视频添加水印。请联系管理员安装 ffmpeg"
                    + "（或配置 antflow.media.ffmpeg-bin）后重试。");
        }
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                log.error("ffmpeg 超时（{} 分钟）", PROCESS_TIMEOUT_MINUTES);
                throw new BizException("WATERMARK_PROCESSING_FAILED",
                    "图片/视频水印处理超时，请换更小的文件重试；详情见服务端日志。");
            }
            return new ProcessResult(process.exitValue(), tailOf(logFile));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            // 中断了就一定要把子进程收掉：原来只重新打断自己，ffmpeg 继续占着 CPU 与磁盘编码，
            // 而调用方的 finally 已经把临时目录删了。
            process.destroyForcibly();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            throw new BizException("WATERMARK_PROCESSING_FAILED", "水印处理被中断，请重试。");
        }
    }

    /**
     * 读 ffmpeg 日志的末尾（最多 8KB）当诊断信息。
     *
     * <p>只 seek 到末尾读那一段：以前 `Files.readAllBytes(整个日志)` 再截尾——异常时 ffmpeg 能刷出
     * 很大一段 stderr，每次失败都要把整份读进堆，正好发生在内存最紧张的时候。
     */
    private static String tailOf(Path logFile) {
        try (SeekableByteChannel channel = Files.newByteChannel(logFile, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, OUTPUT_CAPTURE_LIMIT);
            if (length <= 0) return "";
            ByteBuffer buffer = ByteBuffer.allocate(length);
            channel.position(size - length);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // keep reading until the tail segment is filled
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    /** ffmpeg 的报错动不动几百行：给用户留第一行、截断，完整的进日志。 */
    private static String firstLine(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) return "";
        int newline = trimmed.indexOf('\n');
        String first = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return first.length() > USER_MESSAGE_LIMIT
            ? first.substring(0, USER_MESSAGE_LIMIT) + "…" : first;
    }

    static String extensionFor(String contentType) {
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

    static void deleteRecursively(Path root) {
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
