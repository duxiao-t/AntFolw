package com.antflow.attachment;

import com.antflow.engine.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 对象存储的写/删。**整个上传件的"对象生命周期"只在这里改**，因为它的顺序是行为的一部分：
 *
 * <ul>
 *   <li>写对象失败要变成 {@code FILE_STORAGE_FAILED}（不能漏成 500），调用方据此回滚整段事务；
 *   <li>删对象**必须在事务提交之后**：提交前删、事务又回滚，就留下"READY 行 + 对象已丢"；
 *       反过来（提交成功、删除失败）最多是个孤儿对象，没人会读到它。
 * </ul>
 *
 * <p>方法体从 {@code MobileFileService} 原样搬出，语义未改。
 */
@Slf4j
class MobileFileObjectStore {
    private final FileStorage storage;

    MobileFileObjectStore(FileStorage storage) {
        this.storage = storage;
    }

    void writeStorageObject(String storageKey, MobileFileStaging.StagedFile staged, String contentType) {
        try (InputStream content = Files.newInputStream(staged.path())) {
            storage.put(storageKey, content, staged.size(), contentType);
        } catch (IOException exception) {
            log.error("写入对象存储失败：{}", storageKey, exception);
            throw new BizException("FILE_STORAGE_FAILED", "文件存储失败，请稍后重试");
        }
    }

    /**
     * 对象在不在。**故意不吞异常**：去重补写时"对象不在"要补写，"存储查不动"必须整个请求失败——
     * 混为一谈要么每次抖动白重传一份，要么把锅甩给用户的文件。
     */
    boolean exists(String storageKey) {
        return storage.exists(storageKey);
    }

    /** 事务提交后再删对象；删失败只记日志（行已经 DELETED，最坏是个没人引用的孤儿对象）。 */
    void deleteAfterCommit(String storageKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteObjectQuietly(storageKey);
                }
            });
        } else {
            deleteObjectQuietly(storageKey);
        }
    }

    void deleteObjectQuietly(String key) {
        try {
            storage.delete(key);
        } catch (Exception exception) {
            log.warn("清理旧对象失败（不影响本次结果）：{}", key, exception);
        }
    }
}
