package com.antflow.attachment;

import com.antflow.engine.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传件的本地暂存与命名/摘要等纯工具。
 *
 * <p>从 {@code MobileFileService} 原样搬出（方法体逐字未改）。**暂存文件的生命周期仍由调用方
 * （{@link MobileFileUploadService}）负责**：谁 stage 谁 deleteTemp，这里不自作主张清理——
 * 水印链路要用同一个临时文件反复覆盖，提前删会把上传打断。
 */
@Slf4j
class MobileFileStaging {

    /** 已落盘的暂存件：路径 + 真实字节数 + 内容摘要，三者必须同时来自同一次写完。 */
    record StagedFile(Path path, long size, String sha256) {
    }

    StagedFile stage(MultipartFile file) {
        Path path = null;
        try {
            path = Files.createTempFile("antflow-upload-", ".bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream input = new DigestInputStream(file.getInputStream(), digest);
                 var output = Files.newOutputStream(path)) {
                size = input.transferTo(output);
            }
            return new StagedFile(path, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteTemp(path);
            log.error("暂存上传文件失败", exception);
            throw new BizException("BAD_FILE", "文件上传失败（服务端暂存异常），请重试");
        }
    }

    /**
     * 读文件头给魔数嗅探用。512 字节是给"带 XML 声明的 SVG"留的余量——`<?xml ...?>` 之后还要能
     * 看见 `<svg` 根（只读 16 字节时只能看见声明本身，就没法把任意 XML 和 SVG 区分开）。
     */
    static byte[] readHeader(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(512);
        }
    }

    /** 流式算哈希：结果文件可能有 100MB，别为了算摘要再读进堆一份。 */
    static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("could not hash " + path, exception);
        }
    }

    static String kindPrefix(String contentType) {
        if (contentType.startsWith("image/")) {
            return "image/";
        }
        if (contentType.startsWith("video/")) {
            return "video/";
        }
        return "file/";
    }

    static String toMp4Name(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(0, dot) + ".mp4";
        }
        return name + ".mp4";
    }

    static String sanitizeName(String originalName) {
        String name = originalName == null || originalName.isBlank() ? "file" : originalName;
        name = name.replace("\\", "/");
        int separator = name.lastIndexOf('/');
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "file" : name;
    }

    static void deleteTemp(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
