package com.antflow.common;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class MybatisAutoFill implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject m) {
        OffsetDateTime now = OffsetDateTime.now();
        strictInsertFill(m, "createdAt", OffsetDateTime.class, now);
        // updatedAt 列在 INSERT/UPDATE 路径上都需要被填充（schema: NOT NULL）。
        // INSERT 时与 createdAt 同值，保持审计链一致。
        strictInsertFill(m, "updatedAt", OffsetDateTime.class, now);
    }
    @Override
    public void updateFill(MetaObject m) {
        strictInsertFill(m, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
    }
}
