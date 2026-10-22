# `FormDefinitionService` 拆分方案（2026-10-22）

> 对象：`backend/src/main/java/com/antflow/form/FormDefinitionService.java`，拆前 **1403 行 / 76 方法**。
> 口径、纪律与样板见 [`class-decomposition-execution-2026-10-20.md`](class-decomposition-execution-2026-10-20.md)
> §3.2（`WecomService` 六刀）与 §6（五条硬规则）。本文只记录"为什么这样切"，实施记录见文末 §7。

## 1. 目标类当前职责清单（按"变化原因"聚类，不按行号切）

| 簇 | 方法（行号） | 变化原因 | 状态 |
| --- | --- | --- | --- |
| **A. 读取与列表** | `list`×2(44-58)、`getByCode`(60)、`getPublishedByCode`(66)、`getById`(72)、`mapByIds`(75)、`normalized`(84) | 查询口径/可见范围 | 留原类 |
| **B. 草稿写入与状态机** | `saveDraft`(88)、`update`(149)、`publish`(183)、`disable`(202)、`softDelete`(213)、`validateStatus`(1398) | 表单生命周期（唯一写入口） | 留原类 |
| **C. JSON 边界** | `readTree`(481)、`writeJson`(596)、`sameJson`(585)、`valueMap`(1265)、`isEmpty`(1360) | 入参形态归一 + jsonb 排版差异 | 拆出 `FormSchemaJson` / `FormVisibility` |
| **D. Schema 解析与叶子字段** | `parseSchema`(667)、`validateNode`(689)、`leafFieldIds`(496)、`leafFieldTypes`(501)、`collectLeafFieldTypes`(510) | 结构契约（什么算合法 schema） | 拆出 `FormSchemaParser` |
| **E. 发布期结构校验** | `validateSchema`(604)、`rejectDisplayConditionCycles`×2(620/633)、`collectConditionSources`(657)、`validatePublishingNode`(1225)、`validateSelectOptions`(1182)、`validateSelectDisplayStyle`(1205)、`validateChecklistResultColors`(1215)、`validateDisplayCondition`(1251) | "能不能发布" | 拆出 `FormSchemaValidator` |
| **F. 矩阵填报** | `validateMatrixSchema`(993)、`validateMatrixSubmission`(1036)、`validateMatrixCell`(1081)、`readMatrixAxis`(1123)、`positiveMatrixLimit`(1156)、`ensureNoMatrixAxisCollision`(1164)、`matrixLabel`(1176)、`MatrixAxisItem`(1180) | 矩阵控件的行列轴语义（发布/提交共用） | 拆出 `FormMatrixRules` |
| **G. 提交期值校验** | `validateSubmission`×2(222/231)、`validateNodeValue`×2(554/714)、`validationNode`(832)、`validateValueShape`(843)、`validUserPicker`(900)、`validateChecklistValue`(909)、`containsAcceptedField`(944)、`validateTableValue`(957)、`validateDepartmentPickerValue`(1367)、`invalidDepartmentPickerValue`(1383)、`isPositiveIntegralId`(1389) | "这份数据能不能提交" | 拆出 `FormSubmissionValidator` |
| **H. 可见性求值** | `visibleNodeIds`×2(1273/1288)、`collectNodes`(1294)、`resolveVisible`(1303)、`isVisible`(1322)、`sameValue`(1347)、`compareNumbers`(1351) | displayCondition 的运行时语义 | 拆出 `FormVisibility` |
| **I. 首填可见性投影** | `projectStarterSchema`(326)、`projectStarterData`(339)、`projectSchemaNode`(435)、`removeHiddenData`(454)、`collectVisibleValues`(526)、`hasStarterVisibleField`(422)、`filterVisibleSubmission`(241) | 返回给发起人的"看得见的部分" | 拆出 `FormStarterProjector` |
| **J. 首填数据规范化** | `canonicalizeStarterSubmission`(252)、`canonicalizeStarterData`×2(263/295)、`canonicalizeStarterRevision`(269)、`validateStarterSubmission`(279)、`validateStarterRevision`(287)、`starterFieldModes`(309)、`collectStarterValues`(352)、`collectStarterTable`(386) | ROOT 字段权限（可编辑/只读/隐藏）在提交时的落地 | 拆出 `FormStarterCanonicalizer` |

聚类依据是"改一个需求要动哪些方法"，不是"方法在文件里挨着"：

- D/E/F 只读 schema，**无 DB、无事务**；G/H 读 schema + data，仍然无 DB（唯一外部协作者是 `OptionRuntimeService`，且只做校验）。
- I/J 是"同一份 schema 的两种对外形态"：I 决定**看得见什么**，J 决定**收下什么值**。两者共用 `FormVisibility` 与 `hasStarterVisibleField`，
  所以先切 H，再切 I，最后切 J——顺序反了会让 J 反过来被 I 依赖，出现环。
- A/B 共享 `mapper` 与事务边界，且 B 是表单定义这个聚合的**唯一写入口**（`AGENTS.md` §3）。拆开会出现多个写入口，不拆。

## 2. 依赖与调用关系

### 2.1 谁调用这个类（决定了哪些公开方法必须留在原地）

| 调用方 | 用到的公开方法 | 是否在本次可改边界内 |
| --- | --- | --- |
| `FormDefinitionController` | `list`/`getPublishedByCode`/`saveDraft`/`update`/`publish`/`disable`/`softDelete` | ✅ `form/**` |
| `FormProcessPublishService` | `getById`/`publish` | ✅ |
| `form/runtime/FormDataService` | `getByCode`/`validateSubmission`/`filterVisibleSubmission` | ✅ |
| `form/runtime/MobileDraftService` | `mapByIds`/`getByCode`/`getById`/`project*`/`canonicalizeStarterData` | ✅ |
| `engine/ProcessEngine` | `getByCode`/`getById`/`canonicalizeStarterSubmission`/`starterFieldModes`/`validateSubmission` | ❌ 边界外 |
| `mobile/workflow/{MobileWorkflowService,ApprovalPreviewService}` | `getByCode`/`getById`/`project*`/`canonicalize*`/`validateStarterRevision`/`starterFieldModes` | ❌ 边界外 |
| `process/ProcessDefinitionService` | `getById`/`leafFieldTypes` | ❌ 边界外 |
| `task/InstanceController` | `getById`/`project*` | ❌ 边界外 |

**结论（本次最关键的取舍）**：`engine`/`mobile`/`process`/`task` 四个包不在可改边界内，
所以 24 个公开方法**一个都不能移出 `FormDefinitionService`**。原类保留同名方法（其中 13 个是**一行委托**，
逻辑落在协作者里；这与 `WecomService`（调用方都在同包，可以直接改注入）不同，是本次的硬约束。
代价是原类仍有 24 个方法，贴着 25 的上限——**后续任何新能力都必须加到协作者，不能回填门面**。

### 2.2 它调谁

- `FormDefinitionMapper`（MyBatis-Plus）、`FormGrantService`、`DefinitionVersionRepository`（可选）、`BusinessNumberService`（可选）
- `com.antflow.form.options.OptionRuntimeService`：`validateSchema`/`validateValue`/`validateBinding`/`requireNewBindingsAreReferenced`
- `ObjectMapper`：schema/settings 的 jsonb 读写

### 2.3 现有共享状态

| 状态 | 可变性 | 归属 |
| --- | --- | --- |
| `STATUSES` / `FIELD_TYPES` / `CONTAINER_TYPES` | 不可变集合 | 常量；`FIELD_TYPES` 复用 `FieldTypeCatalog`，新类直接引用同一个真源 |
| `versions` / `businessNumbers`（`@Autowired(required=false)`） | 引用不可变（bean 单例） | **只留在门面**：只有 `publish` 用 |
| `json`（`ObjectMapper`） | 线程安全、只读使用 | 可以共享，不算"共享可变状态" |
| `optionRuntimeService` | 无状态服务 | 校验入口共享 |

**没有**草稿缓存、schema 编译结果缓存或 `ThreadLocal`——所以本次不存在"缓存归属"问题，
新增类一律不持有可写字段（只有 `final` 协作者）。

## 3. 拆分后的类清单

新包 `com.antflow.form.schema`（跟随 `form/options`、`engine/tree` 的既有习惯，不新造顶层包）。

| 类 | 职责（一句话） | 行数（实测） | 方法数 | 构造器依赖 |
| --- | --- | --- | --- | --- |
| `FormSchemaJson` | schema/settings 的 JSON 边界：宽松解析、语义比较、序列化 | 76 | 4 | `ObjectMapper` |
| `FormVisibility` | `displayCondition` 的运行时求值：哪些节点可见 | 127 | 8 | `ObjectMapper` |
| `FormSchemaParser` | schema 文本 → 节点树；结构契约与叶子字段清单 | 115 | 5 | `ObjectMapper`、`FormMatrixRules` |
| `FormMatrixRules` | 矩阵填报的行列轴解析与单元格校验（发布/提交共用） | 221 | 8 | `ObjectMapper` |
| `FormSchemaValidator` | 发布期结构校验：字段类型、选项、展示条件环 | 183 | 9 | `FormSchemaParser`、`OptionRuntimeService`、`ObjectMapper` |
| `FormSubmissionValidator` | 提交期值校验：必填、形状、长度、选项值、明细行 | 399 | 13 | `FormSchemaParser`、`FormSchemaJson`、`FormVisibility`、`FormMatrixRules`、`OptionRuntimeService`、`ObjectMapper` |
| `FormStarterProjector` | 面向发起人的投影：删掉 HIDDEN 字段后的 schema/data | 181 | 8 | `FormSchemaJson`、`FormSchemaParser`、`FormVisibility`、`ObjectMapper` |
| `FormStarterCanonicalizer` | ROOT 字段权限落地：首填/返工的值合并与再校验 | 172 | 8 | `FormSchemaJson`、`FormSchemaParser`、`FormStarterProjector`、`FormVisibility`、`FormSubmissionValidator`、`ObjectMapper` |
| `FormDefinitionService`（留存） | 定义读取 + 草稿/发布/停用/删除 + 公开 API 门面 | 326 | 25 | 见 §2.1 |

全部新类 ≤400 行、≤25 方法（最接近上限的是 `FormSubmissionValidator`：399 行 / 13 方法，**只留 1 行余量**）。

## 4. 依赖方向（禁止环）

```
Controller / ProcessEngine / MobileWorkflowService / InstanceController / ProcessDefinitionService
                    │
                    ▼
          FormDefinitionService  ──────────────► FormGrantService / Mapper / versions / businessNumbers
                    │
     ┌──────────────┼───────────────────────────┐
     ▼              ▼                           ▼
FormStarterCanonicalizer   FormSubmissionValidator   FormSchemaValidator   FormStarterProjector
     │  │  │                    │   │   │                  │                    │
     │  │  └──► FormStarterProjector ──┐│   │              ▼                    ▼
     │  │              │  │            ││   │        FormSchemaParser ◄──── FormSchemaJson
     │  │              ▼  ▼            ││   ▼              │
     │  │        FormVisibility ◄──────┘│ FormSchemaJson   ▼
     │  │                             │ │             FormMatrixRules
     │  └──► FormSchemaParser ─────────┘ │
     └─────► FormSchemaJson               └──► FormVisibility
```

- 唯一的"菱形"是 `FormVisibility` / `FormSchemaJson` / `FormSchemaParser` / `FormMatrixRules` 被多个上层协作者共享；
  它们都是**无状态纯函数**，共享只带来复用，不带来耦合。
- **反向依赖被禁止**：新类不得 import `FormDefinitionService`（搬迁需要类型时用 `import` 引到新包，而不是回调门面）。
- `FormDefinitionService` 只向下依赖，不参与任何协作者之间的编排——它只做"一问一答"的转发 + 自己的写路径。

## 5. 共享可变状态的归属

本类没有缓存/`ThreadLocal`，本次**不新建任何持有可写状态的组件**。规则：

1. `ObjectMapper` 是 Spring 单例、线程安全，新类以构造器注入只读使用；
2. 每个新类只持有 `private final` 协作者，不存请求级中间结果（原来也没有）；
3. `versions` / `businessNumbers` 两个可选依赖**不下放**——下放会让新类继承"同代码不同装配走不同分支"的问题（§6.1）；
4. `optionRuntimeService` 由 `@Autowired(required = false)` 改为构造器注入（bean 必然存在），
   但调用点仍保留 `== null` 守卫：单测会直接 `new` 协作者并传 `null`，这与拆前行为一致。

## 6. 新类的公开方法签名（调用方改动最小）

### `FormSchemaJson`（`@Component`）
```java
public JsonNode readTree(Object value);
public Map<?, ?> valueMap(Object data);
public String writeJson(Object o);
public boolean sameJson(String left, String right);
```

### `FormVisibility`（`@Component`）
```java
public Set<String> visibleNodeIds(List<JsonNode> roots, Map<?, ?> values);
public Set<String> visibleNodeIds(JsonNode roots, Map<?, ?> values);
public static boolean isEmpty(Object value);
```

### `FormSchemaParser`（`@Component`）
```java
public List<JsonNode> parseSchema(String s);
public void validateNode(JsonNode node);
public Set<String> leafFieldIds(String schema);
public Map<String, String> leafFieldTypes(String schema);
```

### `FormMatrixRules`（`@Component`）
```java
public void validateMatrixSchema(JsonNode node);
public void validateMatrixSubmission(JsonNode node, Object rawValue);
public String matrixLabel(JsonNode node);
private record MatrixAxisItem(String id, String label) {}
private void validateMatrixCell(...);   // 仅矩阵内部使用
```

### `FormSchemaValidator`（`@Component`）
```java
public void validateSchema(String s);
public void rejectDisplayConditionCycles(Object schema);
public void rejectDisplayConditionCycles(List<JsonNode> roots);
public void validatePublishingNode(JsonNode node);
```

### `FormSubmissionValidator`（`@Component`）
```java
public void validateSubmission(String schema, Object data);
public void validateSubmission(String schema, Object data, Set<String> fieldIds);
public void validateNodeValue(JsonNode node, Map<?, ?> values, Set<String> visibleIds);
public void validateNodeValue(JsonNode node, Map<?, ?> values, Set<String> fieldIds, Set<String> visibleIds);
public void validateValueShape(JsonNode node, Object value);
public void validateTableValue(JsonNode node, Object value, Set<String> fieldIds);
public void validateDepartmentPickerValue(JsonNode node, Object value);
```

### `FormStarterProjector`（`@Component`）
```java
public Map<String, String> starterFieldModes(Object process);   // ROOT formPerms：字段 id → 模式
public JsonNode projectStarterSchema(Object schema, Object process);
public JsonNode projectStarterData(Object data, Object schema, Object process);
public Map<String, Object> filterVisibleSubmission(String schema, Object data);
public boolean hasStarterVisibleField(JsonNode nodes, Map<String, String> modes);
public void collectVisibleValues(JsonNode node, Map<?, ?> values,
                                 Map<String, Object> output, Set<String> visibleIds);
```
`starterFieldModes` 放在投影器而不是规范化器：它读的是"可见性输入"，而规范化器本身就依赖投影器，
放到规范化器会让两者互相依赖（Spring 启动即报循环依赖）。

### `FormStarterCanonicalizer`（`@Component`）
```java
public Map<String, Object> canonicalizeStarterSubmission(String schema, Object data, Object process);
public Map<String, Object> canonicalizeStarterData(String schema, Object data, Object process);
public Map<String, Object> canonicalizeStarterRevision(String schema, Object data, Object existingData, Object process);
public void validateStarterSubmission(String schema, Object data, Object process);
public void validateStarterRevision(String schema, Object data, Object process);
```

### `FormDefinitionService`（门面，签名一字不改）
24 个公开方法全部保留原签名，方法体只保留两种情况：

- **A/B 簇**（读 + 写路径）：实现仍在原类；
- **C–J 簇**：一行委托到上表同名方法。

## 7. 风险点与"不拆"的部分

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| 门面方法数 25/25 顶格 | 以后加一个方法就触发 H7 | 注释写死"新增能力加协作者"，评审时卡 |
| 13 个委托方法看着像"贫血壳" | 可能被误判为拆过头 | 门面同时承担 A/B 簇（读模型 + 唯一写入口，共 11 个真实实现），不是纯转发；§1 已说明 |
| `parseSchema` 有两套错误码（`BAD_SCHEMA` / `BAD_SCHEMA_JSON`） | 前端按 code 分支，改错会让"schema 写坏了"变成 500 | 搬迁时逐字保留，不合并 catch |
| 两个 `validateNodeValue` 重载 | 只搬一个会留下半套校验 | 两个一起搬（§3.2 的教训） |
| `@Transactional` 被搬散 | 部分成功、数据不一致 | 只有 A/B 留在原类；新类**零** `@Transactional`，也不回调门面 |
| `saveDraft` 的"内容未变不降级"依赖 jsonb 排版无关比较 | 误判会让已发布表单掉出手机端目录 | `sameJson` 连注释一起搬，行为逐字一致 |
| 构造依赖 12 个（门面） | 与 §5.1 的 ≤8 目标冲突 | 门面必须覆盖 8 个簇的公开 API，再拆只会产生转发壳；记录为例外，不下放可选依赖 |

**不拆的部分与理由**

1. `list`/`getByCode`/`getPublishedByCode`/`getById`/`mapByIds`：一个读模型，改一处就是为了一个需求，拆开只会增加跳转。
2. `saveDraft`/`update`/`publish`/`disable`/`softDelete`：表单定义聚合的**唯一写入口**，共享事务边界与 `version` 乐观锁语义。
3. `validateStatus` 与 `STATUSES`：状态机取值，属于 B 簇。
4. `OptionRuntimeService`：已有独立类，不在本次范围（它的 `validateBinding`/`validateValue` 是外部契约）。

## 8. 实施记录（逐刀，每刀编译 + 测试）

| 刀 | 产物 | 原类行数（`wc -l`） |
| --- | --- | --- |
| 起点 | — | 1403 |
| 1 | `FormSchemaJson` / `FormVisibility` | 1277 |
| 2 | `FormSchemaParser` / `FormMatrixRules` | 986 |
| 3 | `FormSchemaValidator` | 881 |
| 4 | `FormSubmissionValidator` | 537 |
| 5 | `FormStarterProjector` / `FormStarterCanonicalizer` | 329 |
| 6 | 门面收尾（委托、注释、`package-info`、测试装配） | **326**（方法数 76 → **25**） |

每刀都执行 `mvn -B -q compile`，全部通过后再动下一刀。

**逐字核对**（脚本按方法名比对 HEAD 版本与新位置，忽略修饰符/空白/协作者前缀）：

| 分类 | 数量 | 说明 |
| --- | --- | --- |
| 保留在门面且逐字一致 | 11 | `list`(4 参)/`getByCode`/`getPublishedByCode`/`getById`/`mapByIds`/`saveDraft`/`update`/`publish`/`disable`/`softDelete`/`validateStatus` |
| 搬迁到 `form/schema` 且逐字一致 | 49 | 含两个 `validateNodeValue` 重载、矩阵全套、环检测、投影、规范化 |
| 门面一行委托 | 13 | 公开 API，调用方在拆分边界外（见 §2.1） |
| 有意差异 | 2 处 | 见下 |

有意差异（必须解释，不是顺手改）：

1. **`list` 的 6 参重载**：原来的 `normalized(keyword)` 内联成
   `keyword == null || keyword.isBlank() ? null : keyword.trim()`。
   原因：门面方法数是 25/25 顶格，留不下 `normalized` 这个私有方法。语义逐字等价。
2. **`isEmpty`**：从 `private boolean` 改为 `public static boolean`（`FormVisibility`）。
   搬出去后提交校验与可见性求值都要用同一份"空"的定义，只能公开；实现一字未改。

**验证结果**

| 项 | 结果 |
| --- | --- |
| `mvn -B compile` | 通过 |
| `mvn -B test -Dtest='FormDefinition*'` | 46 passed（原 44 + 新增 2） |
| 全量（排除 PG 事务与契约快照） | 658 passed，0 failed / 0 error |
| `node scripts/health-metrics.mjs` | 退出码 0；H7 后端超标类 15→14、超标方法类 18→17、最大类 1403→1277 行 |
| `git diff --stat -- frontend/config/antflow-api.json` | 空 |
| 新增测试 | ①矩阵运行态行 id 与配置态撞车必须拒绝；②`displayCondition` 的 `empty`/`notEmpty` 操作符。两条都是搬迁前没有覆盖、可稳定断言的行为 |
