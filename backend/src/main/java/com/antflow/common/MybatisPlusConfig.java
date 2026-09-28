package com.antflow.common;

import com.antflow.authz.DataPermissionPolicyHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MybatisPlusConfig {

    private final DataPermissionPolicyHandler dataPermissionPolicyHandler;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        var interceptor = new MybatisPlusInterceptor();
        // 行级数据权限必须排在分页之前：分页的 COUNT 查询才会带上范围条件。
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(dataPermissionPolicyHandler));
        // Required by spec decision #17 — concurrent approve double-clicks.
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // Used by /api/tasks and /api/forms/data list pagination.
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
