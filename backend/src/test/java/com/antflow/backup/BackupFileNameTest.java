package com.antflow.backup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定备份文件名的判定口径。
 *
 * <p>背景：{@code bc72eeb} 给"仅数据库"备份加了 {@code -db} 前缀，但 {@code files()} 与 {@code file()}
 * 各自写死了一份 {@code antflow-[0-9-]+}，谁都没跟着改 —— 结果是仅库备份**生成成功却列不出来**，
 * 就算知道文件名也下载不了（{@code file()} 会判定"不存在"）。
 *
 * <p>这两条路径必须共用 {@link BackupService#BACKUP_NAME}，本测试就是那个"别人改正则要响"的哨兵。
 */
class BackupFileNameTest {

    @Test
    void acceptsBothFullAndDatabaseOnlyArchives() {
        assertThat(BackupService.BACKUP_NAME.matcher("antflow-20261022-070501.afbackup").matches())
            .as("全量备份").isTrue();
        assertThat(BackupService.BACKUP_NAME.matcher("antflow-db-20261022-071059.afbackup").matches())
            .as("仅数据库备份").isTrue();
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(BackupService.BACKUP_NAME.matcher("antflow-db2-20261022.afbackup").matches())
            .as("未知前缀").isFalse();
        assertThat(BackupService.BACKUP_NAME.matcher("antflow-.afbackup").matches())
            .as("缺时间戳").isFalse();
        assertThat(BackupService.BACKUP_NAME.matcher("antflow-db-20261022-071059.sql").matches())
            .as("扩展名不符").isFalse();
        assertThat(BackupService.BACKUP_NAME.matcher("../antflow-db-20261022-071059.afbackup").matches())
            .as("路径穿越必须被名字校验拦住").isFalse();
        assertThat(BackupService.BACKUP_NAME.matcher("other-20261022-071059.afbackup").matches())
            .as("非本服务产物").isFalse();
    }
}
