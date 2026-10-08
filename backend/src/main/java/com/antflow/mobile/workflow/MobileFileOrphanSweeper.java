package com.antflow.mobile.workflow;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 孤儿对象清扫：桶里存在、但**没有任何 {@code t_mobile_file} 行引用**、且已经躺了 24 小时以上的对象。
 *
 * <p>24 小时宽限是为了让"正在写"的对象不被误删——写对象与插行之间还有窗口（转码结果用的是独立
 * attempt key，发布前也没有行引用它），而一次转码最多分钟级。
 *
 * <p>**默认只记日志（dry-run）**：删除是不可逆的，这个清扫器存在的意义是"能看见"，先跑一轮看看
 * 它打算删什么，确认无误后再把 `orphan-sweep-delete=true` 打开。
 */
@Component
@ConditionalOnProperty(prefix = "antflow.mobile.files", name = "storage", havingValue = "minio")
@RequiredArgsConstructor
@Slf4j
public class MobileFileOrphanSweeper {
    /** 每轮最多删几个：删除是逐对象的网络调用，一轮几万个会把启动/定时任务拖死。 */
    static final int BATCH = 500;
    static final int GRACE_HOURS = 24;

    private final FileStorage storage;
    private final MobileFileMapper fileMapper;
    private final MobileFileProperties properties;

    @Scheduled(fixedDelayString = "${antflow.mobile.files.orphan-sweep-interval-ms:3600000}")
    void sweep() {
        List<FileStorage.StoredKey> objects;
        try {
            objects = storage.list();
        } catch (RuntimeException error) {
            // 存储不支持列举（老实现）或暂时查不动：跳过本轮，别把定时任务打挂。
            log.debug("孤儿对象清扫跳过：{}", error.toString());
            return;
        }
        Set<String> referenced = new HashSet<>(fileMapper.selectAllStorageKeys());
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(GRACE_HOURS);
        List<FileStorage.StoredKey> orphans = objects.stream()
            .filter(object -> !referenced.contains(object.key()))
            .filter(object -> object.lastModified() != null
                && object.lastModified().isBefore(cutoff))
            .limit(BATCH)
            .toList();
        if (orphans.isEmpty()) return;
        if (!properties.isOrphanSweepDelete()) {
            log.warn("发现 {} 个无引用的旧对象（dry-run，未删除）：{}", orphans.size(),
                orphans.stream().map(FileStorage.StoredKey::key).limit(10).toList());
            return;
        }
        int deleted = 0;
        for (FileStorage.StoredKey orphan : orphans) {
            try {
                storage.delete(orphan.key());
                deleted++;
            } catch (Exception error) {
                log.warn("删除孤儿对象失败：{}", orphan.key(), error);
            }
        }
        log.info("已清理 {} 个孤儿对象（候选 {} 个）", deleted, orphans.size());
    }
}
