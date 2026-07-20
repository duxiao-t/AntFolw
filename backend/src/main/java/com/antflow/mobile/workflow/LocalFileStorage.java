package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalFileStorage implements FileStorage {
    private final MobileFileProperties properties;

    @Override
    public StoredObject put(String storageKey, InputStream content, long size) throws IOException {
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new StoredObject(storageKey, size);
    }

    @Override
    public Resource get(String storageKey) {
        return new FileSystemResource(resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    private Path resolve(String storageKey) {
        Path root = properties.getDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new BizException("BAD_FILE_PATH", "invalid file path");
        }
        return target;
    }
}
