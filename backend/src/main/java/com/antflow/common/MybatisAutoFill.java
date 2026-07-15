package com.antflow.common;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class MybatisAutoFill implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject m) {
        strictInsertFill(m, "createdAt", OffsetDateTime.class, OffsetDateTime.now());
    }
    @Override
    public void updateFill(MetaObject m) {
        // no-op
    }
}
