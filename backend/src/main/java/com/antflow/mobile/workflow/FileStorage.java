package com.antflow.mobile.workflow;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.Resource;

public interface FileStorage {
    StoredObject put(String storageKey, InputStream content, long size) throws IOException;

    Resource get(String storageKey);

    void delete(String storageKey) throws IOException;
}
