# 权限体系设计审计报告（2026-09-21）

- 分支：`codex/authz-v2-remediation`（8 个 authz 提交 + 本日 4 个主题提交，`git rev-list --count master..HEAD` = 12，0 落后）。
- 本报告为只读审计，不含代码改动。每条目字段：编号 / 问题 / 证据(file:line) / 受影响人群 / 现状行为 / 影响 / 建议 / 代价 / 置信度。
- 置信度三档：**已复核**（逐行读过源码或迁移）、**推断**（由两处已确认事实推导，机制未完全证实）、**待实测**（需运行时验证）。
- 与此前交付计划的差异已在附录 B 逐条标注：3 处结论被本轮复核修正。

## 1. 结论摘要

- **端点层**：结构清晰、`AuthorizationCoverageTest` 兜底"端点必须声明鉴权"，但注解与服务层双重校验之间无一致性对账，`consoleEntry()` 被挪用于非自助端点。
- **资源层**：语义正确（fail-closed、隐藏资源返回 404），但 `requireManageTask` 与 `requireManageInstance` 对同一"可读但越范围"情形返回不同状态码。
- **数据范围层**：规则表驱动是对的，但同一范围语义存在 3+1 处实现（Java / MyBatis handler / Mapper SQL / SELF 特判），且无 principal 时不注入条件（隐性全表）。
- **配置层**：角色/菜单编辑器功能可用，但能力树无搜索、改动无影响面提示、同一枚举在三个页面有三种文案、菜单与角色两个编辑器互不解释。
- **最该先动的三件事**：
  1. 修 `Contacts.tsx:471` 死键 `canWriteDepartments`（一行注册 + 一次回归，约 10 分钟）。
  2. 拍板"越范围"状态码语义并统一 `requireManageTask`/`requireManageInstance`（半天，含测试）。
  3. 拍板员工是否应持有 `console:entry:access`（决定桌面入口语义与 403 页的真正受众，不写代码，先决策）。

## 2. 基线：系统里到底有谁

### 2.1 角色清单（已复核）

| 角色 | 来源 | 说明 |
| --- | --- | --- |
| admin | V20 内置 | 隐式全能力（快照对 admin 直接展开全部非 deprecated 能力） |
| employee | V20 内置 `user`，V40:175 改名 | 普通用户，能力集见 2.2 |
| auditor | V20:44-50 内置 | 只读审计：audit:event:read/export/archive:download + page.security.audit_log（V40 后换算为 audit:* + console:entry:access） |
| 自定义角色 | 运行期创建 | 能力由角色编辑器授予；admin_only 能力被 `RoleAdminService.java:313` 阻止授予 |

### 2.2 employee 的 14 项能力及推导链（已复核）

| # | 能力码 | 授予迁移 |
| --- | --- | --- |
| 1 | form:runtime:read | V20:148-164（旧码 form.runtime.read）→ V40 映射 |
| 2 | workflow:instance:start | V20 → V40 |
| 3 | workflow:instance:read | V20 → V40 |
| 4 | workflow:instance:withdraw | V20 → V40 |
| 5 | workflow:task:approve | V20 → V40 |
| 6 | workflow:task:reject | V20 → V40 |
| 7 | workflow:task:transfer | V20 → V40 |
| 8 | workflow:task:delegate | V20 → V40 |
| 9 | workflow:task:add_assignee | V20 → V40 |
| 10 | workflow:task:recall | V20 → V40 |
| 11 | file:attachment:upload | V20（file.upload）→ V40 |
| 12 | file:attachment:read | V20（file.read）→ V40 |
| 13 | workflow:task:read | V23 追加（授予所有 page.workplace 持有者） |
| 14 | console:entry:access | V40:169-173 由 page.workplace 持有者派生；V41:17-41 的回收清单（admin_only + integration:*）不含它，未回收 |

推导链核对：V20 给 `user` 角色授 12 项（V20:148-164 的 IN 列表恰好 12 个旧码）→ V21:51-52 补 `page.workplace` → V23 给所有 page.workplace 持有者补 `workflow.task.read` → V40 将 14 个旧码 1:1 换算成新码并删 `page.*`，同时按第 5 步派生 `console:entry:access`。最终 14 项，与权限目录/快照一致。

**注意**：第 7/9/10 项（transfer/delegate/add_assignee/recall）与第 13 项（task:read）的真实语义见第 8 章红线与第 9 章 F1。

## 3. 已确认缺陷

### D1 「新建一级部门」按钮对包括管理员在内的所有人永久禁用
- 问题：`access.canWriteDepartments` 从未被任何 access 来源产出，模板里 `!access.canWriteDepartments` 恒为 true。
- 证据：frontend/src/pages/org/Contacts.tsx:471（全仓唯一引用）；frontend/src/access.ts:24-40（构造来源只有 `canAdmin`/`can`/`canEnterConsole`/PAGES/HIDDEN_*，无该键）。
- 受影响人群：所有桌面用户，含 admin。
- 现状行为：按钮永远 disabled，功能只能靠其它入口（如有）或接口直调。
- 影响：功能不可达；同时说明 access 键无静态校验，拼错键名静默失效。
- 建议：在 registry 的 HIDDEN_CAPABILITIES 注册 `org:department:manage`（自动生成 `canOrgDepartmentManage`），或给 access 增加键存在性测试。
- 代价：约 5 行 + 一次 tsc/测试，30 分钟内。
- 置信度：已复核

### D2 「用户权限」页面存在两套裁决标准（双门禁），当前未造成实际缺口
- 问题：菜单放行依据能力码 `security:user_role:read`（page-capabilities.json:11），页面却硬编码 `roles.includes('admin')`（UserPermission.tsx:69、:117）。二者是真源不一致；本轮复核补充：`security:user_role:read` 是 admin_only（V40:420），且 `RoleAdminService.java:313` 阻止把它授予普通角色，所以今天菜单层面也只有 admin 能见——**是冗余双源，不是可利用矛盾**。
- 证据：frontend/src/pages/security/UserPermission.tsx:69,117；frontend/config/page-capabilities.json:11；backend/src/main/java/com/antflow/authz/RoleAdminService.java:313；V40:420（admin_only=true）。
- 受影响人群：开发者/审计者（理解成本）；未来若 admin_only 政策松动则升级为可见性分裂。
- 现状行为：菜单能力码与页面 isAdmin 各自裁决，恰好目前同真同假。
- 影响：任何一侧单独变化（如放开 admin_only 授予，或页面改判据）都会造成"菜单能进、页面拒绝"或反向。
- 建议：二选一：页面改用 `hasCapability(user, CAPABILITY.securityUserRoleRead)`；或保留 isAdmin 并在 page-capabilities 注明"此页仅 admin"。倾向前者。
- 代价：约 3 行；需同步检查 Role.tsx:96 的同型 isAdmin（Role.tsx 也有硬编码，且其菜单码 security:role:read 非 admin_only，语义上是「security:role:manage 才能写」——见 D2 备注）。
- 置信度：已复核（较原计划"自相矛盾"下调，见附录 B）

### D3 同一"越范围"情形，两个入口返回不同状态码
- 问题：`requireManageTask`（AuthorizationService.java:361-368）在不可读时抛 HiddenResourceException → 404；`requireManageInstance`（:370-386）在可读但数据范围外抛 AccessDeniedException → 403。对"范围外管理"这同一情形，任务入口 404、实例入口 403。
- 证据：backend/src/main/java/com/antflow/authz/AuthorizationService.java:361-368,370-386；GlobalExceptionHandler.java:37-46（Hidden→404 NOT_FOUND）、:93-100（AccessDenied→403 FORBIDDEN）。
- 受影响人群：前端与第三方集成（错误分支）；桌面用户（重试/报错文案）。
- 现状行为：同一授权状态，HTTP 语义随入口不同。
- 影响：前端无法用状态码区分"不存在"与"越权"，容易把越权当数据 bug 处理；审计里 404/403 语义被稀释。
- 建议：定一条规则写进 DECISIONS："范围内可读资源的管理越权=403；任何不可读=404（隐藏资源）"，然后按规则统一 requireManageTask 路径（可读但越范围应 403——现状 404 是把 readable 检查提前吞了）。
- 代价：0.5 天含回归测试。
- 置信度：已复核

## 4. 命名与文案可读性

### N1 `form:authorization:manage` 一个能力码混指两套 schema
- 证据：FormGrantController.java:20-73（8 个端点同码，覆盖 t_form_resource_grant 使用范围与 t_form_maintainer 维护人员两组资源）；FormGrantService.java:50,66；PermissionCatalog（V44 起 name 已改成"管理表单使用范围与维护人员"，承认混指）。
- 影响：无法单独回收"维护人员管理"而保留"使用范围管理"；审计日志里两类操作同码。
- 建议：新增 `form:grant:manage` / `form:maintainer:manage`，V45+ 迁移换算，旧码 deprecated。
- 代价：1 个迁移 + 控制器/目录改动，约 1 天。
- 置信度：已复核

### N2 `workflow:task:read` 标签「查看本人任务」与真实语义不符
- 证据：V40:250（name=查看本人任务，scopeable=false）；真实裁决在 AuthorizationService.java:340-343（能力存在 + isReadableTaskAssignee 即 FULL），与数据范围无关。
- 影响：管理员会把它当成"按数据范围裁剪"的能力配置；审计者会把它当 bug 上报。
- 建议：目录标签改为「查看被指派任务」。**语义本身是审批人待办的地基，不得改裁决（红线 1）**。
- 代价：目录 name 一行 + 快照同步。
- 置信度：已复核

### N3 三个「查看」互相含混
- 证据：`form:definition:read`（查看表单=配置对象）、`form:runtime:read`（使用已发布表单）、`form:data:read`（查看表单数据）（V40:80-150）。
- 影响：管理员在能力树里无法从名字区分三者，误配率高。
- 建议：标签改为「查看表单配置 / 使用已发布表单 / 查看填报数据」。
- 代价：3 行目录文案。
- 置信度：已复核

### N4 数据范围文案三个页面三种说法，且直接输出枚举名
- 证据：Role.tsx:59-63（SELF=仅本人，ALL=全部数据）；UserPermission.tsx:171-172（`scope.modes.join(' / ')` 直接拼枚举名，SELF 又译"本人"，ALL 译"全部部门"）；FormGrantUserPicker.tsx:118（全部部门）。
- 影响：同一概念三套词，管理员无法建立稳定心智模型；原始枚举名暴露给非技术用户。
- 建议：抽一个 `scopeLabel(mode)` 共享函数，枚举→中文一处定义。
- 代价：约 20 行 + 三处替换。
- 置信度：已复核

### N5 菜单编辑器暴露原始 pageKey 与权限数组；异常文案仍是英文
- 证据：frontend/src/pages/security/Menu.tsx:134,141-146（新页面 name 直接用 page.key，requiredPermissions 原样数组）；GlobalExceptionHandler.java:45,99（"NOT_FOUND"/"instance is outside your management scope"）。
- 影响：菜单编辑器可读性差；后端 403/异常 envelope 英文，与全中文 UI 割裂（403 页面本体已中文化，见第 7 章）。
- 建议：菜单编辑器显示 PAGES 注册表里的中文标题；envelope message 按错误码做前端映射，后端保持码稳定。
- 代价：前端半天；后端不动。
- 置信度：已复核

## 5. 管理员配置体验

### E1 能力树无搜索，而角色搜索就在旁边
- 证据：Role.tsx:110-115（filteredRoles 只过滤角色列表）；能力树渲染无任何搜索输入（~50 能力 / 9 域，PermissionCatalog ENTRIES）。
- 影响：改一个中大型角色的配置要在树里逐域翻找。
- 建议：Tree 外层加一个前端过滤输入（按 code/name contains），无后端改动。
- 代价：约 30 行。
- 置信度：已复核

### E2 取消勾选零提示、保存即时生效
- 证据：Role.tsx `updateGrant`/保存流程无确认与影响面统计；后端无"该角色影响多少用户"端点。
- 影响：取消 `console:entry:access` 会让一批人直接失去桌面入口，管理员无感知；authz_version 失效是即时且不可逆的（需重新勾选恢复）。
- 建议：保存前二次确认；后端加一个 `GET /api/security/roles/{id}/impact`（count 用户）。
- 代价：前端 1 小时 + 后端 1 个只读端点。
- 置信度：已复核（以代码中不存在提示/统计逻辑为准）

### E3 范围语义只有工具栏一行文字
- 证据：Role.tsx:59-63 仅三个 option label；CUSTOM 部门选择、scopeOverride 与默认范围的优先级（AuthorizationService.java:579-585）无任何页面内解释。
- 影响：管理员不理解"覆盖值优先于默认范围"，配出的结果与预期不符。
- 建议：范围选择旁加一行 Tooltip 说明优先级与 CUSTOM 行为。
- 代价：1 小时。
- 置信度：已复核

### E4 菜单编辑器与角色编辑器互不解释
- 证据：Menu.tsx 不展示 pageKey→能力码的映射含义；Role.tsx 不展示"勾选哪些能力会点亮哪个菜单"。
- 影响：两个编辑器各自改，管理员无法推出"为什么这个角色看不到这个菜单"。
- 建议：Menu.tsx 每个页面节点展示其能力码（已有 requiredPermissions 字段，补展示）；Role.tsx 能力树对页面型能力加"点亮 xx 菜单"标记。
- 代价：合计半天。
- 置信度：已复核

## 6. 结构与去重

### S1 `t_menu.required_permissions` 与 `t_menu.version` 是死列
- 证据：MenuService.java:113 写入、:126-141 loadTree 读回 required_permissions、:148 build() 立即被 `pageCapabilities.require(row.pageKey())` 覆盖；t_menu.version 定义于 V40:230 但 loadTree 不 SELECT，实际版本号在 t_menu_revision（V41:3-12，MenuService.java:181）。
- 影响：两列误导读者以为 DB 是菜单能力真源；写放大无意义。
- 建议：V45 清理（列删除或标记 deprecated + 代码停止写入）。V45 已获批。
- 代价：1 个迁移 + 少量代码。
- 置信度：已复核

### S2 数据范围逻辑重复实现 3+1 处
- 证据：AuthorizationService.java:408-460（inDataScope，Java switch）；DataPermissionPolicyHandler（MyBatis SQL 注入，规则表驱动）；ProcessInstanceMapper.java:40-60（FULL_VISIBLE SQL 内联 DEPARTMENT_AND_DESCENDANTS EXISTS）；FormGrantService.java:295,349（SELF 特判）。
- 影响：语义漂移风险——四处对 CUSTOM/DESCENDANTS 的边界理解必须人工保持一致； Mapper SQL 无法被单测覆盖。
- 建议：短期：为四处写同一组真值表断言（同 snapshot 同数据 → 同结果）；长期：mapper SQL 由规则源生成。`ponytail:` 备注级——先做测试，不做框架。
- 代价：测试 1 天；生成方案另议。
- 置信度：已复核

### S3 `admin` 字面量散落 12 处（原计划记 6 处，少计）
- 证据：backend 4 处 SQL/条件：PermissionCatalogSynchronizer.java:82、WorkflowRuntimeV2.java:238、UserService.java:604,617；frontend 8 处：access.ts:24、AuthorizedHome.tsx:17、registry.ts:93、authz.ts:28、app.tsx:116、FormManagementWizard.tsx:291、Role.tsx:96、UserPermission.tsx:69。
- 影响：改角色名/引入第二超管角色时漏改风险高。
- 建议：前端先收敛到 authz.ts 单函数；后端把「builtin 管理角色」判定改为 `t_role.builtin AND data_scope='ALL'` 或专用标记。
- 代价：前端 1 小时；后端半天。
- 置信度：已复核

### S4 PermissionCodes 注释与实现相反
- 证据：PermissionCodes.java:7（"菜单可见性由能力投影得出（见 t_menu.required_permissions）"）vs MenuService.java:148（实际用 PageCapabilityRegistry 覆盖）。
- 建议：随 S1 改注释为「page-capabilities.json + PageCapabilityRegistry 是唯一真源」。
- 代价：2 行。
- 置信度：已复核

### S5 `@authz.console` 注解与 service 层 requirePermission 双重校验，无一致性对账
- 证据：FormGrantController.java:20-73（注解）+ FormGrantService.java:50,66（服务内 requirePermission 同码）；AuthorizationCoverageTest 只校验「端点声明了鉴权 + 码在目录内 + 移动端不用 consoleEntry」，不校验两层码一致。
- 影响：注解改码、服务层漏改时静默产生"能过注解、死在服务层"的 403；反之注解比服务层宽则靠服务层兜底但审计语义混乱。
- 建议：扩展 CoverageTest：反射收集 controller 注解码集合与 service requirePermission 常量集合做包含关系断言。
- 代价：0.5 天。
- 置信度：已复核

## 7. 普通用户影响分析（employee）

1. **员工能进桌面管理端**：14 项能力含 `console:entry:access`（V40:169 派生 + V41 未回收）。落点是工作台 + 审批记录（page.workplace 语义的延续），不出现其它菜单（无对应只读能力）。
2. **AuthorizedHome.tsx 的 403 页不是给员工的**：员工有入口，直接被 `firstAccessiblePath` 带走；403 页的受众是无入口/无角色账号。设计意图（"不能进入管理端，请使用手机端"）与实际受众不符——**是否属有意设计需拍板**：若员工本不该进桌面，V45 回收 entry（迁移 DELETE）；若有意开放，把 403 文案改为"该账号未开通桌面访问"。
3. **员工核心路径绕开「能力点 × 数据范围」**：填表/发起看 t_form_resource_grant（requireFormUse）；看实例走 instanceVisibility（AuthorizationService.java:331-350）三条路径，审批人靠「workflow:task:read 存在 + 我是被指派人」，inDataScope 不参与。数据范围配置对员工几乎是空转的，这解释了第 5 章 E3/E4 的配置困惑为何对管理员更痛。
4. **对普通用户的净影响**：第 3 章缺陷近乎零影响（员工不进那些页面）；正向项：403 页中文化（员工是少数真会撞上的人群）；E2 的"影响 N 人"提示是间接受益（减少误锁入口）。

## 8. 两条硬约束（不得触碰）

### 红线 1：不得收窄 `workflow:task:read` 的语义
- 证据：AuthorizationService.java:340-343（能力存在 + 被指派人 → FULL）。标签「查看本人任务」看着像 bug，但审批人正是靠它看到指派任务；改成按数据范围过滤会让审批人立刻丢待办。
- 要求：任何整改只允许改标签（N2），不许改裁决；配套回归测试：员工 B 被指派 A 的任务 → canReadFullInstance=true。

### 红线 2：不得给非可配能力补默认范围
- 证据：AuthorizationService.java:579-585（effectiveScope：覆盖值 → 目录 defaultScope → ALL 兜底）。今天 workflow:task:read 等非 scopeable 能力的范围只被当布尔用、从不消费，是惰性正确；若"修正"为 SELF/NONE，会波及 workflow:task:approve 等员工路径与所有非 scopeable 能力。
- 要求：整改时保持 `defaultScope == null → ALL` 分支；配套测试：非 scopeable 能力的 snapshot RoleGrant.scope == ALL。

## 9. 顺带发现

### F1 转交/加签：能力已授、后端已实现、前端无入口（修正原计划）
- 证据：V20:148-164 授予 user 角色 transfer/delegate/add_assignee/recall → V40 1:1 映射保留；TaskController.java:109（/transfer）、:126（/delegate）、:145（addAssignee）、:163（/recall-child）端点存在且有权限校验；前端无对应操作入口（grep 无 UI 调用）；CLAUDE.md:87-89 仍写「转交/加签」为二期未做。
- 影响：**原计划"功能不存在"的判断不成立**——真实状态是后端已实现、文档过期、前端未接线。员工持有着他们用不上的 4 个高危能力码。
- 建议：要么前端接上（二期提前），要么 V45+ 回收 4 项能力等 UI 就绪再授；无论哪条先更新 CLAUDE.md。
- 代价：回收 = 1 个小迁移；接线 = 前端页面。
- 置信度：已复核

### F2 审批人选择器包含停用/离职用户
- 证据：MobileOrgService.java:31-53 searchUsers 无 status='ACTIVE' 过滤（select 列表中无 status，WHERE 无条件）。
- 影响：停用用户出现在移动端审批人选择器，选中后任务流向死账号。
- 建议：查询加 `status = 'ACTIVE'`（selectedUsers 按 id 回显不过滤，保持历史回显）。
- 代价：2 行 + 测试。
- 置信度：已复核

### F3 同一份用户数据三扇门三种门禁，唯一带范围的恰不是挑审批人用的
- 证据：/api/mobile/users `@AuthenticatedOnly`（MobileOrgController.java:19-23）；/api/pickers/users、/users/selected `consoleEntry()`（PickerController.java:19-31,43-48）；searchUsers 本身无能力/范围校验；AuthzPolicy.java:29-31 javadoc 自述 consoleEntry 只用于「自助类但仅限桌面端的端点」。
- 影响：门禁语义与注释矛盾；移动端挑审批人靠 /api/mobile/users（无范围），桌面挑人却要入口能力（但桌面挑人不该只看入口）。
- 建议：挑人端点统一 `@authz.capability(...)` 语义或专用 pickers 能力；javadoc 与实现对齐。
- 代价：0.5 天 + CoverageTest 调整。
- 置信度：已复核

### F4 无 principal 时数据权限不注入（隐性全表）
- 证据：DataPermissionPolicyHandler.java:31-37：`scope.isEmpty()` 即 return null（不注入）。异步线程/系统任务无 principal → 命中规则表的查询变成全表。
- 影响：当前全上下文查询都从请求线程发起则无害；任何异步读（调度、outbox 重试里的查询）会静默放大。
- 建议：规则表里显式登记"允许系统上下文"的语句，其余 fail-closed（1=0）；或提供 SystemPrincipal。
- 代价：1 天含排查异步调用点。
- 置信度：已复核（机制）；影响面=推断（未逐个排查异步调用）

### F5 V43 触发器只护账号停用，不护维护人行直删；死锁推断降级
- 证据：V43:53-57 触发器仅 `BEFORE UPDATE OF status ON t_user`；维护人行的删除保护在服务层（FormGrantService.java:221 FOR UPDATE + 校验）。此前"replaceMaintainers 与触发器加锁顺序相反可能死锁"的推断未证实：触发器只 SELECT ... FOR UPDATE form 行，不写维护人行，成环条件未找到。
- 建议：保持观察；若要消除疑虑，把服务层与触发器统一为"先锁 form 再动行"并在注释写明锁序。
- 代价：0.5 天（可选）。
- 置信度：触发器范围=已复核；死锁风险=推断（低）

### F6 前端 biome 门禁实际是阻塞的（修正代理结论）
- 证据：frontend/package.json:13-16，`lint = biome:lint && tsc`，`biome:lint = biome lint`，无 `|| true`。
- 影响：此前"lint 非阻塞"的判断已过期，无需整改。
- 置信度：已复核

## 10. 建议的优先级排序（供拍板）

| 批次 | 内容 | 说明 |
| --- | --- | --- |
| P0（先做，无迁移） | D1 死键修复；D3 状态码定案 + 统一；N2/N3 标签修正；红线 1/2 回归测试补齐 | 合计约 1.5 天，全部低风险 |
| P1 | E1 能力树搜索；E2 保存确认 + 影响面端点；S5 CoverageTest 两层对账；F2 ACTIVE 过滤 | 合计约 2 天，纯增量 |
| P2（含 V45，已获批） | S1 清 t_menu 死列；S4 注释随迁；N1 拆 form:authorization:manage（新码 + 换算）；F1 员工 4 项任务能力回收（或接线，先拍板） | V45 一个迁移打包，约 1.5 天 |
| P3（结构） | S2 范围逻辑真值表测试 → 长期生成；S3 admin 收敛；F4 DPH fail-closed；F3 门禁语义统一 | 按需排期，建议单独立项 |

## 附录 A：8 个 authz 提交的审计结论（已复核）

`git rev-list --count master..HEAD`（审计时点）= 8，0 落后；主干为 master，main 本地不存在。

| 提交 | 日期 | 结论 |
| --- | --- | --- |
| 9e76add wip(authz) | 09-17 | 自带提权漏洞（A1），不可部署；可编译、不破坏 bisect，但绝不可上线 |
| dea150d fix(authz) | 09-17 | V41 + PageCapabilityRegistry；修复 A1，引入 F3 的 consoleEntry 挪用 |
| 69d84d3 chore(security) | 09-17 | 生产净改善；但开发密钥落入被跟踪的 application-local.yml（A3）；CI 门禁问题见 A4 |
| 5d13e46 chore(repo) | 09-17 | .gitattributes 漏 *.bat；未 renormalize（工作区 CRLF 警告至今可见） |
| a558471 feat(authz) | 09-18 | V42+V43 同车；V42 回填漏 form:authorization:manage（A5） |
| 4b524a3 feat(ui) | 09-18 | 表单使用/维护拆分，移动端服务端强制（正确） |
| 5373823 docs(authz) | 09-18 | 仅文档 |
| a5833eb feat(workflow) | 09-18 | scope=='mine' 与 __rework__ 互斥（A6），待产品确认 |

### A1（高，已复核）9e76add 提权漏洞
- `git log -S validateGrantCeiling`：82d7caf 引入、9e76add 消失、dea150d 恢复。
- 9e76add 的快照加载器（AuthorizationService 快照查询，约 :528-537）缺 `AND permission.admin_only = false`——非 admin 角色若持有 admin_only 能力即被快照展开为可用；叠加 ceiling 校验删除，非管理员可自我授予任意非 admin_only 能力。
- dea150d 起当前代码恢复 `admin_only = false` 过滤与 ceiling 校验。两提交是一个逻辑单元被拆成两半，bisect 时 9e76add 是"已知坏点"。

### A2（中，已复核并修正）/api/pickers 与目录暴露
- 三个事实成立：searchUsers 无 requirePermission/无 inDataScope/无 ACTIVE 过滤（F2）；keyword 为空时跳过整个 WHERE 直接 LIMIT 20（MobileOrgService.java:31-53）；consoleEntry 用于非自助端点与 AuthzPolicy javadoc 矛盾（F3）。
- 修正：/api/mobile/users 早已 `@AuthenticatedOnly` 暴露同一 searchUsers（先于本分支，移动端挑审批人依赖它），dea150d 把 pickers 收紧为 consoleEntry 是收紧而非放宽。"收紧反而放宽枚举"的说法不成立。

### A3（中，已复核）application-local.yml 被跟踪
- `git ls-files` 确认 backend/src/main/resources/application-local.yml 与 application-local-sql-debug.yml 均被跟踪；前者含 jwt.secret、audit.archive-*、minio access/secret-key、wecom encryption-key 等键（值不在此引用）。
- 公允说明：值原就在 application.yml，非新增泄露；但现位于 src/main/resources 随 jar 发布，.gitignore 未覆盖，ProductionAuditCredentialValidator 仅 prod 生效。建议：迁移到环境变量 + 本地 profile 模板文件（*.example）。

### A4（待实测）CI 占位符风险
- 已复核部分：ci.yml 的 backend job 只注入 JWT_SECRET + 3 个 PG 变量；backend/src/test/resources 下仅有 media 目录、无 application*.yml；MinioFileStorage 有 @ConditionalOnProperty。
- 推断部分：audit 归档/企微集成等占位符持有类无 @Conditional，占位符不可解析会使全上下文测试在 CI 失败；分支从未推送（无 upstream），风险是"首次推送才暴露"。
- 处置：不改代码，首次推送前本地以 CI 同等 env 跑一次 `mvn -B test` 即可证实或证伪。

### A5（中，已复核）V42 回填漏 form:authorization:manage
- V42:47-63 的 IN 列表仅 6 个 definition/workflow 模板能力码，不含 form:authorization:manage；持有该能力的角色在 V42 后的维护人回填中一无所获（之后仅创建人回填 + 管理员兜底补位，V42:67-105）。
- 待确认：是有意收窄（则该能力在回填语境下死码）还是漏项。结合 N1（本就该拆码），建议在 V45 拆码时一并给出结论。

### A6（中，已复核）scope='mine' 与 __rework__ 互斥
- ProcessInstanceMapper.java:88-93：scope=='mine' 追加 `AND pi.current_node_id IS DISTINCT FROM '__rework__'`；:98-101：status=='REWORK' 要求 `current_node_id = '__rework__'`。两者同时命中即空集；Sent.tsx:14 恒传 scope:'mine'。
- 结果：申请人在「已提交」列表永远看不到自己被打回待改的单（RecordList 走 scope=authorized 不受影响）。是否有意需产品确认；若非有意，去掉 mine 分支的 DISTINCT 条件即可。

### A7（低，已复核）仓库根散件
- `Engineering discipline.md`（99 行通用中文方法论，与 docs/ 惯例冲突）、`agent.md`（2026-07-31 移动端任务上下文，已声明保留为历史）与 `AGENTS.md`（项目协作说明）并存，另根目录还有 codex.md、backend-run.log、frontend-run.log、frontend-run.err 等运行残留。
- 建议：方法论并入 docs/ 或删除；log/err 入 .gitignore；agent.md/codex.md 归档到 docs/archive/。

## 附录 B：本轮复核对原计划的修正记录

1. **D2 降级**：原判断「菜单放行、页面拒绝自相矛盾」→ 实为冗余双门禁。`security:user_role:read` 是 admin_only（V40:420）且 `RoleAdminService.java:313` 禁止授予普通角色，当前无实际放行缺口；风险是未来政策松动后的双源分裂。
2. **F1 翻转**：原判断「转交/加签能力已授、功能不存在」→ TaskController 四个端点均已实现且有权限校验；真实缺口是前端无入口 + CLAUDE.md 过期。
3. **F6 纠错**：原判断「biome lint || true 非阻塞」→ 当前 package.json 无 `|| true`，lint 为阻塞门禁。
4. **行号校准**：Contacts.tsx 与 UserPermission.tsx 实际路径在 pages/org/ 与 pages/security/（原计划路径缺一级目录）；MenuService 精确行号 113/126-141/148；员工能力链由 4 个迁移逐行闭环（第 2.2 节）。
5. **置信度升级**：原计划中标注"代理探查"的 DataPermissionPolicyHandler、V43 触发器范围、biome 三项均已升级为已复核（F4/F5/F6）；A4 维持待实测，A6 维持待产品确认。

## 附录 C：与本次交付相关的仓库注意事项

- `frontend/src/services/ant-design-pro/` 是 `npm run openapi` 的生成目录（frontend/CLAUDE.md 禁改）。本次 auth.ts 迁移有意手改了该目录下 api.ts（删除生成的 outLogin），并同步删除 oneapi.json 中的接口定义防止再生成时回退——已在提交 5c710c4 的 message 中注明。后续整改若涉及登出接口，改 `frontend/src/services/auth.ts`，不要改生成目录。
- Flyway 前滚约束：V40-V44 已冻结，S1/N1/F1 等涉及 schema 的整改一律走新迁移（V45 已获批）。
- 本报告交付前已完成：4 个主题提交（5c710c4 logout/CSRF、3f19f95 SSE、46acf47 account 重定向、623102c V44 共享选项数据源），后端 433 / 前端 209 / 移动端 324 个测试全绿，frontend tsc 通过。
