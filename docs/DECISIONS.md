# 架构决策

## D-20260917-authz-layers

- **状态：** accepted
- **背景：** 仅检查能力码无法表达逐表授权和部门数据边界。
- **决策：** 后端按端点能力、资源授权、数据范围三层校验；受控通用查询默认拦截，自助查询显式豁免。
- **影响：** 列表与详情必须保持相同谓词，前端隐藏不作为授权依据。

## D-20260917-flyway-forward-only

- **状态：** accepted
- **背景：** V40 已在现存数据库执行，即使提交前仍不能安全改写。
- **决策：** V40 冻结；权限修正和菜单版本从 V41 前滚，禁止 `repair` 掩盖校验和变化。
- **影响：** 发布前同时验证全新库和存量升级路径。

## D-20260917-menu-authority

- **状态：** accepted
- **背景：** 浏览器提交的页面能力映射不可作为服务端可信输入。
- **决策：** 页面能力使用仓库内共享 JSON，后端读取并过滤导航；菜单 API 只保存结构和展示属性。
- **影响：** 修改页面能力需同时通过后端与前端清单测试。

## D-20260918-form-usage-maintenance-separation

- **状态：** accepted
- **背景：** 把表单使用授权复用于模板管理和业务数据读取，会造成普通使用者被错误升级，也会让跨部门维护受创建人部门限制。
- **决策：** `t_form_resource_grant` 只表示表单使用范围；`t_form_maintainer` 只表示模板维护成员。非管理员维护模板时必须同时满足维护成员关系与对应原子能力；数据和实例读取只按各自能力及数据范围判断。
- **影响：** 新表单创建人自动成为维护人；维护人委派要求 `form:authorization:manage`，停用最后一名有效维护人由 V43 数据库触发器拒绝；模板能力取消部门范围配置，数据类能力范围不变；手机目录、详情、收藏和发起使用同一使用授权谓词。

## D-20260921-shared-option-sources

- **状态：** accepted
- **背景：** 大量下拉选项需要从文本或表格复用，同时必须支持跨字段联动、移动端分步筛选和历史表单稳定回放。
- **决策：** 导入内容发布为不可变数据版本，表单只绑定确定版本；管理端引用同时校验表单维护权和数据源用户/角色授权，运行时查询复用表单、实例或填报记录的既有授权上下文。
- **影响：** 重新导入不会改变已发布表单；停用数据源阻止新绑定但不破坏历史版本；服务端在发布、查询和提交时校验列映射、联动链及选项值。

## D-20260921-hidden-vs-out-of-scope-status

- **状态：** accepted
- **背景：** 同一授权状态若因入口不同返回不同状态码，前端无法用状态码区分"资源不存在"与"越范围"，审计里 404/403 的语义也会被稀释。
- **决策：** 统一为「范围内可读资源的管理越权 = 403；任何不可读 = 404」。不可读一律抛 `HiddenResourceException`（映射 404 并写 `security.resource.hidden` 审计），已可读但超出数据范围抛 `AccessDeniedException`（403）。
- **影响：** `AuthorizationService.requireManageTask` 委托 `requireManageInstance`，任务入口与实例入口天然一致（不存在"同情形两种状态码"）；前者内部的 `canReadInstance` 前置判断与后者重复但无害。新增管理与读取入口必须沿用同一规则。

## D-20260922-bi-ledger-readonly-views

- **状态：** accepted
- **背景：** 第三方 BI 要读表单数据形成台账，但表单值存在 `t_form_data.data`（JSONB，键是字段 nanoid、无类型），且同一列的含义随 schema 演化。BI 直接读基表写不出可读 SQL，也算不出业务口径。
- **决策：** BI 直连数据库，但**只授只读视图**（V45 的 4 个字典视图 + `v_form_ledger` 长表），不授任何基表；授权脚本 `infra/sql/bi-readonly-role.sql` 刻意不进迁移（建角色需要 elevated 权限，密码不进 git）。台账含审批人与审批结果（join `t_process_instance`），软删表单**保留行**并暴露 `form_deleted` 供 BI 自行筛选，不静默丢弃。
- **影响：**
  - **这是有意接受的鉴权旁路：** BI 直连库读取**绕过**应用的三层鉴权（端点能力 / 表单使用授权 / 数据范围）。任何"把视图授权换成基表授权"的改动都要重新评审。
  - **`t_form_definition_version` 只增不删（不变量）。** 台账的历史列名依赖它解析；一旦有人加"清理旧版本"的任务，历史报表的列名会静默改变。
  - **BI 关联一律用 `form_def_id`**：`t_form_definition.code` 有部分唯一索引（`deleted = 0`），软删后编码可复用，`form_code` 只能用于展示。
- 视图是纯计算视图，每次查询都重算。量级上来后升级路径是物化视图 + 定时 `REFRESH`，或改走应用侧台账接口。
  - V48 起人员字典不再暴露登录工号，自由文本/附件/复杂字段的台账值固定返回 `NULL`；选项、数值、日期等结构化值继续可用。

## D-20260923-option-source-reversible-state

- **状态：** accepted
- **背景：** 选项数据源的"发布"原本是单向的（`PUBLISHED` 是终态，源和版本都只增不减），于是"导入错了、发布错了"只能靠停用占位、越堆越多。放宽删除门槛会引入竞态（删除检查与表单发布之间），而且被表单版本快照引用过的源本来就不能删。
- **决策：** **不靠放宽删除，靠状态可逆**——补上 启用（DISABLED→ACTIVE）、取消发布（PUBLISHED→DRAFT，退回待发布）、丢弃待发布版本（连同行数据）。删除门槛保持"从未发布 + 无人引用"不变。
- **影响：**
  - **撤引用 ≠ 解绑。** `t_form_option_source`（源侧清单）只决定源能不能出现在设计器候选里；撤掉它**不会**解绑字段、也**不会**让源变得可删。删除判定只认 schema 扫描（`bindingFormCount`，覆盖草稿 + 所有版本快照，含已软删表单的快照）。破坏这条会让管理员在源侧点一下就把别人的表单改坏。
  - **停用只挡"新绑定 + 设计器预览"**，已发布表单的填报与读取照常——因为填报值校验走 `requireVersion(source, false)`，只有设计期的 `forBinding=true` 才要求源 ACTIVE。改动这个分叉会静默地把已发布表单变成提交失败。
  - **取消发布 / 删除的唯一硬门槛是"没有表单版本快照引用"**（版本级、表单级各一条查询）。一退一删，那张表单打开下拉会立刻 422、字段提交不了；而版本快照只增不删，所以被引用过的版本永远退不回来。
  - **读写状态分叉**：发布要求源 ACTIVE（"开始用"），取消发布/丢弃只要求源存在（"不再用"）。加新的状态操作时保持这个方向。

## D-20260923-option-source-version-retire

- **状态：** accepted
- **背景：** 「取消发布」需要"没人钉着该版本"才放行，因为一旦退回待发布，钉着它的表单读候选项会立刻 422。但"这一版有问题、先别让新的表单选它，在用的先撑着"是另一个真实需求，取消发布满足不了它。用户也把这个需求叫"停用"。
- **决策：** 拆成两个动作，语义靠**是否改变版本状态**区分：
  - **版本停用/启用**（V47 的 `disabled_at`）：从"新绑定"候选里移除，状态**仍是 PUBLISHED** → 读路径完全不受影响 → **无门槛**。
  - **取消发布**：退回待发布 → 钉着它的表单 422 → **必须有"没人钉着"这道闸**；被拒时的信息要指向「停用该版本」。
- **影响：**
  - **`requireVersion` 的读与写都要求 `status='PUBLISHED'`**，只有 `forBinding=true` 才额外要求源 ACTIVE。**任何"让读路径接受非 PUBLISHED 版本"的改动都会把取消发布变成破坏性操作**，别做——那是把"下架"和"拆除"合成一个动作，代价是重新导入会覆盖掉别人正在用的版本（`importDraft` 会 `DELETE ... status='DRAFT'`）。
  - **「最新」= 最高的"已发布且未停用"版本**（`list()` 与 `bindable()` 两处 LATERAL 都要跟着改）。全部下架时 `publishedVersionNo` 为 null，用 `anyPublishedVersion` 区分「从没发布过」（未发布）和「发布过但都下架了」（无可用版本）。
  - **已停用的版本仍要出现在"正钉着它"的表单候选里**（`boundVersionIds` 从表单草稿 schema 解析），否则版本下拉会显示空白、管理员以为绑定丢了。这和源的"已绑定兜底"是同一条规则。
  - 「**引用**」（源侧可撤销的清单）与「**在用**」（表单侧钉死的绑定）是两件事：撤引用不解绑，也不影响删除判定。

## D-20260924-v40-legacy-permission-conversion

- **状态：** accepted（**记录现状，不是设计意图**）
- **背景：** V40 把旧的能力码换算成新能力模型时，有三处"信息在换算里丢了"，且因为 V40 已冻结、只前滚，都不能原地改。补记在这里，避免后来者按现在的模型反推、以为历史上一直是这么紧的。
- **决策：** 三条都**不改权限数据、不改 V40**，只记录 + 用测试钉住现状（`PostgresTransactionalIntegrityIntegrationTest` 里三条 `v40*` 用例）。
  1. **`form:authorization:manage` 丢掉旧的 `t_role.data_scope`。** 该能力在 V40:47 声明为 `scopeable=false`，而换算语句（V40:145-152）只在 `scopeable` 为真时才写 `scope_override`，于是旧的部门范围被丢成 NULL。行为上等于"谁管表单授权，谁就能管全部表单的授权"。
  2. **`create` 与 `design` 收敛成同一个写能力。** 旧码 `form.definition.create` 与 `form.definition.design` 都映射到 `form:definition:manage`（V40:117-119），粒度没了：只被授过"创建"的角色换算后同样能改已有表单的设计。
  3. **内置角色改名没有冲突保护。** `UPDATE t_role SET code = 'employee' WHERE code = 'user'`（V40:198）是裸更新，`t_role.code` 唯一；如果库里已经有人建了 `employee` 角色，整个 V40 回滚。全新库的种子顺序保证不会撞，但"手动建角色"的路径能触发（测试用一条断言把"会回滚"这件事钉住了）。
- **影响：** 要真正修这三条只能**前滚一条对账迁移**（补范围、补粒度、加冲突保护），那属于改权限数据，得单独开一轮并配一次权限对账。在那之前，读 `t_role_permission` 时不要把"没有 scope_override"当成"刻意配成默认范围"。

## D-20260924-picker-list-narrow-dto

- **状态：** accepted
- **背景：** 运行时选择器的两个列表端点（移动 `/api/mobile/users`、桌面 `/api/pickers/users`）是唯一能用关键字翻页枚举的入口，而它们只要求"登录"或"能进管理端"；此前会把 `username`（登录账号）一起下发，等于给任何登录用户提供了账号字典。按 id 取单人的端点不可枚举，不受影响。
- **决策：** 列表端点改用窄 DTO `RuntimePickerUserDto(id, displayName, department, employeeNo)` —— **不下发登录账号，保留工号**。人员详情（`proc/Detail` 的"某某 · 工号"）与移动端"已选"回显走按 id 的端点，仍是完整 DTO；管理端要全量字段继续走 `/api/users`（已要求 `org:user:read`）。
- **影响：**
  - "列表里不再有账号"是**编译期**保证：窄 record 上没有 `username` 字段，加回来必须先改类型。
  - 工号**有意保留**：前端就靠它做同名消歧，而它自己的注释说明过"缺工号时不拿账号冒充"——账号比工号敏感。这与 BI 那条"人员字典不暴露登录工号"不冲突：那条针对的是绕过三层鉴权的直连库场景。
  - 桌面 `UserPickerField` 的候选标签不再有 `username` 可退，缺显示名时回落到 `#id`。

## D-20260928-hidden-field-is-not-a-secrecy-boundary

- **状态：** accepted（**记录现状**）
- **背景：** 节点级字段权限（`props.formPerms` 的 `HIDDEN`）看起来像"这个字段对某些人不该出现"，容易被当成保密手段。实际上它只在**渲染层**过滤：`HIDDEN` 的字段不出现在表单里、选项接口也按调用者节点收窄（`OptionRuntimeService.requireViewerVisible`），但**实例详情的 `formData` 里仍带着被隐藏字段的原始值**——只要那个人有实例读权限。
- **决策：** 保持现状，**不把 `HIDDEN` 当保密边界**。要真正保密（例如薪资、身份证号），用**独立的表单使用授权**（`t_form_resource_grant`）把整张表单的可见范围收窄，或者干脆拆到另一张受限表单里。
- **影响：**
  - 别在 UI 上把 `HIDDEN` 描述成"保密/权限"；它是**填写体验**的开关（别让人填无关字段、别让流程节点看到不该改的东西）。
  - 选项接口那一层是"防越过界拿数据"的收窄，不等于详情接口也收窄——**改动详情接口的返回字段时不要以为 `HIDDEN` 已经兜住了**。真要收敛就得改详情响应（那是接口契约变更，得单独评审）。
  - 隐藏字段的**值**随实例快照永久留存（含版本快照），删除表单定义也不会让历史实例丢字段。

## D-20260928-option-source-pinned-version-only

- **状态：** accepted
- **背景：** 参考了一份「数据源版本化」的界面原型，它比 AntFlow 多出两件事：字段可以选「跟随最新版本」（实例启动时解析最新版），以及删除数据源时可以「强制删除」或「把引用迁移到另一个源再删」。看着都比现在灵活。
- **决策：** **都不做**，保持「表单钉死它绑定的 `versionId`、版本快照只增不删」。想要新版的效果改为**显式升级 + 重新发布**：设计器的绑定面板会在钉的版本落后时提示「数据源已经有 vN」，用户在那里选新版本、再重新发布那张表单。删除仍然只有一道门（从未发布 + 无人引用），其余情况一律走「停用」。
- **影响：**
  - **「跟随最新」会同时打穿三处设计**：读路径要求版本 PUBLISHED 且源 ACTIVE（取消发布/停用正是靠这一点才安全）、实例级版本记录（`current_form_revision_id` 那套）要扩到选项版本、以及"同一张单子的不同实例选项不一致"这个新解释成本。
  - **「强制删除」与「版本快照只增不删」直接冲突**：历史记录的下拉回显全靠快照里的 `versionId` 还能解出选项；删掉源或版本，历史单据就变成一片空值。原型自己也提示"历史回显将丢失"——AntFlow 明确不接受。
  - **「迁移引用」是跨表单的批量改写**：它要修改别人表单的 schema（还有各自的授权与发布状态），远超一个数据源页面的职责。
  - 界面文案必须跟着这条口径走：说「固定版本」「重新发布才能用上新数据」，不要说「自动跟随最新」。

## D-20260929-export-follows-read-scope

- **状态：** accepted
- **背景：** 新增「数据导出」时发现：行级数据范围是 `DataPermissionPolicyHandler` 按 **mapper 语句 id** 显式开启的，而 `t_form_data` 那条规则绑的是 `form:data:read` 的范围。于是"只有 `form:data:export` 权限"的角色会被注入恒假条件——**导出一个空文件**，看起来像"这段时间没数据"。
- **决策：** 导出与导出预览（`/api/forms/data/admin/export`、`/api/reports/form-data-count`）**同时要求 `form:data:read`**，缺了就直接 403 说清缺什么。口径一句话：**导出的范围 = 你能看到的范围**。
- **影响：**
  - 别为了"让只有导出权限的角色也能导"去放宽成只查导出权限——那会回到"空文件"或"越权导出"二选一。要么补读权限，要么给导出能力单独加一条范围规则（那需要新建一个专属 mapper 语句，不能复用列表那条）。
  - 导出必须走 `FormDataMapper.selectList`（`exportFilter` 与台账列表共用一份过滤）；自己写 count/裸 SQL 都吃不到这层收窄——预览行数会与实际导出行数不一致，那本身就是越权线索。
  - 导出动作写 HIGH 风险审计，带上 `rowCount` / `truncated` / `limit`；上限 10000 行。

## D-20260930-contacts-dept-name-search-covers-subtree

- **状态：** accepted
- **背景：** 通讯录搜索框改成"双用"（同一个词既过滤左树、又跨部门搜人）之后，你报了一个现象：输入「技术部」时左树筛出了部门，**右栏却一个人都没有**。原因是后端关键字只匹配 `username/display_name/employee_no`，而"匹配部门名"这件事当时只存在于移动端选择器一条路径里（还是在 Java 里先查部门 id 再 `IN`，只命中同级、结果集还可能很大）。
- **决策：** 「关键字命中部门名 → 该部门**及全部下级**的成员」统一成**一条 ltree 子查询谓词**（`DepartmentMapper.applyDeptNameMatch`），桌面台账搜索与移动选择器共用。谓词仍然 OR 在原有的 `and(...)` 分组**内部**，所以后面的数据范围收窄照旧 AND 在外面。
- **影响：**
  - 写在 `DepartmentMapper` 上是因为这是 ltree 查询知识（和 `subtreeIds` 同一族）；写成 **static** 而不是 default 方法，是因为 mapper 的 default 方法会被 mock 掉——单测就再也看不到拼出来的 SQL 了（本轮踩过：`MobileOrgServiceTest` 立刻变红）。
  - 判定"含下级"靠 `child.path <@ hit.path`。**别退回"先查部门 id 列表再 IN"**：既要展开子树（N 次查询），又会拼出超长参数列表，而且两个入口会再次各写一份。
  - 左树过滤同步改成"命中节点保留**整棵子树**"（`filterDepartmentTree`）：命中后只留也命中的子节点，会出现"搜到技术部、点进去看不到后端组"，与右栏的结果对不上。
  - 关键词语义现在包含"部门名"，即搜「研发」会返回研发部及其下级的所有人。这是**有意的**：用户搜的就是"这个部门的人"。

## D-20260930-ledger-explains-each-record-by-its-own-version

- **状态：** accepted
- **背景：** 台账（`/api/forms/data/admin`）的字段标签、下拉选项、检查项字典全部取**当前**表单定义，忽略每条记录的 `formDefVersion`：表单改过标签/删过选项之后，旧提交会显示新标签，外链下拉甚至显示成 `option_1`。显示规则当时也只活在前端（`fieldValues.ts`），于是同一个值在页面和导出里是两个样子，前端还要为一次可选的定义请求处理 403。
- **决策：** 显示规则搬到后端（`form/runtime/FormValueDisplay`），并**按每条记录自己的版本**解析 schema，解析顺序与 `OptionRuntimeService.schema()` 一字不差：实例当前修订版 → `(form_def_id, form_def_version)` 快照 → 当前定义。`FieldValue` 增加 `displayText`/`detailText`，保留 `fieldId`/`fieldName`/`value`。
- **影响：**
  - **分辨率随记录走，不是随表单走**：同一页里混着两个版本时各按各的来。所以缓存/分组的键是**解析出来的 schema 文本**，不是 `formDefId`。
  - **外链下拉的选项名要查库**：schema 里只有 `(sourceId, versionId, valueColumn, labelColumn)`，没有选项行。按 `(versionId, valueColumn, labelColumn)` 用**本批真的被选过的值**批量查（一组一条 SQL，最多 500 个值）——整版扫一遍在 2 万行的源上等于每次翻页都白读 2 万行。查不到的值回落**原始值**（表单删了选项，旧单据仍要看得见当时选的是什么）。
  - **空值按原始 value 判断**，不能拿"未填写"这类摘要文案当哨兵：选项真叫「未填写」的字段会被抹成空白（前端老实现的洞，本轮修掉）。
  - 前端删掉本地那套规则与那个可选的定义请求；页面只渲染 `displayText`/`detailText`。**页面与导出从此共用一份实现**——改显示口径只改 `FormValueDisplay` 一处。

## D-20260930-export-format-and-column-identity

- **状态：** accepted
- **背景：** 导出第一版只吐原始值（`option_1`、`id=item-1; …`），列名在没有 label 时退化成字段 id；而且只有 CSV。你要求"字段名称用中文、字段内容是表单真实显示的内容"，并且能选 CSV 或 Excel。
- **决策：** `FormDataCsv` → `FormDataExport`，产出统一的 `Model(headers, rows)`（CSV 与 Excel 共用），接口加 `format=csv|xlsx`（**默认 xlsx**）。三条硬规则：**列身份是 `fieldId`**（标签只作表头）、**值取 `detailText`**（换行压成 `；`）、**导出默认给 Excel**。
- **影响：**
  - **列不能按标签当身份**：两个不同字段可以叫同一个标签（表单改过版本就是），按标签建列会互相覆盖、同一字段跨版本还会拆成两列。同名时表头会出现两列相同的文字——这是**有意**的（值各归各位），不是 bug。
  - **公式注入防护是必须的**：`=` `+` `-` `@`（含前导空白之后）开头的值会被 Excel 当公式执行，而字段值、姓名、部门名都能进 CSV。CSV 统一前置单引号当文本；xlsx 走全 STRING 单元格（顺带保住工号的前导零），单元格截断到 32767 字符。
  - xlsx 用 **SXSSF** 流式写并 `dispose()`：1 万行的 XSSF 能把堆吃光（部署里后端只有 768M）。列宽按内容算，上限 60 字符。
  - **预览计数必须与下载同参数**（含 `from`/`to`/`tzOffsetMinutes`）：少一个时间范围，"预览 300 行、实际 30 行"就出现了，`truncated` 还会误报。这条是上一轮发出去的 bug，本轮一并修掉。

## D-20260930-media-watermark-needs-ffmpeg-and-says-so

- **状态：** accepted
- **背景：** 「5S检查表」的 `image_upload` 字段开了水印，上传图片报 422，消息是 `cannot start ffmpeg: Cannot run program "ffmpeg": error=2, No such file or directory`。真因是**部署镜像从来没装 ffmpeg**（`postgres:17-bookworm` + 拷进去的 JRE，没有任何 apt 包，连 `/usr/share/fonts` 都没有）；而图片水印是在**请求内同步**跑 ffmpeg（`MobileFileService.upload` → `applyImageWatermark`），缺二进制就让整个上传失败，`GlobalExceptionHandler` 把 `BizException` 一律映射成 422。视频是异步的，只会把行标成 FAILED，所以症状只在图片上出现。
- **决策：** ①Dockerfile 里装 `ffmpeg` + `fonts-wqy-microhei`（字体路径本来就在 `CJK_FONT_CANDIDATES` 里，装完不用配置）；②`MediaWatermarkProcessor` 加 `@PostConstruct` 启动探测，ffmpeg 不可用或没有中文字体时写 WARN，正常写 INFO；③用户可见的失败文案换成可操作的（缺 ffmpeg / 处理失败 / 超时 三种），原始报错只进日志与 `processingError`。**不做静默降级**：上传仍然会失败，但用户知道为什么、去找谁。
- **影响：**
  - 镜像是 +100~150MB（ffmpeg 那一层）。这是"开了水印的字段能真的加上水印"的价格，写进了 Dockerfile 注释与提交说明。
  - `-version` 探测与 `run()` 都**不能先读管道再 waitFor**：ffmpeg 输出超过管道缓冲就填满卡住，本来能完成的转码被误判成超时（探测则会把启动挂住）。现在统一"输出重定向到文件/DISCARD，再 `waitFor(超时)`，失败时读文件末尾当诊断"。
  - `watermark=true` 但 `watermarkText` 为空以前是**静默存原图**（调用方以为加了水印）——现在显式 422。客户端（桌面/移动）都必须同时带上非空文案，默认 `AntFlow`。
  - 图片内容按**魔数**校验（jpeg/png/gif/webp/bmp）：声明成 `image/*` 的任意字节以前会一路走进 ffmpeg，最后甩给用户一句看不懂的 ffmpeg 报错。只校验"是不是图片"，不校验"声明的子类型与字节一致"——安卓/微信图库经常把 jpeg 标成 png。
  - CI 也装了 ffmpeg：`MediaWatermarkProcessorTest` 里两条真机用例是 `assumeTrue(ffmpegAvailable())`，不装就永远静默跳过。
  - 启动探测是**日志**不是健康检查：没装 ffmpeg 时其它功能都正常，不该让 `/actuator/health` 变 DOWN。

## D-20260930-desktop-media-uploads-go-through-the-mobile-file-api

- **状态：** accepted
- **背景：** 桌面端（`frontend/src/components/form-fields/`）的图片、视频、附件、检查项照片四个上传控件**从来没发过请求**——`beforeUpload={() => false}` 让 antd 只把文件留在浏览器里，值里写的是 antd 的 `UploadFile`（没有 `id`/`contentType`）。而 `Fill.tsx` 的 `collectFileRefs` 只认带这两个字符串字段的服务端 DTO，于是用户选了文件、提交成功，**附件静默丢失**（检查项照片那次连 `onChange` 都没有）。只有桌面音频是真的在上传。
- **决策：** 四个控件统一接 `MediaUploadControl`（`customRequest` 真上传）→ 走**移动端同一个** `POST /api/mobile/files`（同一个 `MinioFileStorage` 桶、同一套 `t_mobile_file` 行与授权），值写成服务端 DTO 数组。只读态用新抽出的 `MediaPreview`（鉴权 blob → objectURL）显示缩略图/播放器/下载。
- **影响：**
  - **值里只放 READY 的 DTO**：服务端 `MobileFileLinkService.normalized` 只接受 READY 文件，拿 PROCESSING 的 DTO 去提交会被整单拒（`FILE_NOT_FOUND`）。所以"上传中/视频加水印处理中"的项只留在组件本地状态，进表单值的必然 READY；提交前还会再查一次（草稿恢复的 DTO 可能是 PROCESSING/FAILED）。
  - **值形状就是契约**：`collectFileRefs` 认 `字符串 id + 字符串 contentType`。测试里专门断言这一点——只要有人改了写值的形状，附件会被静默丢掉而页面毫无提示。
  - `fileList` 全受控、**不接 `onChange`**：antd 在 uploading/done/error 每次流转都会触发它，接上就等于把 raw UploadFile 灌回表单（就是原来那个 bug）。数量与大小在 `beforeUpload` 挡，不靠 antd 的 `maxCount` trim（受控列表被 trim 会静默丢掉已上传的项）。
  - 「要水印但没文案」在服务端已经会拒绝，桌面端因此必须把 `props.watermarkText`（默认 `AntFlow`）随请求带上。
  - 处理中的视频**不给删**：服务端后台任务正在往同一个 key 写结果，删除会留下读不出来的孤儿对象（服务端那层协调要单独修，见 HANDOFF 已知遗留）。
  - 老数据（修复前写进表单的本地 File 记录、更早的单文件名字串）没有服务端 id，只显示名字并标注"历史记录，未上传"——它们的字节从来没上传过，没有任何东西可迁移。

## D-20260930-video-watermark-independent-key-and-token-lease

- **状态：** accepted
- **背景：** 视频水印是异步的（有界队列 + 行状态 PROCESSING→READY），原实现把转码结果**写回原 storage_key**，且只靠 `processing_claimed_at` 一个时间戳防重入。两个坑都能真的炸：① put 成功但 DB 更新失败/进程崩溃时，行还是 PROCESSING 而对象已是成品 mp4，reaper 再捞一次就是**二次水印**，出错时连原视频都没了；② 排队等待 + ffmpeg 最长 10 分钟 + MinIO 上下行都可能超过 stale 窗口，reaper 会重复领取同一行，两个任务同时写、旧任务的 `updateById` 还会覆盖新任务的 READY/FAILED。
- **决策：** ①**结果写本次尝试独有的 key**（原 key + `.wm-` + token），成功后用**带 token 的条件 UPDATE** 把行的 `storage_key`/`content_type`/`size_bytes`/`sha256`/`original_name` 一起改掉并置 READY，再清理旧对象；条件更新影响 0 行（租约过期/被抢/行已删）就删掉自己刚写的 attempt 对象、**绝不碰那一行**。②**真租约**：`processing_claim_token`（V51）+ `processing_claimed_at`，处理期间在下载后/ffmpeg 前/ffmpeg 后各续一次，`renew`/`release`/终态都按 token 条件更新——丢租约的 worker 不许发布结果。③`reapStaleProcessing()` 有界（默认 20），只领"未领取或已过期"（`processing_claimed_at IS NULL OR < staleBefore`），且**队列满时上传路径标 FAILED / 恢复路径只还租约**。
- **影响：**
  - `contentUrl` 按 id 生成，所以 key 变化对客户端**不可见**（前端不用动）。
  - **`processing_claim_token`/`processing_claimed_at` 不加进 `MobileFile` 实体**：实体带上它们，任何一次 `updateById`（比如 `delete`）都会把 token 回写/覆盖。这是"字段存在但实体不知道"的刻意设计，别顺手补上。
  - `claimStaleProcessing` 的条件必须是 `IS NULL OR < staleBefore`：迁移前遗留的行 `processing_claimed_at` 是 NULL，只写 `<` 会永远捞不到——那正是最该被恢复的一批。
  - 队列满时两条路语义不同：上传路径要立刻给客户端答案（客户端在轮询），恢复路径只还租约保持 PROCESSING（下一轮再捞，避免"领了就被拒、拒了就释放、下轮再领同一条"的活锁）。
  - `catch (Throwable)` 里写 FAILED 之后**把 Error 重抛**：`OutOfMemoryError` 不能吞（吞了线程池还会带着坏状态继续跑），但行也不能永远停在 PROCESSING。

## D-20260930-delete-keeps-the-row-lock-and-deletes-the-object-after-commit

- **状态：** accepted
- **背景：** `delete` 原来是"`selectById` 查一下 → `countLinks` → 标 DELETED → 在事务里删 MinIO 对象"。三个问题：① 不加锁，"检查有没有被提交"会和提交方"检查文件是否 READY"交错，结果是**提交成功但附件指向已删文件、对象也没了**（永久坏引用）；② 对象的删除在事务提交前，一旦回滚就变成"READY 行 + 对象已丢"；③ 存储 IO 期间一直占着行。
- **决策：** ①先按 id 加 `FOR UPDATE`；②`PROCESSING` 直接拒绝（「视频正在加水印，处理完再删」）；③对象的删除挪到 `TransactionSynchronization.afterCommit`，删失败只记日志。
- **影响：**
  - 反过来（提交成功、删对象失败）最多是个**没人引用的孤儿对象**，不会让用户读到坏数据——这是刻意选的方向。
  - 行锁现在只覆盖 DB 那几步，不再被存储 IO 长时间占住。
  - 桌面端 UI 上轮已经挡了"处理中不给删"，服务端现在补齐（不信任客户端）。

## D-20260930-form-data-file-is-keyed-by-file-id-not-field

- **状态：** accepted
- **背景：** `t_form_data_file` 主键是 `(form_data_id, file_id)`，但 `normalized` 原来按 `fileId + fieldId` 去重，并且每条 ref 单独 `selectById` 一次（无锁）。上传端的 SHA 去重会把两个字段里的同一张照片收敛成同一个 file_id —— 于是两次插入落到同一主键，**整单 500**，而且查文件是否 READY 与并发删除之间没有任何保护。
- **决策：** ①按 `fileId` 去重（`LinkedHashMap` 保首见 fieldId），②对去重后的 id **升序批量** `FOR UPDATE` 一次（一条 SQL，固定锁序避免与并发 delete 形成 ABBA 死锁），锁内复核 READY/`deleted_at`/owner；③`insertFileLink` 冲突改 `ON CONFLICT DO UPDATE SET field_id = EXCLUDED.field_id, sort_order = EXCLUDED.sort_order`；④`MobileFileLinkService` 类上加 `@Transactional`；⑤`requireReadable` 给 admin/owner 提前 return。
- **影响：**
  - **`DO NOTHING` 是错的**：`reconcileEditable` 先插"受限字段"的旧链接、再插"可编辑字段"的新链接，后者才是权威分类；`DO NOTHING` 会把这次字段迁移静默吞掉（用户以为改了分类，实际没改）。
  - **`@Transactional(REQUIRED)` 不能省**：`FOR UPDATE` 只有在同一个事务里才有效，自动提交下语句结束就放锁。现有调用方（`MobileWorkflowService.start`、`FormDataService.submit/resubmit`）都在事务里所以线上没炸，但这是"靠调用方记得开事务"的隐形契约——写这条守护用例时就当场抓到了这个洞。
  - `admin/owner` 提前 return 不影响鉴权口径（本来也要放行），但一页 20 张图会少掉几十次"逐关联实例判权"的查询。
  - `Cache-Control` **保持 `no-store`**：草稿里曾想改成 `private, max-age=3600, immutable` 换性能，撤回——附件 URL 受登录态与**可变**授权保护，权限撤销/取消关联/删除后浏览器不该还能直接复用响应。剩下的 N+1 当已知成本记账。

## D-20260930-upload-dedupe-locks-the-row-and-the-size-cap-counts-real-bytes

- **状态：** accepted
- **背景：** 上传去重分支先 `selectOne`（owner + sha256 + READY + 未删，**不加锁**）拿到已有行，然后**重写那个 key 上的对象**并把旧行返回给客户端。而 `delete` 自上一轮起是"先 `FOR UPDATE` 拿行锁 → 标 DELETED → `afterCommit` 删对象"。两者交错时：客户端拿到一个已删文件的 DTO（之后提交会被 `append` 以 `FILE_NOT_FOUND` 拒掉整单），对象还会被重新写回去变成孤儿。另一处：大小上限只看 `MultipartFile.getSize()`（客户端声明），`stage()` 数出的真实字节数只流向 `row.setSizeBytes` 与 `storage.put` 的 size，没有人再比一次 —— 声明小、实际大就能绕过去，代价落在磁盘。
- **决策：** ①`findReadyDuplicate` 加 `FOR UPDATE`；②`FileStorage` 新增 `exists(String)`，**对象在就只返回旧行、不再重写**，不在才补写；③落盘后按同一口径（抽出的 `sizeLimit`）再比一次真实字节数。
- **影响：**
  - **不需要"锁后复核"分支**：PG 在 READ COMMITTED 下锁等待结束会重新求值谓词，并发删除已提交的行不再满足 `status='READY'` → 查询直接返回 null → 走已有的"新建一行（新 id + 新 key）"路径。写这条守护用例时也是靠"判最终结果"而不是"判有没有被挡住"（子表外键会把 insert 也挡住，只看阻塞区分不出修复前后）。
  - **`exists` 故意不抛受检异常**：调用方必须能把"对象不在"（补写）和"存储查不动"（整个请求失败）分开。如果让它抛 `IOException`，会被 `upload` 外层的 `catch (IOException) → BAD_FILE` 吞掉，把存储问题甩锅成"用户的文件有问题"。
  - **判错方向的代价不对称**：`statObject` 是 HEAD 请求、错误码各家不统一（`NoSuchKey` / `NotFound`），所以 `NoSuchKey`/`NoSuchBucket`/`NotFound`/HTTP 404 都算"缺失"，其余（权限、签名、网络抖动）抛 `FILE_STORAGE_FAILED`。把"缺失"误判成"查不动"会让**每次重复上传都失败**；把"查不动"误判成"缺失"只是白写一份同样的字节。
  - **"对象在就不重写"也让这条路径成了唯一的自愈兜底**（对象被误删时补写）：现在还没有孤儿对象清扫器，所以这个 `exists` 不能省成"直接返回旧行"。
  - 保留 `validateBasic` 基于声明大小的早退：诚实客户端不必落盘、也不必造临时文件就被拒；真实大小检查是补上撒谎那条路。

## D-20260930-media-watermark-outside-the-transaction-and-configurable-gates

- **状态：** accepted
- **背景：** 压测（`docs/capacity-tuning-2026-09-30.md`）发现图片上传把**其它接口**拖垮：`upload` 是 `@Transactional`，`DataSourceTransactionManager` 在**方法入口**就取一个 Hikari 连接；而图片水印要等一个只有 2 个许可的信号量、跑几秒 ffmpeg。48 并发时 10 个连接全被"排队等 ffmpeg"的请求占住，GET 元数据 p95/最大 1.97s。顺带两个小洞：去重读对新文件锁不住（并发首次上传同一份字节会留重复行 → `selectOne` 抛 `TooManyResultsException` → 下一次上传 500）；图片水印**成品**没有再核一次大小上限（视频那条有）。
- **决策：** ①`upload` 去掉 `@Transactional`，只把"去重（含 `FOR UPDATE`）→ 写对象 → 插行 → 视频登记 afterCommit 入队"放进注入的 `TransactionTemplate`（本仓 `AuditArchiveService`/`OidcService`/`WecomService` 已有先例）；ffmpeg 与信号量等待在事务外。②去重读改走显式 `@Select` + `ORDER BY created_at, id LIMIT 1 FOR UPDATE`，固定取最早那行、容忍重复。③图片闸门从写死 2 改成 `image-watermark-concurrency`（默认不变），并给图片成品补一次大小检查。
- **影响：**
  - **不能把"关键段"再拆小**：行锁必须覆盖到 MinIO 的 `put`（d95a746），否则并发 `delete` 会插进"查到重复行"和"写回去"之间。
  - **回滚语义靠"异常都是 unchecked"**：`writeStorageObject` 把 IOException 包成 `BizException`、`FileStorage.exists` 故意不抛受检、mapper 抛 `DataAccessException`、入队吞掉 `RejectedExecutionException` —— 所以 `TransactionTemplate` 的默认回滚等价于原来的 `rollbackFor = Exception.class`。**别加自定义回滚规则**（加了反而和现在不一致）。
  - **别用"抽一个带 `@Transactional` 的 public 方法再自我调用"**：本类已有这个坑（2 参重载自己调 3 参重载，3 参上的注解对这条路失效）。
  - **显式构造替代 Lombok**：信号量要从配置建，而 Lombok 生成的字段初始化器早于构造体执行 —— 在字段初始化器里读 `properties` 会 NPE。
  - **MP 的 wrapper 不合适做这条 SQL**：它把 `last()` 拼在 `ORDER BY` 之前、再自己追加 `LIMIT 1`，`orderByAsc(...).last("LIMIT 1 FOR UPDATE")` 生成非法 SQL（真库用例当场 `BadSqlGrammar`）。要精确控制就写显式 `@Select`。
  - 两个上限都是**配置**而不是硬件：视频 = `processing-concurrency + processing-queue-capacity`（默认 22，超出直接 FAILED）；图片 = 信号量（默认 2，不拒收只排队）。硬件决定速率与内存（每路 720p ≈ 1 核 + 200MB）。生产按规格调，见容量文档的表。
  - `compose.yaml` 的 Hikari 变量用 `${DB_POOL_MAX_SIZE:-10}`：compose 的默认值语法是 `:-`，写成 Spring 那种 `${VAR:10}` 只会打个警告然后停住。

## D-20260930-video-queue-never-rejects-and-pumps-on-completion

- **状态：** accepted
- **背景：** 视频转码线程池是 `core=max=processing-concurrency(2)` + `queue(20)` + `AbortPolicy`，上传路径用的是"拒绝即失败"（`RejectPolicy.FAIL`）——第 23 个视频在**对象与行都写完**之后被标 `FAILED`，用户看到的是"上传成功但处理失败"。而"忙"不是"坏"：队列满只说明要排队。
- **决策：** **永不拒收**。线程池拒绝时 `releaseProcessingClaim` 把行留在 `PROCESSING` 等空位（库里那一行就是队列）；每个转码任务在 `finally` 里调 `reap(1)`——**完成即泵**，一有槽位就认领下一条。`reapStaleProcessing`（定时/启动恢复）只当"进程真的没了"的兜底，不再是排队的一部分。
- **影响：**
  - **`RejectPolicy` 整个枚举删掉**：全仓只有上传路径和 reaper 两个调用点，reaper 本来就是 LEAVE 语义。留着两个语义不一致的策略只会让人再选错一次。
  - **泵放在任务 `finally` 里，不是包 executor**：`FileProcessingConfig` 的 bean 就是普通 `ThreadPoolTaskExecutor`，包 `execute()` 会造出 bean 循环依赖；`finally` 每条路径都跑、且每个释放的槽位只跑一次。
  - **不活锁**：泵只在任务完成之后触发（槽位确定空着），`reap(1)` 匹配不到行就返回空集，拒绝时释放认领后不再重试 → 一次完成至多一次入队。
  - **客户端轮询预算跟着抬**（600→1800 次 = 30 分钟）：积压时第 23/24 个视频要排队等（并发 2 时约 12 分钟），原来的 10 分钟上限会变成新的"失败"来源。真正的长久解是任务进度 UI，记在遗留里。
  - 容量口径变了：视频的"并发上限"不再是"超过就失败"，而是"超过就排队等"。`processing-reap-limit` 仍要与真实空位对应。

## D-20260930-form-data-status-is-a-whitelist

- **状态：** accepted
- **背景：** `submit` 收客户端传的 `status`，原来只做 `status == null ? "SUBMITTED" : status`，于是能往 `t_form_data.status` 塞 `APPROVED`/`REJECTED`——那两个是 `t_process_instance` 的状态，由引擎推进实例时写。塞进去的记录会落进台账/报表的"其它"桶，且没有任何东西能解释它。
- **决策：** 只接受 `DRAFT`/`SUBMITTED`，其余抛 `BAD_STATUS`。
- **影响：** 客户端如果自己实现了状态机（比如把实例状态回写到表单状态）会立刻收到明错，而不是产生无法解释的数据。台账/报表的状态分桶因此可以假定只有两个合法值。

## D-20260930-contacts-export-reuses-the-list-query

- **状态：** accepted
- **背景：** 通讯录导出原来是纯前端：把**当前页**（一页 15 人）拼成 CSV。一页 15 人时"导出成功"却少了人，而且前端拿不到行级数据范围的信息，导出的范围与"看得到的范围"没有可验证的关系。
- **决策：** 新增 `GET /api/users/export`（csv/xlsx），参数与 `GET /api/users` 一字不差、走同一条 `UserService.listAuthorized`（无分页 + 行级数据范围），表头与前端导入器的 `headerMap` 对齐；前端删掉 `buildMembersCsv`，只负责下载。
- **影响：**
  - **"你导出的绝不会比你看到的多"** 由"同一套参数 + 同一条查询"保证，而不是两处实现碰巧一致。
  - **导出能原样导回**：表头就是导入器认的那组中文列名（姓名/工号/账号/手机/邮箱/职务/性别），性别出 `男/女`（导入侧 `normalizeGender` 两套都收）。
  - **共享的渲染器**：复用台账导出的 `FormDataExport.Model/csv/xlsx`（BOM、公式注入防护、Excel 全字符串单元格）。服务端不再需要一份前端 CSV 实现，也就不会漂移。
  - 行数上限 10,000（与台账导出同量级），截断写进审计的 `truncated`。
  - 前端 `blobErrorMessage` 从 `pages/report/Export.tsx` 提到 `utils/format.ts`：下载接口出错时后端返回 JSON 而非文件，两处都得读出来才有话说。

## D-20260930-visibility-resolves-like-the-backend

- **状态：** accepted
- **背景：** 前端 `visibleNodeIds` 原来是一遍 `forEach` 边走边判：条件引用**后面才声明**的字段时，那个字段还没被算过，"来源不可见"于是被判成"依赖它的字段不可见"——同一份数据前后端能得出两套可见性（后端 `FormDefinitionService.resolveVisible` 是按 id 递归求值 + memo）。另一处口径不一致：`Number(null)`/`Number('')` 都是 0，空数字字段能"满足" `gte 0` 之类的条件把下游字段显示出来，而后端 `compareNumbers`（BigDecimal 解析失败即 false）判不成立。
- **决策：** `visibleNodeIds` 换成记忆化 DFS（id→node/parent 映射 + memo + `visiting` 防互指条件死循环），声明顺序无关；`numberCompare` **先拒空值再做数值转换**。
- **影响：**
  - 前端提交前的"可见/必填"判定与后端提交/取数时的判定同源，条件字段前后声明不再产生差异。
  - 数值型显示条件的四个操作符（`gt/gte/lt/lte`）在"源值为空"时一律为假——这条是**口径**，别为了"空值当 0"再改回去。
  - 明细表每行各自算一次可见性并复用给校验与"还有文件在上传"扫描：按行条件隐藏的列既不该拦提交，也不该因为在传而被当成"仍在处理"。

## D-20260930-outbox-dedupe-key-is-the-outbox-row

- **状态：** accepted
- **背景：** 渠道侧的幂等键（`t_wecom_message_delivery.dedupe_key`）对非抄送事件退回 `type:instanceId:taskId:userId`，而**不同事件会共用这几个字段**（撤回后重新指派、改派回原审批人都会对同一个 task 再发一次 `TASK_ASSIGNED`）→ `ON CONFLICT DO NOTHING` 把合法的"再次通知"**静默丢掉**。另外两处：2 分钟租约期间 worker 还活着但外部调用卡住时会过期，另一个实例认领同一行投两遍；webhook 是 at-least-once 却没有任何稳定事件标识，接收端无法去重。
- **决策：** ①幂等键改用 `type:instanceId:event.id()`（outbox 行 id 跨 attempt 稳定）；抄送保留按轮次（一轮是一条汇总消息）。②`renewLeases()` 定时**只续本 worker 自己持有的 RUNNING 行**。③webhook 请求头带 `X-AntFlow-Event-Key`、body 带 `eventKey`（同一个键）。
- **影响：**
  - **"重投"与"再次通知"从此可区分**：同一条 outbox 行重投键不变（去重仍有效），不同行必然不同键（不再被吞）。
  - **续租的边界**：崩掉的进程续不了 → 它的行照样在 2 分钟后可被抢（恢复路径不变）；活着但慢的 worker 不会被抢走同一行。代价是"卡死但没崩"的 worker 会一直占着那行——对 webhook 来说，宁可晚也不愿意投两遍。
  - **webhook 明确是 at-least-once**：接收端要幂等请用 `eventKey`。文档里不再有"我们保证只发一次"的暗示。
  - `t_user_notification` 那条插入本来就是 `ON CONFLICT DO NOTHING`，不受影响。

## D-20260930-upload-inserts-row-before-object-and-the-sweeper-is-dry-run

- **状态：** accepted
- **背景：** 上传原来是"先写对象、后插行"：`put` 成功而事务最终没提交（连接断、进程被杀）就留下一个没人引用的对象，而且那种对象按现有读路径**永远不会被发现**。另外转码结果用的是独立 attempt key，在发布前也没有任何行引用它。
- **决策：** ①改成**先插行、后写对象**（key 由本方法生成的 UUID 算得出，两者没有先后依赖）；②新增 `MobileFileOrphanSweeper`：桶里有、**没有任何 `t_mobile_file` 行引用**、且 mtime 早于 24 小时的对象，每轮最多 500 个、逐对象删；**默认 `orphan-sweep-delete=false`，只记日志**。
- **影响：**
  - 现在最坏是"行在、对象没写成"，而那会让整个事务回滚——不再产生孤儿对象。
  - **24 小时宽限覆盖两种"暂时没有引用"**：写对象与插行之间的窗口、以及转码 attempt key 在发布前的窗口。两种都是分钟级，宽限留了两个数量级。
  - **判据是"没有行引用"，不是"没有已提交单据引用"**：`PROCESSING` 的行引用的正是它即将被替换掉的源对象，排队等转码期间那也是活的。
  - **默认不开真删**：删除不可逆，先跑一轮看它打算删什么（日志里会打前 10 个候选）。要开就是把 `antflow.mobile.files.orphan-sweep-delete` 设 true。
  - 存储不支持列举（`FileStorage.list()` 默认抛 `UnsupportedOperationException`）时跳过本轮，不会把定时任务打挂。

## D-20260930-drop-the-unusable-jsonb-path-ops-index

- **状态：** accepted
- **背景：** `V44` 给 `t_option_data_source_row.data` 建了 `GIN (data jsonb_path_ops)`。`jsonb_path_ops` 只服务 `@>`/`@?`/`@@`，而读这张表的两条查询（`OptionRuntimeService.labelsForValues` / `querySchema`）都是 `version_id = ?` + `data ->> '列名' IN (...)` / `= ?`。实测（5 万行、`SET enable_seqscan=off` 逼优化器只能用索引）：那条真实形状的查询走的是 **Bitmap Index Scan on 主键** + Filter（49997 行被过滤），而 `data @> '{"code":"C1"}'` 才走 GIN。
- **决策：** `V52` `DROP INDEX IF EXISTS ix_option_source_row_data;`，理由写在迁移里。
- **影响：**
  - 想让它有用只有两条路，都不通：把查询改成包含式（会改变语义与参数绑定），或建 `(data ->> '列名')` 表达式索引（列名是每个数据源自己配的，运行时才知道）。所以不是"暂时没用"，是**没有补救余地**。
  - `version_id` 已经把范围收窄到一个版本（主键前导列），版本内行数有界，不需要为它再设计索引。
  - 留着只会让导入（逐行 INSERT）多维护一份倒排：纯开销。

## D-20260930-antd-deprecations-that-cannot-be-migrated-yet

- **状态：** accepted（部分清理，其余明确等待）
- **背景：** `npx antd lint ./src` 是本仓库的提交前门槛，但从来不是干净的（清理前 61 deprecated + 45 usage）。这轮只清掉语义等价的 17 处（`Alert message`→`title`、`Space split`→`separator`、`Steps direction`→`orientation`、`Modal maskClosable`→`mask.closable`）。
- **决策：** 其余 44 处**不动**，原因逐条实测：
  - **Select 的 `showSearch={{ onSearch, filterOption, optionFilterProp }}` 在当前依赖里是空操作**：antd 6.5.3 + rc-select 14.1.18 的 `BaseSelect` 只把 `showSearch` 当布尔用，整个 rc-select/antd select 里没有任何地方读 `showSearch.onSearch`。照提示改了一版，`AssigneePicker` 的搜索用例立刻红（关键字永远停在空串）——即"照着提示改"会**静默改坏搜索**。要改得等 antd 升到已接线的版本。
  - **Space `direction` → `Flex`（28）**：`Space size`（small/middle/large = 8/16/24）到 `Flex gap` 要逐处判断，且两者的布局语义（inline-flex、separator、对齐）不等价 → 必须配视觉验证，不能混在"清理"里。
  - **Drawer `width` → `size`（3）**：`size` 是枚举，自定义宽度（如 760）表达不出来，只能改 `styles`——同样是视觉改动。
  - **静态 `message.*`/`Modal.confirm`/`notification.open`（41）**：`requestErrorConfig.ts` 这类非组件模块拿不到 hook，得先做"模块级实例由 App 组件注入"的改造，是独立一轮。
- **影响：** 门槛目前**不是 0**，别把"`antd lint` 干净了"当前提。新增代码保持与既有写法一致（同一个页面两种写法比一条弃用警告更糟）。

## D-20260930-hidden-is-not-a-secrecy-boundary（明确不做）

- **状态：** accepted（记录"不做"，不是待办）
- **背景：** `props.formPerms` 的 `HIDDEN` 目前只在**写路径**（提交时剔除、审批时按 schema 校验/回写）生效，读路径（台账/详情/导出对**有权限**的读者）原样返回。OCR 反复标它 high。
- **决策：** **保持现状**。把它变成保密边界等于**改口径**：要对哪些视图、哪些角色、哪些字段过滤，都没定；而一旦过滤，有权限读者拿到的载荷会全部改变。
- **影响：** 谁要把它当保密手段是误解——权限的正确表达是"这条记录你读不到"，不是"这个字段我藏起来"。真要做这件事，先定清上面三个维度，且必须逐视图验证。

## D-20260930-unhandled-errors-count-again-in-frontend-tests

- **状态：** accepted
- **背景：** `frontend/vitest.config.ts` 曾挂 `dangerouslyIgnoreUnhandledErrors: true`，起因是 `MobileFormPreview` 真的挂 `<iframe src="/mobile/form-preview">`，happy-dom 会去请求它、中止时抛的 DOMException 无人接。代价是**全项目不再因未处理错误而红**——那层安全网比一条控制台噪音值钱。
- **决策：** 收回该 flag。实测（vitest 4.1.10）连跑三次全量 59 文件 / 288 用例全部退出码 0，那条 DOMException 只打印、不再让 run 失败。
- **影响：** 异步未处理错误重新算红。已排除的窄口径修法（about:blank 的 origin=null、`disableIframePageLoading` 的 contentWindow=null、自挂 `unhandledRejection`、桩 `global.fetch`）与"万一以后又在负载下变红"的处理顺序都写在 `vitest.config.ts` 的注释里，别重复试。
