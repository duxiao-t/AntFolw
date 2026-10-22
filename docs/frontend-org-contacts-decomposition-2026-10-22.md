# 前端巨型组件拆分：`org/Contacts.tsx`（750 行）

日期：2026-10-22 ｜ 目标：`frontend/src/pages/org/Contacts.tsx`（750 行 → ≤600，理想 ≤400）
参照：[`frontend-form-wizard-decomposition-2026-10-22.md`](frontend-form-wizard-decomposition-2026-10-22.md)、
[`frontend-proc-detail-decomposition-2026-10-22.md`](frontend-proc-detail-decomposition-2026-10-22.md)、
[`frontend-wecom-settings-decomposition-2026-10-22.md`](frontend-wecom-settings-decomposition-2026-10-22.md)
的拆法（按**变化原因**切：纯逻辑 → hook → 视图，壳只做装配）

## 0. 一句话

这个文件是"通讯录页"的**一个页面壳 + 两栏布局 + 三个域（部门 / 成员 / 账号弹窗）+ 十二条数据链路 + 六个写操作**。
行政上是一个页面，但变化原因完全不同：改部门拖拽规则不动成员表列，改 CSV 导入不动负责人选择器，
改重置密码校验不动左树渲染。因此按**域**切成 3 个 hook + 3 个视图 + 1 个类型文件，
`Contacts.tsx` 只留"页面壳 + 跨域状态 + 装配"。

## 1. 当前职责清单与"变化原因"

| # | 职责 | 现状位置 | 变化原因（谁会来改它） |
| --- | --- | --- | --- |
| R1 | 类型：`Dept` / `UserItem` / `UserPage` / `ManagerCandidate` | 30-45 | 后端接口字段增减 |
| R2 | 页面壳：公司列表查询、单选/多选公司、`busy` 计算、`key={companyId}` 重置 | 47-79 | 公司选择交互 |
| R3 | 部门查询 + 树构造 + 三种索引（父、名称、可管成员）+ 选中部门派生 | 111-215 | 树的形状与权限字段 |
| R4 | 部门写操作 6 个：建 / 改名 / 换父 / 排序 / 拖拽定位 / 删 | 232-277 | 部门接口与失效范围 |
| R5 | 拖拽落点判定（gap 相对位置 → 排序 or 换父） | 306-328 | 拖拽语义 |
| R6 | 左树节点渲染：节点菜单 7 项、禁用条件、删除确认、`contextOnly` 只读 | 330-373 | 菜单项与权限口径 |
| R7 | 面包屑（从已授权部门列表推导，不请求 `/path`） | 375-382 | 面包屑口径 |
| R8 | 部门弹窗表单：新建 / 子部门 / 改名，回填 effect 与提交 payload | 384-402、519-532、627-667 | 表单字段 |
| R9 | 负责人选择：`leader-users` 查询 + 设置 + 刷新成员页 | 138-142、403-413、669-689 | 负责人交互 |
| R10 | 成员列表：分页查询、页/选中重置、跨页勾选保留 | 117-123、217-224 | 列表契约 |
| R11 | 关键词：同一个词过滤左树 + 跨部门搜人（分页） | 125-136、226-230 | 搜索口径 |
| R12 | 成员写操作：增/改/删、批量删、导入 CSV、导出 CSV、登录开关、密码重置 | 279-304、442-517 | 接口与提示文案 |
| R13 | 成员编辑弹窗：上级候选查询、公司切换、部门树、提交 payload（`managerId ?? null`） | 144-173、414-425、691-716 | 表单与候选契约 |
| R14 | 密码重置弹窗（两条校验规则） | 718-747 | 校验规则 |
| R15 | 右栏三态装配（search / members / empty） | 579-624 | 布局 |
| R16 | 左栏装配（搜索框 + 新建一级部门按钮 + 空态提示） | 539-576 | 布局 |

行数分布：视图 JSX ≈ 430、hooks/查询/写操作 ≈ 190、派生与 effect ≈ 80、类型 16。

**关键事实**：`Contacts.utils.ts`（316 行）已经承担了所有纯函数（树的构造/过滤/拖拽判定/CSV 解析/分页常量），
`Contacts.components.tsx`（455 行）已经承担了表格、成员表单、负责人选择器三块**叶子视图**。
所以本次不需要再造纯函数层，只把**页面内部**的编排拆开。

## 2. 切分方案与理由

### 2.1 三个域，各一个 hook

| 域 | hook | 为什么是一个域 |
| --- | --- | --- |
| 部门 | `useContactsDepartments` | 左树、部门弹窗、负责人三处的写操作**共用**同两条失效链（`['depts']`、`['dept-path']`），拆成两个 hook 会让失效范围各写一份、迟早漂移 |
| 成员 | `useContactsMembers` | 列表查询、勾选、批量删、导入、导出共用 `refreshMembers()` 这一个刷新口径（4 个 queryKey），与部门域的唯一交汇点是"选中部门" |
| 成员弹窗 | `useContactsMemberDialogs` | 编辑弹窗与重置密码弹窗共享"当前编辑对象"这一条状态链（编辑弹窗里点"重置密码"→ 关闭编辑、打开密码框），必须同宿主 |

### 2.2 state 归属

| state / 数据 | 归属 | 理由 |
| --- | --- | --- |
| `selectedCompanyId`、`busy`、`mutations` | `ContactsPage`（壳） | 切公司要 `key={companyId}` 整棵子树重置，这一段原样不动 |
| `selDeptId`、`search`、`memberPage` | `CompanyContacts`（壳） | **跨域**：`selDeptId` 同时喂部门的树/面包屑与成员的列表/导出；`search` 同时喂左树过滤与人员搜索；`memberPage` 会被"设置负责人"重置 |
| `expandedKeys`、树过滤结果 | `ContactsDeptPanel`（局部） | 只在左栏用；原 effect 依赖 `[filteredTree, search]`，随组件挂载不变 |
| 部门查询、5 个索引、6 个写操作、拖拽 | `useContactsDepartments` | 唯一消费者是左栏与部门弹窗 |
| 部门弹窗 open/form | `useContactsDepartments` | `handleDeptAdd`/`handleLeaderSet` 要直接 `mutateAsync` 部门写操作 |
| 成员列表/搜索查询、勾选、批量/导入/导出、登录开关 | `useContactsMembers` | 同域 |
| 成员表单、上级候选、部门树、密码重置 | `useContactsMemberDialogs` | 同域；保存成功后调 `refreshMembers()`（由壳传入） |

**必须提升（不能下沉）**：`selDeptId`、`search`、`memberPage`。
`setSelDeptId` 由部门域（删除后清空选中）与成员域（搜索结果"定位"）共同写，因此放在壳里以 prop 传下去。

### 2.3 视图切分

- `ContactsDeptPanel.tsx`：左栏整体（搜索框、新建一级部门按钮、`Tree`、节点菜单、拖拽配置、空态提示）。
  节点菜单的 7 项与禁用条件原样搬入；删除确认留在 hook（`modal.confirm` 需要部门名，属数据层）。
- `ContactsDeptDialogs.tsx`：新建/子部门弹窗 + 改名弹窗 + 负责人弹窗（三个 `Modal` 共用 `deptForm`，必须同位）。
- `ContactsPasswordDialog.tsx`：重置密码弹窗（两条规则 + `destroyOnHidden`）。
- `Contacts.tsx`：页面壳 + 跨域状态 + 右栏三态装配 + `MemberFormModal` 装配（它已在 components 里）。

## 3. 拆分后的文件清单

| 文件 | 职责（一句话） | 估算 | 实际 | props / 参数契约 |
| --- | --- | --- | --- | --- |
| `Contacts.tsx`（改） | 页面壳（公司选择 + `busy`）+ `CompanyContacts`（跨域状态、三态装配、成员弹窗装配） | ~250 | **205** | 默认导出 `ContactsPage` 不变；无 props |
| `Contacts.types.ts`（新） | `Dept`、`MemberItem`、`UserPage`、`ManagerCandidate`、`CompanyOption` | ~45 | **57** | 纯类型，无运行时代码 |
| `useContactsDepartments.ts`（新） | 部门查询 + 5 个索引 + 面包屑 + 6 个写操作 + 拖拽 + 部门弹窗/负责人状态 | ~240 | **273** | `{ companyId, selDeptId, onSelectDept, onLeaderSaved }` |
| `ContactsDeptPanel.tsx`（新） | 左栏：搜索框、新建一级部门、`Tree`、节点菜单、拖拽、空态 | ~170 | **121** | `{ departments, treeData, search, onSearchChange, canCreateRoot, onAddRoot, selectedDeptId, onSelectDept, onDrop, actions }` |
| `ContactsDeptDialogs.tsx`（新） | 新建/子部门 + 改名 + 负责人三个 `Modal` | ~120 | **115** | `{ companyLabel, departments, form, addOpen, addParentId, createPending, onAddOk, onAddCancel, editOpen, editName, updatePending, onEditOk, onEditCancel, leaderOpen, leaderUsers, leaderCurrentIds, leaderSaving, onLeaderOk, onLeaderCancel }` |
| `useContactsMembers.ts`（新） | 成员列表/搜索查询、分页、勾选、批量删、导入、导出、登录开关 | ~170 | **187** | `{ companyId, selDeptId, search, memberPage, setMemberPage, breadcrumb, onBusyChange }` |
| `useContactsMemberDialogs.ts`（新） | 成员表单状态、上级候选、成员部门树、增/改、密码重置 | ~150 | **154** | `{ companyId, selDeptId, canAdmin, refreshMembers }` |
| `ContactsPasswordDialog.tsx`（新） | 重置密码弹窗（校验规则原样） | ~50 | **45** | `{ target, form, saving, onOk, onCancel }` |

新文件全部 ≤273 行；目标文件 750 → **205**（-73%）。org 目录合计 2259 → 2559 行（+300，全部是新文件的 import/导出与 props 声明；
`Contacts.components.tsx` 因类型上移反而少了 15 行）。

`Contacts.components.tsx` 只做一处不动语义的改动：`MemberListItem` 改为 `Contacts.types.ts` 里 `MemberItem` 的别名，
避免同一份行类型抄两遍（导出的名字与结构都不变，`Contacts.components.test.tsx` 的类型导入照旧可用）。

## 4. 风险与对策

| # | 风险 | 对策 |
| --- | --- | --- |
| R1 | **重复请求**：成员列表、人员搜索、部门列表三条 query 换宿主后若被条件渲染会反复挂载 | 全部无条件渲染；`queryKey`、`enabled`、`params` 一字不改（`['depts', companyId]`、`['users-page', companyId, selDeptId, memberPage]`、`['users-search', companyId, keyword, searchPage]`、`['leader-users', companyId]`、`['manager-candidates', …]`） |
| R2 | **树与列表状态同步**：删除部门要清空 `selDeptId`，搜索结果"定位"要改 `selDeptId` + 清关键词 + 回第 1 页 | `selDeptId`/`search`/`memberPage` 都留在壳里，两侧只调壳给的 setter；`onLeaderSaved` 把 `setMemberPage(1)` 回传给部门 hook，顺序与原来（设置负责人后回第 1 页）一致 |
| R3 | **权限判定口径漂移**：`canOrgDepartmentManage`（建一级部门）、`selectedDepartment.canManageUsers`（加成员）、`canManageUsersByDept[deptId]`（编辑/勾选）、`canAdmin`（重置密码/登录控制）、`canManageDepartment`（节点菜单/拖拽） | 判定点原样保留在各自消费处：左栏用 `canOrgDepartmentManage` 与每节点 `canManageDepartment`；右栏用 `canManageUsersByDept`；`canAdmin` 由壳读一次 `useAccess()` 后以布尔值传下去，不新增/不合并任何条件 |
| R4 | **i18n key 搬迁**：本页文案全部内联中文，无 `formatMessage` | 文案逐字复制（含提示语、确认语、空态、CSV 提示）；不重写、不改标点 |
| R5 | **测试断言的受控属性**：`aria-label="通讯录公司"`、`placeholder="搜索部门或人员"`、按钮名 `编辑`/`OK`、`Form.Item` label（`姓名`/`直属上级`/`所属部门`）、`编辑` 弹窗可见性 | 表单与表格 JSX 整段搬移，属性零改动；`Contacts.test.tsx` 不改一行 |
| R6 | **提交 payload 变化**：成员保存要 `managerId: payload.managerId ?? null` 且剔除 `confirmPassword`/`companyId`；部门新建 `parentId` 三级回退（`v.parentId ?? deptAddParentId ?? null`） | 两个 payload 组装函数原样搬进对应 hook，一行不改；测试里对 `PUT /api/users/100` 的 `data` 断言会兜住 |
| R7 | **`Form.useWatch` 的宿主**：`companyId`/`deptId` 的 watch 从页面移到成员弹窗 hook | 仍在同一 `<Form>` 外层子树内（`form` 实例由壳创建后传入），watch 语义不变 |
| R8 | **弹窗 `destroyOnHidden` 与回填 effect**：部门弹窗靠 effect 回填名称，若弹窗被条件渲染会丢 | 三个 `Modal` 仍无条件渲染（`open` 控制），两个回填 effect 依赖数组原样 |
| R9 | **导入/导出的 Blob 处理**：`responseType: 'blob'` + `skipErrorHandler` + `URL.revokeObjectURL` | `handleImportMembers`/`handleExportMembers` 整体搬移，仅把 `msg` 来源改为 hook 内的 `App.useApp()` |

## 5. 验收结果

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 类型 | `cd frontend && CODEBUDDY_SAFE_DELETE_ENABLED=0 npm run tsc:fast` | 退出码 **0**（每抽一个文件跑一次，共 5 次；中途一次 `setLoginAccess` 签名不匹配已修） |
| 测试 | `npm test -- org` | **3 个文件 / 35 个用例全过**（`Contacts.test.tsx` 3、`Contacts.components.test.tsx` 9、`Contacts.utils.test.ts` 23），与拆分前同数 |
| 边界 | `npm run check:boundaries` | 存量 8 条，**无新增**（新文件都在 `pages/org` 同 zone 内） |
| H7 | `node scripts/health-metrics.mjs` | 退出码 **0**；`frontendOverLines` **5 → 4**，待拆清单 23 → 22；`Contacts.tsx` 750 → 205 |
| 风格 | `npx biome lint src/pages/org` | 13 个文件 0 问题 |

### 行为不变性证据

1. **默认导出与路由**：`export default function ContactsPage()` 未改名、未改签名；`pages/registry.ts` 的
   `./org/Contacts` 与 `.umi` 生成的路由都没有动。
2. **数据契约逐字保留**：6 条 `queryKey`（`['companies']`、`['depts', companyId]`、`['users-page', companyId, selDeptId, memberPage]`、
   `['users-search', companyId, keyword, searchPage]`、`['leader-users', companyId]`、`['manager-candidates', …]`）与
   对应 `params`（`includeDescendants: true`、`size: PAGE_SIZE`、`keyword: managerKeyword || undefined`）全部原样。
3. **写操作 payload**：成员保存仍剔除 `confirmPassword`/`companyId` 并强制 `managerId ?? null`；
   部门新建仍 `parentId: v.parentId ?? deptAddParentId ?? null`；失效链（成员 4 个 key / 部门 depts+dept-path）与提示文案未改。
4. **测试兜底**：`Contacts.test.tsx` 一行未改仍全过——它直接断言了 `PUT /api/users/100` 的 `data`（`displayName` 保留输入、
   `deptId: 10`、`managerId: null`；跨公司 `deptId: 20, managerId: 101`）、搜索请求的 `params`、
   切公司后编辑弹窗关闭与搜索框清空。
5. **可见文案**：`aria-label="通讯录公司"`、`placeholder="搜索部门或人员"`、空态两句、节点菜单 7 项、删除确认文案、
   CSV 导入导出提示、密码校验两条规则均为逐字复制。
6. **权限口径**：`canOrgDepartmentManage`（建一级部门）、`canManageDepartment`（节点菜单/拖拽）、
   `selectedDepartment.canManageUsers`（添加成员）、`canManageUsersByDept[deptId]`（行内编辑/勾选）、
   `canAdmin`（重置密码/登录控制）五个判定点在原消费处原样保留，未新增也未合并。

### 遗留风险

- `useContactsDepartments.ts` 273 行，是本次最大的新文件：它同时管"数据/写操作"与"两个弹窗的状态"。
  再来一类部门侧写操作（如批量调整排序、部门负责人多选上限）时，应把弹窗状态拆成 `useContactsDeptDialogs`。
- 跨域状态（`selDeptId`/`search`/`memberPage`）留在页面壳里，靠 `onSelectDept`/`onLeaderSaved` 两个回调传下去；
  新增"改选中部门"的入口时必须走壳的 setter，否则会出现"左树已换、右表还是旧部门"。
- 成员弹窗的部门树与左树共用 `['depts', companyId]` 这个 key 但 `queryFn` 不同（沿用拆分前的写法）。
  这是既有行为，本次未改；若将来统一，要同时确认 TreeSelect 只接受"可选部门"的 `selectable` 过滤。
