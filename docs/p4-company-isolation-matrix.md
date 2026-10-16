# P4 #5-2 公司数据隔离：读路径与负向验收清单

2026-10-15，核对基线 `c14f055`。下表记录实施前调用链与目标约束。
用户已确认两项既有访问例外，本批共享授权、受控/专用 SQL、附件、作业/监控已接通并通过真库负向矩阵；
当前进度与证据以 `docs/architecture-completion-plan.md` 及同批发布记录为准。生产未执行。

## 1. 实施前需要确认的两条规则

| 规则 | 当前行为 | 建议保留的范围（待用户确认） |
| --- | --- | --- |
| 跨公司审批参与者 | 具有 `workflow:task:read` 的当前待办处理人、实际已审批人和抄送人可读完整实例；其他有效历史参与者只读 SUMMARY；审批动作另查能力、任务归属与状态 | 保留现有任务关系授予的读取/审批权限；不因此获得其他单据、公司台账、导出、监控或作业管理权限 |
| 员工跨公司调岗后的本人历史单据 | 发起人/填报人可读自己的详情；桌面本人列表、移动“我发起的”和旧草稿按本人查询，不按当前公司过滤 | 保留本人历史访问及既有草稿规则；单据仍属创建时的公司，不扩展新公司的管理范围 |

这两项改变的是现有用户可用行为，不能用“ALL 限本公司”直接覆盖。
确认前保持现状；清单中的目标约束尚未启用。
NULL 和推断归属还需遵守下一节的历史确认边界，不能被上述例外默认为已确认。

## 2. 公司来源与固定边界

| 对象 | 权威来源 | 约束 |
| --- | --- | --- |
| 当前调用者公司 | 有效用户的 `dept_id → t_department.company_id` | 非全局管理员无有效公司时，管理查询为空、管理操作拒绝；不回退公司 1 |
| 审批实例 | `t_process_instance.company_id / company_inferred` | 不按发起人今天的部门或发起部门今天的公司重新推断 |
| 填报、无流程提交、草稿 | `t_form_data.company_id / company_inferred` | 与实例快照一致；调岗、审批和重提不改归属 |
| 附件、作业、事务消息 | 关联的填报/实例快照 | 无须先复制公司列；必须检查关联业务记录，不能从上传者/执行人的当前部门推公司 |
| 历史未确认数据 | `company_id IS NULL`，或 `company_inferred=true` 的推断快照 | NULL 只保留全局管理员确认边界；V54 明确要求同时考虑 inferred，不能把非 NULL 当事实、自动清标或按今天部门回填；推断行的启用须纳入人工确认验收 |

全局 `admin` 旁路、模板全局共享、表单 COMPANY 使用门槛保留。
非全局管理员的能力范围必须与已确认的业务公司快照相交：
ALL 只表示本公司，CUSTOM 中配置其他公司部门不授予跨公司管理权限。
本人和参与者例外只落在确认后的专用路径，不给报表、业务导出或管理动作增加隐式 OR。

## 3. 真实读路径与操作入口

下表中 Java 路径均相对 `backend/src/main/java/com/antflow/`。

| 入口 | 当前授权和调用链 | #5-2 需要收口及负向断言 |
| --- | --- | --- |
| 表单目录、打开、新草稿、发起 | `AuthorizationService.requireFormUse`；COMPANY 门槛与主体授权；旧草稿按保存的公司校验 | 继续使用原门槛；业务数据隔离不应把全局模板误变为公司私有。未授权公司直开、选项和发起仍拒绝 |
| 桌面实例列表、总数、工作台近期与状态计数 | `/api/instances`、`/api/workplace/overview` → `task/ProcessInstanceMapper.FULL_VISIBLE`；admin/本人/任务关系/能力范围 OR；ALL 目前全局；`scope=mine` 为独立分支 | 在 SQL 分页、count、聚合前施加公司边界；本人/参与者分支按确认结果；关键词、startedBy、时间与状态不能扩大范围 |
| 桌面/移动实例详情与历史 | `/api/instances/{id}`、`/{id}/history`、`/api/mobile/instances/{id}` → `AuthorizationService.instanceVisibility`；当前未读公司列；FULL/SUMMARY/NONE | 共享授权读取公司快照；直接猜 ID 与列表结论一致；SUMMARY 不得带表单值、附件、修订内容或作业载荷 |
| 移动本人实例、我发起的、无流程详情、最近流程 | `/api/mobile/instances`、`/initiated`、`/submissions/{id}`、bootstrap；`mobile/workflow/MobileWorkflowMapper.selectInstancePage/selectInitiatedPage/selectDirectSubmission/selectRecentProcesses` 为本人 SQL | 这些路径不自动继承通用范围拦截器；调岗规则要逐个覆盖，列表、hasMore、最近流程和详情一致；替换 userId 不能看到他人记录 |
| 桌面/移动待办、已办、抄送与角标 | `/api/tasks`、`/api/mobile/tasks`、bootstrap；`task/TaskMapper` 和 `MobileWorkflowMapper` 按 assignee/recipient；bootstrap 另查待办数和未读 CC | 跨公司任务规则同步作用于列表、详情、计数和已读；不能只拦详情而泄漏标题、申请人和数量 |
| 审批、转交、委托、加签、撤回与重提 | `task/TaskController`、`task/TaskOperationService`、`mobile/workflow/MobileWorkflowService`、`engine/ProcessEngine`；端点能力与任务/发起人归属、状态锁；普通动作未叠公司判断 | 可读不等于可执行；保留状态锁、原子更新、实际 assignee/approvedBy 约束。跨公司动作按确认规则，拒绝时不产生新任务、历史或通知 |
| 台账列表、详情 | `/api/forms/data/admin` → `FormDataService.authorizedPage` → `FormDataMapper.selectList` → `authz/DataPermissionPolicyHandler`；`/{id}` → `AuthorizationService.canReadFormData` | 列表与详情共享公司语义；当前 ALL 无公司约束；无实例时部门范围仍回查填报人当前部门，公司必须取 data 快照，不能跟随该回查漂移 |
| 桌面本人提交、两端草稿 | `/api/forms/data`、`/api/mobile/drafts`；`FormDataMapper.selectMySubmissions/countMySubmissions/selectMyDrafts` 显式豁免通用拦截器；`MobileDraftService` 校验本人和旧公司门槛 | 保留专用查询及显式豁免，按确认规则补业务条件；分页总数与行一致；撤权保留草稿并拒绝保存/提交，NULL 草稿不能靠请求 companyId 认领 |
| 业务导出及导出计数 | `/api/forms/data/admin/export`、`/api/reports/form-data-count`；export + read；`FormDataService.exportRows/countForExport` 复用受控 selectList/selectPage | 先过滤再限 10,000 行，列表/预览/count/实际下载同范围；不能只过滤下载内容；schema 查询只接收已授权行 ID |
| 审批报表与流程监控 | `/api/reports/approval-summary`、`/api/workflow-monitor` → `authz/InstanceScopeSql`；目前 admin 与非 admin ALL 都返回恒真 | 共享谓词加入实例公司条件；合计、按天/部门/表单、超时、卡死、驳回率、兜底与 outbox 均在聚合前过滤；未知 outbox 聚合类型继续排除 |
| 运行时选项及字段上下文 | `form/options/OptionRuntimeService.schema`；instanceId 需 FULL，dataId 需可读填报；另校验节点字段可见性；formCode 走使用授权 | 不能通过换成 dataId 绕过公司或节点 HIDDEN；草稿仍检查本人/表单匹配与原公司门槛；跨公司拒绝时不暴露字段或选项 |
| 附件元数据与下载 | `/api/mobile/files/{id}`、`/{id}/content` → `MobileFileService.requireReadable`；admin/上传者目前直接放行，其余经 `MobileFileAccessMapper.selectLinkedInstanceIds` 要求至少一个实例 FULL | 上传者分支必须显式验收，不能认为实例改完即完成；区分未挂接、草稿、无流程填报和实例附件；多记录共用文件时依据可读关联判权；SUMMARY 不能下载 |
| 附件删除与关联 | 同一 files 入口 DELETE；`MobileFileService.delete` 只允许 owner 删除未关联且非 PROCESSING 文件；`MobileFileLinkService` 仅挂接当前上传者的 READY 文件 | 保留行锁与事务后删对象，不得为隔离放宽“已关联不能删除”；调岗后的上传文件与旧草稿分别验收；不能挂接他人文件 |
| 作业详情、重试及强制管理 | FULL 实例详情才返回 `WorkflowJobService.listViews`；`/api/instances/{id}/jobs/{jobId}/retry`、terminate 与任务 override 经 `requireManageInstance/requireManageTask`；重试 SQL 校验 job 属于 instance | 公司边界同时作用于读取与管理范围；本人/参与者的读例外不能升级为跨公司重试/强制审批；替换 instanceId/jobId 不得越权或改变状态 |
| 自动化、超时与事务消息后台执行 | `automation/AutomationJobScheduler`、`notify/WorkflowOutboxDispatcher`；无请求主体、按已持久化 job/instance/recipient 执行；锁查询有专用豁免 | 系统 worker 必须继续处理两家公司；不把管理端 fail-closed 直接套进调度器；核对任务与实例关联、回调载荷、接收人和快照不串单 |
| 通知列表、未读数、已读 | `/api/mobile/notifications` → `MobileWorkflowMapper`；按 user_id，仅返回事件标题、instanceId/taskId、时间；详情另走授权 | 与参与者/本人规则核对；不能替换通知 ID 标记他人已读，旧通知深链不能绕过当前详情授权 |
| BI 台账和字典直连 | `infra/sql/bi-readonly-role.sql` 给 `antflow_bi` 五个全局只读视图；台账 V49、人员字典 V48、部门字典 V45；不经过应用鉴权且现无公司过滤 | 这是既有已接受的全局可信 BI 账号，不能发给公司管理员。应用过滤不构成 BI 隔离；面向公司的 BI 须先落实数据库账号/视图边界并用真实受限角色验证，当前未具备 |

组织公司、部门、成员管理及人员导出已有 #5-1 的 `OrgCompanyAccess`，
本批保留回归；审批运行时选择器仍沿用原候选语义，不能直接套组织管理边界导致跨公司任务无法选人。
`MobileFileAccessMapper.countReadableProcessLinks` 当前没有生产调用者，实际附件判权走
`selectLinkedInstanceIds → canReadFullInstance`，不能只修改旧 COUNT 查询。

## 4. 双公司负向验收矩阵

统一建 A/B 两家公司、同能力不同公司账号、全局 admin、无有效部门账号，
以及 A/B 的已确认快照、NULL 待确认、inferred 推断行。
使用真实 PostgreSQL；预期拒绝时同时核对响应和数据库未变化。

| 场景 | 验收结果 |
| --- | --- |
| A 的非 admin ALL 读取 B 的非本人/非参与记录 | 列表、总数、工作台、详情、选项、文件、业务导出和统计均无 B；直接 API 不可绕过 |
| CUSTOM 同时配置 A/B 部门；部门子树含异常跨公司关系 | 仍只在调用者公司内取交集；不因配置错误获得 B 的管理数据 |
| 非 admin 没有有效公司；缺能力；伪造客户端 companyId | 管理读取为空或拒绝，不能全局放行；客户端参数不能决定服务端授权公司 |
| A 员工调到 B；本人旧提交、旧草稿、草稿转正、旧文件 | 按确认的本人规则；快照不变，旧草稿继续按 A 门槛；撤权或 NULL 阻断保持，不删除数据 |
| B 人员被 A 单据指定审批/抄送；审批后、委托后、SKIPPED/CANCELLED | 按确认的任务规则，保留实际审批人与摘要的现有区分；不能看 A 的其他单据或取得管理权 |
| 本人/任务关系与 NULL/inferred 归属相遇 | 单独验证历史确认优先级，不能因 owner/assignee 的提前 return 静默开放未确认公司数据 |
| 全局 admin 与正常本公司 SELF/DEPT/CUSTOM/ALL | 合法读取和管理仍成功；不把原有表单使用或模板维护授权错误叠到业务数据读取 |
| 多页搜索与业务导出时间筛选 | 过滤先于 LIMIT/count；总数、下载、预览一致；零命中不退化为全部 |
| 文件 owner、非 owner、无实例直提、未挂接、多关联及 SUMMARY | 元数据与内容一致；至少一个关联可读的规则显式验证；下载失败不返回对象内容，删除/关联安全约束保留 |
| B 猜 A instanceId/jobId，或可读参与者尝试重试/override | 无作业敏感信息，无状态、attempts、history/outbox 变化；显式管理能力与范围仍必须满足 |
| 无 Principal 的调度器处理 A/B 作业、超时与通知 | 两家公司都正常推进；每个副作用只落自己的 instance/recipient；重投与锁保护保持 |
| BI 真实受限账号、全局可信账号、基表/角色提权尝试 | 不能用应用测试代替数据库授权测试；既有全局账号只用于全局可信场景，公司直连没有数据库边界就不得宣布隔离完成 |

## 5. 落地顺序与验证位置

1. **规则确认，预计 30 分钟。** 确认第 1 节两项例外并记录 DECISIONS；
   对齐 NULL/inferred 的人工确认与 BI 全局可信账号边界。
2. **共享授权与受控 SQL，预计 1 个工作日。** 修改 `AuthorizationService`、
   `DataPermissionPolicyHandler/DataPermissionRules`、`InstanceScopeSql` 和
   `ProcessInstanceMapper`；先覆盖 ALL/CUSTOM、NULL/inferred、无公司与详情/列表一致性。
3. **专用路径与副作用，预计 1 个工作日。** 同步移动 SQL、本人查询、附件关联、任务动作、
   作业/通知与 BI 数据库验收；复用现有边界，不增加租户体系或依赖。
4. **自动验收，预计 4 小时。** 优先扩充 `AuthorizationServiceTest`、
   `DataPermissionPolicyHandlerTest`、`WorkflowMonitoringControllerTest`、
   `MobileWorkflowServiceTest/MobileFileServiceTest` 及
   `PostgresTransactionalIntegrityIntegrationTest` 的范围、导出、报表、参与者和真库用例；
   依次跑后端真库、桌面、移动套件与构建。只有契约变化才重生成客户端。
5. **本机发布与提交，预计 1 小时。** 备份并验证恢复，部署本机，浏览器双公司实测并核对数据库；
   验收通过更新文档、提交。生产发布仍需独立窗口；不得把本机结果写成生产已隔离。

当前完成第 1/5 个工作项中的代码路径核对和验收清单；规则尚待确认。
上述时长为实施估算，不是已经完成的验证。
