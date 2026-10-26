package com.antflow.mobile.workflow;

import com.antflow.attachment.FormDataFileMapper;
import com.antflow.attachment.MobileFileLinkService;
import com.antflow.authz.AuthorizationService;
import com.antflow.engine.ProcessEngine;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.form.runtime.MobileDraftService;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 移动端工作流门面：16 个公开入口，只做委托与**事务边界**，不再持有任何业务逻辑。
 *
 * <p>拆分的依据是"变化原因"，不是行数：
 * <ul>
 *   <li>{@link MobileTaskQueryService} —— 列表与分页口径（待办 / 已办 / 我发起的 / 我参与的）；</li>
 *   <li>{@link MobileTaskDetailService} —— 详情投影与可见性（FULL/SUMMARY、发起人视角、驳回目标）；</li>
 *   <li>{@link MobileTaskCommandService} —— 写操作（发起 / 审批 / 驳回 / 已读 / 撤回）；</li>
 *   <li>{@link MobileReworkService} —— 退回修改的读改写（锁、修订 Schema、暂存态）；</li>
 *   <li>{@link MobileFormFillService} —— 填单页投影（发布校验、草稿/返工上下文、starter 字段模式）；</li>
 *   <li>{@link MobileApprovalRecordService} / {@link MobileAttachmentProjector}
 *       / {@link MobileProjectionSupport} —— 三份被多处复用的投影口径。</li>
 * </ul>
 *
 * <p>**事务边界为什么留在这里**：控制器只认识门面，公开入口一进来就落在事务里，
 * 协作者内部再声明事务只会多一层"加入点"（单测直接 new 时还不生效）。因此新协作者一律没有
 * {@code @Transactional}；要改事务语义，改的是本类这 7 个注解，而不是散落的实现类。
 *
 * <p>**依赖方向**：协作者之间只允许正向依赖（命令/返工 → 取数 → 投影），
 * 没有任何一个协作者持有本类引用——本类对它们只是装配者与委托者。
 */
@Service
public class MobileWorkflowService {
    /** 填单页投影。 */
    private final MobileFormFillService forms;
    /** 列表查询。 */
    private final MobileTaskQueryService queries;
    /** 详情投影。 */
    private final MobileTaskDetailService details;
    /** 写操作。 */
    private final MobileTaskCommandService commands;
    /** 退回修改。 */
    private final MobileReworkService reworks;

    /**
     * 表单修订仓库是可选能力（未启用修订的环境里没有这个 bean），因此保留字段注入：
     * 改成构造器必选依赖会让这些环境直接起不来。
     */
    @Autowired(required = false)
    private DefinitionVersionRepository definitionVersions;

    /**
     * 延迟读取：上面的 bean 是字段注入，构造期还没赋值；协作者若直接持引用，
     * 就永远看到 null，"带修订的表单"分支在运行时静默失效。单测里也会在构造后替换该字段。
     */
    private final Supplier<DefinitionVersionRepository> definitionVersionsRef =
        () -> definitionVersions;

    /**
     * 显式构造器（替代 {@code @RequiredArgsConstructor}）：14 个参数原样保留，
     * 只为装配协作者。测试中的 {@code new MobileWorkflowService(engine, ...)} 一行未改。
     */
    public MobileWorkflowService(ProcessEngine engine, MobileDraftService draftService,
                                 MobileWorkflowMapper workflowMapper,
                                 FormDataFileMapper fileLinks,
                                 FormDefinitionService formDefinitionService,
                                 ProcessDefinitionService processDefinitionService,
                                 FormDataMapper formDataMapper,
                                 ProcessInstanceMapper instanceMapper, TaskMapper taskMapper,
                                 TaskHistoryMapper historyMapper,
                                 MobileFileLinkService fileLinkService, ObjectMapper objectMapper,
                                 AuthorizationService authorizationService) {
        // 装配顺序 = 依赖顺序：投影底座最先，取数其次，最后是命令/返工/填单。
        MobileProjectionSupport projection = new MobileProjectionSupport(objectMapper);
        MobileAttachmentProjector attachments =
            new MobileAttachmentProjector(fileLinks, formDefinitionService);
        MobileApprovalRecordService approvals =
            new MobileApprovalRecordService(workflowMapper, historyMapper);
        MobileEntityLookups lookups =
            new MobileEntityLookups(instanceMapper, taskMapper, formDataMapper);
        this.queries = new MobileTaskQueryService(workflowMapper, projection);
        this.details = new MobileTaskDetailService(workflowMapper, authorizationService,
            formDefinitionService, processDefinitionService, engine, approvals, attachments,
            projection, objectMapper);
        this.commands = new MobileTaskCommandService(engine, draftService, workflowMapper,
            formDefinitionService, processDefinitionService, instanceMapper, taskMapper,
            fileLinkService, objectMapper, authorizationService, attachments,
            lookups, definitionVersionsRef);
        this.reworks = new MobileReworkService(engine, formDefinitionService, formDataMapper,
            instanceMapper, taskMapper, fileLinkService, objectMapper, authorizationService,
            attachments, lookups, projection, definitionVersionsRef);
        this.forms = new MobileFormFillService(draftService, formDefinitionService,
            processDefinitionService, authorizationService, reworks, projection);
    }

    public MobileFormDto getMobileForm(String code) {
        return forms.getMobileForm(code);
    }

    public MobileFormDto getMobileForm(String code, Long draftId, Long reworkTaskId) {
        return forms.getMobileForm(code, draftId, reworkTaskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public MobileStartResult start(StartMobileInstanceRequest request, long userId) {
        return commands.start(request, userId);
    }

    public MobilePageDto<MobileInstanceDto> listInstances(long userId, int page, int size,
                                                          String keyword, String status) {
        return queries.listInstances(userId, page, size, keyword, status);
    }

    public MobilePageDto<MobileInitiatedDto> listInitiated(long userId, int page, int size,
                                                            String keyword, String status) {
        return queries.listInitiated(userId, page, size, keyword, status);
    }

    public MobileDirectSubmissionDetailDto getDirectSubmission(long dataId, long userId) {
        return details.getDirectSubmission(dataId, userId);
    }

    public MobileInstanceDetailDto getInstanceDetail(Long instanceId, long userId,
                                                      java.util.Collection<String> roles) {
        return details.getInstanceDetail(instanceId, userId, roles);
    }

    public MobilePageDto<MobileTaskDto> listTasks(String view, long userId, int page, int size,
                                                  String keyword, String status) {
        return queries.listTasks(view, userId, page, size, keyword, status);
    }

    public MobileTaskDetailDto getTaskDetail(Long taskId, long userId,
                                             java.util.Collection<String> roles) {
        return details.getTaskDetail(taskId, userId, roles);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markTaskRead(Long taskId, long userId) {
        commands.markTaskRead(taskId, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long taskId, MobileTaskActionRequest request, long userId) {
        commands.approve(taskId, request, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long taskId, MobileTaskActionRequest request, long userId) {
        commands.reject(taskId, request, userId);
    }

    public ReworkTaskDto getReworkTask(Long taskId, long userId) {
        return reworks.getReworkTask(taskId, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReworkTaskDto saveRework(Long taskId, ReworkTaskRequest request, long userId) {
        return reworks.saveRework(taskId, request, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReworkResult resubmitRework(Long taskId, ReworkTaskRequest request, long userId) {
        return reworks.resubmitRework(taskId, request, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long instanceId, long userId) {
        commands.withdraw(instanceId, userId);
    }
}
