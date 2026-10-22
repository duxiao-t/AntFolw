package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;

/**
 * 按主键取实体的三个查询，缺失时统一抛 {@code NOT_FOUND} + 固定文案。
 *
 * <p>为什么值得单独一个类：写操作（审批/已读）和返工（读改写）是两簇，
 * 但它们对"实体不存在"的反应必须一模一样——前端按 {@code code} 显示文案，
 * 复制一份之后出现 {@code "instance not found"} 与 {@code "流程不存在"} 两种说法，
 * 排查时就得先去猜是哪条路径。
 *
 * <p>刻意不加锁：需要悲观锁的调用方（返工）自己调 {@code selectForUpdate}，
 * 在这里统一加锁会把详情查询也拖进事务。
 */
final class MobileEntityLookups {
    private final ProcessInstanceMapper instanceMapper;
    private final TaskMapper taskMapper;
    private final FormDataMapper formDataMapper;

    MobileEntityLookups(ProcessInstanceMapper instanceMapper, TaskMapper taskMapper,
                        FormDataMapper formDataMapper) {
        this.instanceMapper = instanceMapper;
        this.taskMapper = taskMapper;
        this.formDataMapper = formDataMapper;
    }

    ProcessInstance requireExistingInstance(Long instanceId) {
        ProcessInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BizException("NOT_FOUND", "instance not found");
        }
        return instance;
    }

    TaskEntity requireExistingTask(Long taskId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("NOT_FOUND", "task not found");
        }
        return task;
    }

    FormData requireFormData(Long formDataId) {
        FormData formData = formDataMapper.selectRuntimeById(formDataId);
        if (formData == null) {
            throw new BizException("NOT_FOUND", "form data not found");
        }
        return formData;
    }
}
