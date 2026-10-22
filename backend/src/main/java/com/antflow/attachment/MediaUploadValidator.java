package com.antflow.attachment;

import com.antflow.engine.BizException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传件"能不能收"的判定：声明大小、真实字节的魔数、以及 Content-Type 规范化。
 *
 * <p>从 {@code MobileFileService} 原样搬出（方法体逐字未改），单独成类只是为了让上传编排那段
 * 读起来只剩流程。**这里不做任何 IO**：字节由调用方读好再传进来，便于单测直接喂签名。
 */
class MediaUploadValidator {
    private static final int JPEG_SIGNATURE_SIZE = 3;

    private final MobileFileProperties properties;

    MediaUploadValidator(MobileFileProperties properties) {
        this.properties = properties;
    }

    void validateBasic(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BizException("BAD_FILE", "file is empty");
        }
        if (file.getSize() > sizeLimit(normalize(file.getContentType()))) {
            throw new BizException("BAD_FILE", "file is too large");
        }
    }

    long sizeLimit(String contentType) {
        return contentType.startsWith("video/")
            ? properties.getMaxVideoBytes() : properties.getMaxBytes();
    }

    void validateContent(String submittedContentType, byte[] content) {
        // Attachments accept arbitrary formats (including executables such as
        // .dll/.exe), so no signature-based rejection is applied here.
        if (submittedContentType.startsWith("image/")) {
            validateImageContent(submittedContentType, content);
        } else if (submittedContentType.startsWith("video/")) {
            String detectedContentType = detectContentType(content);
            // mp4 / quicktime / 3gpp / webm are all accepted regardless of the
            // exact submitted subtype, so iPhone .mov/.mp4 variances pass.
            if (detectedContentType == null || !detectedContentType.startsWith("video/")) {
                throw new BizException("BAD_FILE", "unsupported file content");
            }
        }
        // Other content types (attachments) are accepted as-is.
    }

    /**
     * 图片只做一件事：确认它**真的是图片**。
     *
     * <p>不校验"声明的子类型与字节一致"——安卓/微信的图库经常把 jpeg 标成 png，那种一律放行；
     * 但声明的 `image/*` 后面如果是任意字节（以前就是这么放过去的），它会一路走进同步的 ffmpeg 水印
     * 链路，最后甩给用户一个看不懂的 ffmpeg 报错。
     */
    private void validateImageContent(String submittedContentType, byte[] content) {
        if (detectImageContentType(content) == null) {
            throw new BizException("BAD_FILE",
                "文件内容不是支持的图片格式（jpeg/png/gif/webp/bmp/tiff/heic/avif/svg）");
        }
    }

    private static String detectImageContentType(byte[] content) {
        if (startsWith(content, new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        })) {
            return "image/png";
        }
        if (content.length >= JPEG_SIGNATURE_SIZE
            && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
            && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (startsWith(content, ascii("GIF87a")) || startsWith(content, ascii("GIF89a"))) {
            return "image/gif";
        }
        // RIFF....WEBP
        if (startsWith(content, ascii("RIFF")) && content.length >= 12
            && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return "image/webp";
        }
        if (startsWith(content, ascii("BM"))) {
            return "image/bmp";
        }
        // TIFF：II*\0（小端）或 MM\0*（大端）
        if (content.length >= 4
            && ((content[0] == 'I' && content[1] == 'I' && content[2] == 0x2A && content[3] == 0x00)
            || (content[0] == 'M' && content[1] == 'M' && content[2] == 0x00 && content[3] == 0x2A))) {
            return "image/tiff";
        }
        // ISO-BMFF：``????ftyp<brand>``。**iPhone 的 HEIC 必须认**：`convertHeic` 没开的字段（默认没开）
        // 是原样上传的，漏了它选张照片就被判成"不是图片"。AVIF 同理。
        if (content.length >= 12 && content[4] == 'f' && content[5] == 't' && content[6] == 'y'
            && content[7] == 'p') {
            String brand = new String(content, 8, 4, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            if (brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("msf")) {
                return "image/heic";
            }
            if (brand.startsWith("avif") || brand.startsWith("avis")) {
                return "image/avif";
            }
        }
        // SVG 是文本（`<svg` 或 `<?xml ...?><svg`），可能带 BOM 或前导空白。
        String head = new String(content, 0, Math.min(content.length, 64), StandardCharsets.UTF_8)
            .replace("\uFEFF", "");
        while (head.startsWith(" ") || head.startsWith("\n") || head.startsWith("\r")
            || head.startsWith("\t")) {
            head = head.substring(1);
        }
        head = head.toLowerCase(Locale.ROOT);
        if (head.startsWith("<svg") || head.startsWith("<!doctype svg")) {
            return "image/svg+xml";
        }
        // 带 XML 声明的 SVG：**声明之后必须真的出现 `<svg` 根**。只看 `<?xml` 会把任意 XML 文档
        // （RSS、配置、随便一段 XML）都当成图片放进来——报错文案也就跟着指错方向。
        if (head.startsWith("<?xml")) {
            int prologEnd = head.indexOf("?>");
            int root = head.indexOf("<svg");
            if (prologEnd > 0 && root > prologEnd) {
                return "image/svg+xml";
            }
        }
        return null;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String detectContentType(byte[] content) {
        if (startsWith(content, new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        })) {
            return "image/png";
        }
        if (content.length >= JPEG_SIGNATURE_SIZE
            && (content[0] & 0xFF) == 0xFF
            && (content[1] & 0xFF) == 0xD8
            && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (startsWith(content, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (content.length >= 12
            && matchesAt(content, "ftyp".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 4)) {
            String brand = new String(content, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)
                .toLowerCase(Locale.ROOT);
            if (brand.startsWith("qt")) {
                return "video/quicktime";
            }
            if (brand.startsWith("3gp")) {
                return "video/3gpp";
            }
            // 图片品牌也是 ftyp 盒子（HEIC/AVIF 家族）：声明成 video/* 的图片不能被当成视频送进
            // 水印链路（那会白跑一次 ffmpeg 然后把行标成失败）。这些品牌一律"不是视频"。
            if (brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("msf")
                || brand.startsWith("avi")) {
                return null;
            }
            return "video/mp4";
        }
        if (startsWith(content, new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})) {
            return "video/webm";
        }
        return null;
    }

    private static boolean matchesAt(byte[] content, byte[] marker, int start) {
        if (start < 0 || start + marker.length > content.length) {
            return false;
        }
        for (int index = 0; index < marker.length; index++) {
            if (content[start + index] != marker[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    static String normalize(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }
}
