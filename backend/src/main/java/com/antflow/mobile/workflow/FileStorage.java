package com.antflow.mobile.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.core.io.Resource;

public interface FileStorage {
    StoredObject put(String storageKey, InputStream content, long size, String contentType)
        throws IOException;

    Resource get(String storageKey);

    /**
     * 对象在不在。**故意不抛受检异常**：调用方（去重时判断要不要补写）必须能把"对象不在"和
     * "存储查不动"分开处理——前者补写，后者要整个请求失败。
     */
    boolean exists(String storageKey);

    void delete(String storageKey) throws IOException;

    /** 桶里现有对象（key + 最后修改时间），给孤儿清扫器用。默认不支持：实现不必为此存在。 */
    default List<StoredKey> list() {
        throw new UnsupportedOperationException("storage does not support listing");
    }

    record StoredKey(String key, OffsetDateTime lastModified) { }
}
