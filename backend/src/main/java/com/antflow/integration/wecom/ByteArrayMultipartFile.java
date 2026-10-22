package com.antflow.integration.wecom;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

/** 把企微临时素材的内存字节包装成 MultipartFile，交给 MobileFileService 统一入库（原 WecomAppService 内部类）。 */
final class ByteArrayMultipartFile implements MultipartFile {
    private final String name;
    private final String contentType;
    private final byte[] content;

    ByteArrayMultipartFile(String name, String contentType, byte[] content) {
        this.name = name; this.contentType = contentType; this.content = content;
    }
    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return name; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return content.length == 0; }
    @Override public long getSize() { return content.length; }
    @Override public byte[] getBytes() { return content.clone(); }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
    @Override public void transferTo(java.io.File destination) throws IOException {
        java.nio.file.Files.write(destination.toPath(), content);
    }
}
