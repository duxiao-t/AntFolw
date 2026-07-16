# AntFlow 钉钉式流程改造 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 antflow 的流程模块从"React Flow 自由连线图 + 仅或签 + user/role 审批人"，改造成 wflow / 钉钉式的**递归节点树**：发起人 → 审批 → 抄送 → 条件分支，审批人支持 指定成员 / 角色 / 第 N 级主管 / 发起人自选，多人支持 会签(AND) / 或签(OR)。

**Architecture:** 参考 `D:\code\wflow-master` 的 `ProcessTree.vue` + `DefaultNodeProps.js`。流程定义存成**单棵递归树** `process`（每个业务节点有唯一 `children`；`CONDITIONS` 节点用 `branchs[]` 表示并列分支，分支尾部用 `EMPTY` 占位，`children` 表示分支合流后的后续节点）。后端引擎从"按 edges 查下一节点"改为"沿树 `children`/`branchs` 递归求解下一批可执行节点"，CC 非阻塞、CONDITIONS 按表单数据选分支、审批节点按 mode 决定推进。前端用递归 TSX 组件重写设计器，彻底移除 `@xyflow/react`。

**Tech Stack:** 后端 Spring Boot 3 / Java 17 / MyBatis-Plus / Jackson `JsonNode` / Flyway / JUnit5 + Mockito；前端 React 18 / Umi Max 4 / antd 6 / zustand。

**首期范围（已与用户确认 = 核心子集）：** 节点 `ROOT / APPROVAL / CC / CONDITIONS`；审批人 `ASSIGN_USER / ROLE / LEADER(第N级) / SELF / SELF_SELECT`；多人 `AND(会签) / OR(或签)`；审批人为空 `TO_PASS / TO_REFUSE`。
**明确排除（二期）：** 并行分支 `CONCURRENTS`、延时 `DELAY`、触发器 `TRIGGER`、连续多级主管 `LEADER_TOP`、依次会签 `NEXT`、超时 `timeLimit`、驳回到指定节点 `refuse.TO_NODE`、节点级表单字段权限 `formPerms`、转交 `TO_ADMIN`/加签。这些都以"新增一种 NodeHandler / assignedType 分支"的方式增量加入，不改本计划确立的树模型。

---

## 关键数据模型（本计划的契约，后续任务都引用它）

### 流程树 `process`（存 `t_process_definition.process` JSONB）

```jsonc
{
  "id": "root",
  "type": "ROOT",                 // ROOT | APPROVAL | CC | CONDITIONS | CONDITION | EMPTY
  "name": "发起人",
  "props": { "assignedUser": [] },// ROOT 记录可发起人（空=全员）
  "children": {                   // 唯一后继；到末端时为 null
    "id": "node_123",
    "type": "APPROVAL",
    "name": "审批人",
    "props": { /* APPROVAL_PROPS，见下 */ },
    "children": { /* ... */ }
  }
}
```

分支节点结构（`CONDITIONS`）：

```jsonc
{
  "id": "node_cond",
  "type": "CONDITIONS",
  "name": "条件分支",
  "branchs": [
    { "id": "b1", "type": "CONDITION", "name": "条件1",
      "props": { /* CONDITION_PROPS */ }, "children": { /* 分支内链，或 null */ } },
    { "id": "b2", "type": "CONDITION", "name": "默认条件",
      "props": { "isDefault": true }, "children": null }
  ],
  "children": { /* 分支合流后的后续节点，或 null */ }
}
```

> **不再使用** `t_process_definition.nodes` 与 `edges` 两个平面数组。

### 各节点 `props`（核心子集）

```jsonc
// APPROVAL_PROPS
{
  "assignedType": "ASSIGN_USER",        // ASSIGN_USER | ROLE | LEADER | SELF | SELF_SELECT
  "mode": "OR",                         // AND(会签，全部通过) | OR(或签，一人通过)
  "assignedUser": [1, 2],               // assignedType=ASSIGN_USER 时用
  "role": [3],                          // assignedType=ROLE 时用
  "leader": { "level": 1 },             // assignedType=LEADER；1=直接主管
  "selfSelect": { "multiple": false },  // assignedType=SELF_SELECT；单选/多选
  "nobody": { "handler": "TO_PASS" }    // 审批人为空：TO_PASS 自动通过 | TO_REFUSE 自动驳回
}

// CC_PROPS
{ "assignedUser": [4], "role": [] }

// CONDITION_PROPS
{
  "isDefault": false,                   // 默认分支（其它都不匹配时命中），默认分支忽略 groups
  "groupsType": "OR",                   // 组间关系 OR | AND
  "groups": [
    { "groupType": "AND",               // 组内关系 OR | AND
      "conditions": [
        { "field": "<表单字段的 node.id>", "operator": ">=", "value": 5000 }
      ] }
  ]
}
```

**operator 取值：** `==` `!=` `>` `>=` `<` `<=` `in`（value 为数组，字段值∈数组）`contains`（字段值为数组/字符串且包含 value）。
**表单数据形状：** `t_form_data.data = { "<node.id>": value, ... }`（前端 `FormRenderer` 以 `node.id` 为键，见 `frontend/src/components/FormRenderer/FormRenderer.tsx:17`）。条件里的 `field` 即某表单字段的 `node.id`。

### 运行时约定

- `start` 时前端上送 `{ formCode, data, selfSelected }`，其中 `selfSelected: { "<nodeId>": [userId,...] }` 提供所有 `SELF_SELECT` 节点的自选审批人。
- `t_task.approval_mode` 复用为存 `AND` / `OR`（现有列，无需 DDL）。
- CC 节点：为每个抄送人写一条 `status='CC'` 的 `t_task`（不阻塞），并立即推进到 `children`。
- 会签(AND)：节点内所有 `PENDING` 任务都 `APPROVED` 后才推进；任一 `REJECT` → 实例按驳回规则终止。
- 或签(OR)：首个 `APPROVED` 推进并把同节点其它 `PENDING` 置 `SKIPPED`（沿用现有逻辑）。

---

## 文件结构总览

**后端 新增：**
- `backend/src/main/resources/db/migration/V4__process_tree.sql` — 加 `process JSONB`。
- `backend/src/main/java/com/antflow/engine/tree/ProcessTreeNav.java` — 树遍历工具（求下一节点、判断分支/空节点）。
- `backend/src/main/java/com/antflow/engine/condition/ConditionEvaluator.java` — 条件求值。
- 测试：`ConditionEvaluatorTest`、`ProcessEngineTreeTest`（Mockito）、扩展 `AssigneeResolverTest`、`ProcessDefinitionServiceValidationTest`。

**后端 修改：**
- `process/ProcessDefinition.java` — `nodes`/`edges` → `process`。
- `process/ProcessDefinitionService.java` — 保存/校验签名与逻辑改树。
- `process/ProcessDefinitionController.java` — `SaveBody` 改 `process`。
- `engine/resolver/AssigneeResolver.java` — 增 SELF / SELF_SELECT / LEADER。
- `engine/resolver/AssigneeSpec.java` — 承载新字段。
- `engine/dto/StartCmd.java` — 增 `selfSelected`。
- `engine/ProcessEngine.java` — start/approve/reject 改树遍历 + AND/OR + CC + nobody。
- `org/DepartmentMapper.java` / `UserMapper.java` — 主管查询（如缺）。

**前端 新增：**
- `frontend/src/pages/designer/process/useProcessDesignerStore.ts` — 流程树 zustand store + 树操作。
- `frontend/src/pages/designer/process/ProcessTree.tsx` — 递归渲染组件。
- `frontend/src/pages/designer/process/nodes/{RootNode,ApprovalNode,CcNode,ConditionsNode}.tsx` — 节点卡片。
- `frontend/src/pages/designer/process/config/{RootNodeConfig,ApprovalNodeConfig,CcNodeConfig,ConditionNodeConfig}.tsx` — 配置面板。
- `frontend/src/pages/designer/process/types.ts` — 树节点 TS 类型 + 默认 props。

**前端 修改：**
- `frontend/src/pages/designer/process/ProcessDesigner.tsx` — 用 ProcessTree 替换 React Flow，保存 `{process}`。
- `frontend/src/pages/runtime/form/Fill.tsx` — 自选审批人弹窗 + 上送 `selfSelected`。
- `frontend/src/pages/proc/Detail.tsx` — 展示抄送/条件路径（如需要）。
- `frontend/package.json` — 移除 `@xyflow/react`。

**前端 删除：** 旧 `ApprovalNodeComponent.tsx`、旧 `ApprovalNodeConfig.tsx`（被新目录结构取代）。

---

## 阶段一：后端流程树数据模型与校验

### Task 1: 迁移与实体 —— `nodes/edges` → `process`

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__process_tree.sql`
- Modify: `backend/src/main/java/com/antflow/process/ProcessDefinition.java`
- Modify: `backend/src/main/java/com/antflow/process/ProcessDefinitionController.java`

- [ ] **Step 1: 写迁移脚本**

```sql
-- V4__process_tree.sql
-- 钉钉式递归树取代 nodes[]+edges[] 平面图。MVP 无历史流程数据，直接加列并弃用旧列。
ALTER TABLE t_process_definition ADD COLUMN process JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE t_process_definition ALTER COLUMN nodes DROP NOT NULL;
ALTER TABLE t_process_definition ALTER COLUMN edges DROP NOT NULL;
COMMENT ON COLUMN t_process_definition.process IS '钉钉式流程树：ROOT 根，children 单链，CONDITIONS.branchs 分支';
```

- [ ] **Step 2: 实体改字段**

`ProcessDefinition.java` 把 `nodes`/`edges` 两个字段替换为：

```java
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String process;     // JSONB 流程树
```

（删除 `private String nodes;` 与 `private String edges;` 及其注解）

- [ ] **Step 3: 控制器 SaveBody 改造**

`ProcessDefinitionController.java` 末尾 record 改为：

```java
    public record SaveBody(Long id, Long formDefId, Object process) {}
```

`save()` 内调用改为 `service.saveOrUpdateDraft(body.id(), body.formDefId(), body.process(), p.userId());`

- [ ] **Step 4: 编译**

Run: `cd backend && mvn -q -o compile`
Expected: 编译期在 `ProcessDefinitionService` 处报错（签名未改）——下一 Task 修复。可先跳过，进入 Task 2 一起编译。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V4__process_tree.sql \
        backend/src/main/java/com/antflow/process/ProcessDefinition.java \
        backend/src/main/java/com/antflow/process/ProcessDefinitionController.java
git commit -m "重构(后端): 流程定义改为钉钉式递归树 process 字段，弃用 nodes/edges"
```

---

### Task 2: 树遍历工具 `ProcessTreeNav`

**Files:**
- Create: `backend/src/main/java/com/antflow/engine/tree/ProcessTreeNav.java`
- Test: `backend/src/test/java/com/antflow/engine/tree/ProcessTreeNavTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.antflow.engine.tree;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessTreeNavTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void childrenOf_returnsNullWhenNoChild() throws Exception {
        var root = om.readTree("""
            {"id":"root","type":"ROOT","children":null}""");
        assertNull(ProcessTreeNav.childrenOf(root));
    }

    @Test
    void isBranch_true_forConditions() throws Exception {
        var n = om.readTree("""{"id":"c","type":"CONDITIONS","branchs":[]}""");
        assertTrue(ProcessTreeNav.isBranch(n));
    }

    @Test
    void findById_walksChildrenAndBranchs() throws Exception {
        var tree = om.readTree("""
          {"id":"root","type":"ROOT","children":
            {"id":"cond","type":"CONDITIONS",
             "branchs":[{"id":"b1","type":"CONDITION","children":
                {"id":"a1","type":"APPROVAL","children":null}}],
             "children":{"id":"a2","type":"APPROVAL","children":null}}}""");
        assertEquals("a1", ProcessTreeNav.findById(tree, "a1").path("id").asText());
        assertEquals("a2", ProcessTreeNav.findById(tree, "a2").path("id").asText());
        assertNull(ProcessTreeNav.findById(tree, "nope"));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd backend && mvn -q -o test -Dtest=ProcessTreeNavTest`
Expected: 编译失败 / 找不到 `ProcessTreeNav`。

- [ ] **Step 3: 实现**

```java
package com.antflow.engine.tree;

import com.fasterxml.jackson.databind.JsonNode;

/** 钉钉式流程树的只读遍历工具。节点约定见实施计划"关键数据模型"。 */
public final class ProcessTreeNav {
    private ProcessTreeNav() {}

    public static boolean isBranch(JsonNode n) {
        return n != null && "CONDITIONS".equals(n.path("type").asText());
    }

    public static boolean isEmpty(JsonNode n) {
        return n != null && "EMPTY".equals(n.path("type").asText());
    }

    /** 返回节点的唯一后继；无后继返回 null。 */
    public static JsonNode childrenOf(JsonNode n) {
        if (n == null) return null;
        JsonNode c = n.get("children");
        return (c == null || c.isNull() || !c.has("id")) ? null : c;
    }

    /** 在整棵树内按 id 查找节点（深度优先，含 branchs）。找不到返回 null。 */
    public static JsonNode findById(JsonNode node, String id) {
        if (node == null || node.isNull() || !node.has("id")) return null;
        if (id.equals(node.path("id").asText())) return node;
        if (isBranch(node)) {
            for (JsonNode b : node.withArray("branchs")) {
                JsonNode hit = findById(b, id);
                if (hit != null) return hit;
            }
        }
        return findById(node.get("children"), id);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd backend && mvn -q -o test -Dtest=ProcessTreeNavTest`
Expected: PASS（3 tests）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/antflow/engine/tree/ProcessTreeNav.java \
        backend/src/test/java/com/antflow/engine/tree/ProcessTreeNavTest.java
git commit -m "功能(后端): 新增流程树遍历工具 ProcessTreeNav"
```

---

### Task 3: 流程树发布校验

**Files:**
- Modify: `backend/src/main/java/com/antflow/process/ProcessDefinitionService.java`
- Test: `backend/src/test/java/com/antflow/process/ProcessDefinitionServiceValidationTest.java`（替换旧的 linear-flow 用例）

- [ ] **Step 1: 写失败测试**

替换该测试文件为对 `validateProcessTree(String processJson)` 的用例：

```java
    @Test
    void validate_rejects_approval_without_assignee() {
        String tree = """
          {"id":"root","type":"ROOT","children":
            {"id":"a1","type":"APPROVAL","props":{"assignedType":"ASSIGN_USER","assignedUser":[]},
             "children":null}}""";
        var ex = assertThrows(BizException.class, () -> svc.validateProcessTree(tree));
        assertEquals("BAD_FLOW", ex.getCode());
    }

    @Test
    void validate_rejects_conditions_without_default_branch() {
        String tree = """
          {"id":"root","type":"ROOT","children":
            {"id":"c","type":"CONDITIONS","children":null,
             "branchs":[{"id":"b1","type":"CONDITION",
               "props":{"isDefault":false,"groupsType":"OR","groups":[]},"children":null}]}}""";
        var ex = assertThrows(BizException.class, () -> svc.validateProcessTree(tree));
        assertEquals("BAD_FLOW", ex.getCode());
    }

    @Test
    void validate_accepts_wellformed_tree() {
        String tree = """
          {"id":"root","type":"ROOT","children":
            {"id":"a1","type":"APPROVAL","props":{"assignedType":"ASSIGN_USER","assignedUser":[1]},
             "children":null}}""";
        assertDoesNotThrow(() -> svc.validateProcessTree(tree));
    }
```

（`svc` 用 `new ProcessDefinitionService(null, null, new ObjectMapper())` 构造，校验方法不依赖 mapper。）

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn -q -o test -Dtest=ProcessDefinitionServiceValidationTest`
Expected: 找不到 `validateProcessTree`。

- [ ] **Step 3: 实现校验 + 改造保存/发布签名**

在 `ProcessDefinitionService` 中：删除 `validateLinearFlow`；`saveOrUpdateDraft` 签名改为 `(Long id, Long formDefId, Object process, Long userId)`，内部 `pd.setProcess(writeJson(process))`；`publish` 中调用 `validateProcessTree(pd.getProcess())`。新增：

```java
    /** 树校验：ROOT 唯一根；APPROVAL 审批人配置齐全；CONDITIONS 至少 1 分支且含默认分支；类型合法。 */
    void validateProcessTree(String processJson) {
        try {
            JsonNode root = json.readTree(processJson == null ? "{}" : processJson);
            if (!"ROOT".equals(root.path("type").asText())) {
                throw new BizException("BAD_FLOW", "流程必须以 ROOT 节点开始");
            }
            walk(root);
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private void walk(JsonNode n) {
        if (n == null || n.isNull() || !n.has("id")) return;
        String type = n.path("type").asText();
        switch (type) {
            case "ROOT", "CC", "EMPTY" -> {}
            case "APPROVAL" -> validateApproval(n);
            case "CONDITIONS" -> {
                JsonNode branchs = n.path("branchs");
                if (!branchs.isArray() || branchs.size() < 1) {
                    throw new BizException("BAD_FLOW", "条件分支至少需要 1 个分支");
                }
                boolean hasDefault = false;
                for (JsonNode b : branchs) {
                    if (b.path("props").path("isDefault").asBoolean(false)) hasDefault = true;
                    walk(b.path("children"));
                }
                if (!hasDefault) {
                    throw new BizException("BAD_FLOW", "条件分支必须包含一个默认分支");
                }
            }
            case "CONDITION" -> {}   // 由 CONDITIONS 分支体校验
            default -> throw new BizException("BAD_NODE_TYPE", "未知节点类型: " + type);
        }
        walk(n.path("children"));
    }

    private void validateApproval(JsonNode n) {
        JsonNode p = n.path("props");
        String at = p.path("assignedType").asText();
        boolean empty = switch (at) {
            case "ASSIGN_USER" -> p.path("assignedUser").size() == 0;
            case "ROLE" -> p.path("role").size() == 0;
            case "LEADER", "SELF", "SELF_SELECT" -> false;  // 运行时解析
            default -> true;
        };
        if (empty) throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText() + " 未配置审批人");
    }
```

> `walk` 对 `n.path("children")` 递归时，若为缺省/`null` 会得到 `MissingNode`，`walk` 首行 `!n.has("id")` 即返回，安全。

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn -q -o test -Dtest=ProcessDefinitionServiceValidationTest`
Expected: PASS（3 tests）。

- [ ] **Step 5: 全量编译**

Run: `cd backend && mvn -q -o compile`
Expected: 仅 `ProcessEngine` 因引用旧 `getNodes()/getEdges()` 报错（阶段三修复）。若要保持每步可编译，可临时在 `ProcessEngine` 里把 `pd.getNodes()/getEdges()` 用法注释掉并 `throw new UnsupportedOperationException()`，阶段三重写。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/antflow/process/ProcessDefinitionService.java \
        backend/src/test/java/com/antflow/process/ProcessDefinitionServiceValidationTest.java
git commit -m "重构(后端): 流程发布校验改为树校验 validateProcessTree"
```

---

## 阶段二：审批人解析扩展 + 条件求值（纯逻辑 TDD）

### Task 4: AssigneeSpec / StartCmd 扩展

**Files:**
- Modify: `backend/src/main/java/com/antflow/engine/resolver/AssigneeSpec.java`
- Modify: `backend/src/main/java/com/antflow/engine/dto/StartCmd.java`

- [ ] **Step 1: 改 AssigneeSpec 承载新字段**

现状是 `record AssigneeSpec(String type, List<Object> ids)`。改为：

```java
package com.antflow.engine.resolver;

import java.util.List;

/**
 * 审批人解析输入。
 * @param type ASSIGN_USER | ROLE | LEADER | SELF | SELF_SELECT
 * @param ids  ASSIGN_USER→用户id；ROLE→角色id
 * @param leaderLevel LEADER 的层级（1=直接主管）
 * @param starterId   发起人（SELF、LEADER 起点）
 * @param selfSelected SELF_SELECT 时该节点上发起人已选的用户
 */
public record AssigneeSpec(String type, List<Long> ids, int leaderLevel,
                           Long starterId, List<Long> selfSelected) {
    public static AssigneeSpec of(String type, List<Long> ids) {
        return new AssigneeSpec(type, ids, 1, null, List.of());
    }
}
```

- [ ] **Step 2: StartCmd 增 selfSelected**

```java
package com.antflow.engine.dto;

import java.util.List;
import java.util.Map;

public record StartCmd(String formCode, Object data,
                       Map<String, List<Long>> selfSelected) {}
```

- [ ] **Step 3: 编译（允许 ProcessEngine 暂时报错）** — 见 Task 3 Step 5 处理方式。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/antflow/engine/resolver/AssigneeSpec.java \
        backend/src/main/java/com/antflow/engine/dto/StartCmd.java
git commit -m "重构(后端): AssigneeSpec/StartCmd 支持主管层级与发起人自选"
```

---

### Task 5: AssigneeResolver 增 SELF / SELF_SELECT / LEADER

**Files:**
- Modify: `backend/src/main/java/com/antflow/engine/resolver/AssigneeResolver.java`
- Modify: `backend/src/main/java/com/antflow/org/DepartmentMapper.java`（如需按 id 取部门）
- Test: `backend/src/test/java/com/antflow/engine/AssigneeResolverTest.java`（扩展）

- [ ] **Step 1: 写失败测试**（在现有 `AssigneeResolverTest` 追加，沿用其现有 Mockito 风格）

```java
    @Test
    void resolve_self_returnsStarter() {
        var spec = new AssigneeSpec("SELF", List.of(), 1, 42L, List.of());
        assertEquals(List.of(42L), resolver.resolve("n1", spec));
    }

    @Test
    void resolve_selfSelect_returnsChosen() {
        var spec = new AssigneeSpec("SELF_SELECT", List.of(), 1, 42L, List.of(7L, 8L));
        assertEquals(List.of(7L, 8L), resolver.resolve("n1", spec));
    }

    @Test
    void resolve_selfSelect_emptyThrows() {
        var spec = new AssigneeSpec("SELF_SELECT", List.of(), 1, 42L, List.of());
        assertThrows(NoAssigneeFoundException.class, () -> resolver.resolve("n1", spec));
    }

    @Test
    void resolve_leader_level1_usesDeptLeader() {
        // 发起人 42 属部门 D(leaderId=99)；第1级主管应为 99
        var starter = new User(); starter.setId(42L); starter.setDeptId(10L);
        var dept = new Department(); dept.setId(10L); dept.setLeaderId(99L); dept.setParentId(null);
        var leaderUser = new User(); leaderUser.setId(99L); leaderUser.setStatus("ACTIVE");
        when(userMapper.selectById(42L)).thenReturn(starter);
        when(deptMapper.selectById(10L)).thenReturn(dept);
        when(userMapper.selectById(99L)).thenReturn(leaderUser);

        var spec = new AssigneeSpec("LEADER", List.of(), 1, 42L, List.of());
        assertEquals(List.of(99L), resolver.resolve("n1", spec));
    }
```

（测试类需 `@Mock DepartmentMapper deptMapper;` 并在构造 resolver 时注入。）

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn -q -o test -Dtest=AssigneeResolverTest`
Expected: 编译失败 / 断言失败。

- [ ] **Step 3: 实现**

`AssigneeResolver` 增加构造依赖 `DepartmentMapper deptMapper;`，`resolve` 的 switch 增加分支，并新增方法：

```java
    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        return switch (spec.type()) {
            case "ASSIGN_USER" -> resolveUsers(nodeId, spec.ids());
            case "ROLE"        -> resolveRoles(nodeId, spec.ids());
            case "LEADER"      -> resolveLeader(nodeId, spec.starterId(), spec.leaderLevel());
            case "SELF"        -> List.of(requireStarter(nodeId, spec.starterId()));
            case "SELF_SELECT" -> requireNonEmpty(nodeId, spec.selfSelected());
            default -> throw new IllegalArgumentException("unknown assignee type: " + spec.type());
        };
    }

    private Long requireStarter(String nodeId, Long starterId) {
        if (starterId == null) throw new NoAssigneeFoundException(nodeId, "no starter");
        return starterId;
    }

    private List<Long> requireNonEmpty(String nodeId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new NoAssigneeFoundException(nodeId, "self-select empty");
        return ids;
    }

    /** 从发起人所在部门向上走 level 级，取该级部门的 leaderId。 */
    private List<Long> resolveLeader(String nodeId, Long starterId, int level) {
        if (starterId == null) throw new NoAssigneeFoundException(nodeId, "no starter for leader");
        User u = userMapper.selectById(starterId);
        if (u == null || u.getDeptId() == null) throw new NoAssigneeFoundException(nodeId, "starter has no dept");
        Department dept = deptMapper.selectById(u.getDeptId());
        for (int i = 1; i < level && dept != null; i++) {
            dept = dept.getParentId() == null ? null : deptMapper.selectById(dept.getParentId());
        }
        if (dept == null || dept.getLeaderId() == null) {
            throw new NoAssigneeFoundException(nodeId, "no leader at level " + level);
        }
        User leader = userMapper.selectById(dept.getLeaderId());
        if (leader == null || !"ACTIVE".equals(leader.getStatus())) {
            throw new NoAssigneeFoundException(nodeId, "leader inactive");
        }
        return List.of(leader.getId());
    }
```

`resolveUsers/resolveRoles` 参数从 `AssigneeSpec` 改为直接收 `List<Long> ids`（去掉旧的 `dept_leader` throw 分支）。

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn -q -o test -Dtest=AssigneeResolverTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/antflow/engine/resolver/AssigneeResolver.java \
        backend/src/test/java/com/antflow/engine/AssigneeResolverTest.java
git commit -m "功能(后端): 审批人解析支持发起人本人/自选/第N级主管"
```

---

### Task 6: 条件求值 `ConditionEvaluator`

**Files:**
- Create: `backend/src/main/java/com/antflow/engine/condition/ConditionEvaluator.java`
- Test: `backend/src/test/java/com/antflow/engine/condition/ConditionEvaluatorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.antflow.engine.condition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {
    private final ObjectMapper om = new ObjectMapper();
    private final ConditionEvaluator ev = new ConditionEvaluator();

    @Test
    void gte_number_matches() throws Exception {
        var props = om.readTree("""
          {"isDefault":false,"groupsType":"OR",
           "groups":[{"groupType":"AND","conditions":[
             {"field":"amount","operator":">=","value":5000}]}]}""");
        var data = om.readTree("""{"amount":6000}""");
        assertTrue(ev.matches(props, data));
    }

    @Test
    void gte_number_notMatch() throws Exception {
        var props = om.readTree("""
          {"groups":[{"groupType":"AND","conditions":[
             {"field":"amount","operator":">=","value":5000}]}],"groupsType":"OR"}""");
        assertFalse(ev.matches(props, om.readTree("""{"amount":100}""")));
    }

    @Test
    void isDefault_alwaysMatches() throws Exception {
        assertTrue(ev.matches(om.readTree("""{"isDefault":true}"""), om.readTree("{}")));
    }

    @Test
    void in_operator() throws Exception {
        var props = om.readTree("""
          {"groupsType":"OR","groups":[{"groupType":"AND","conditions":[
             {"field":"city","operator":"in","value":["BJ","SH"]}]}]}""");
        assertTrue(ev.matches(props, om.readTree("""{"city":"SH"}""")));
        assertFalse(ev.matches(props, om.readTree("""{"city":"GZ"}""")));
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn -q -o test -Dtest=ConditionEvaluatorTest`
Expected: 找不到 `ConditionEvaluator`。

- [ ] **Step 3: 实现**

```java
package com.antflow.engine.condition;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

/** 对 CONDITION_PROPS 相对表单数据求值。见实施计划"关键数据模型"。 */
@Component
public class ConditionEvaluator {

    public boolean matches(JsonNode condProps, JsonNode formData) {
        if (condProps.path("isDefault").asBoolean(false)) return true;
        JsonNode groups = condProps.path("groups");
        if (!groups.isArray() || groups.size() == 0) return false;
        boolean orGroups = !"AND".equals(condProps.path("groupsType").asText("OR"));
        boolean acc = !orGroups;   // AND 起点 true；OR 起点 false
        for (JsonNode g : groups) {
            boolean gv = evalGroup(g, formData);
            acc = orGroups ? (acc || gv) : (acc && gv);
        }
        return acc;
    }

    private boolean evalGroup(JsonNode group, JsonNode formData) {
        JsonNode conds = group.path("conditions");
        boolean orInner = !"AND".equals(group.path("groupType").asText("AND"));
        boolean acc = !orInner;
        if (!conds.isArray() || conds.size() == 0) return true;
        for (JsonNode c : conds) {
            boolean cv = evalOne(c, formData);
            acc = orInner ? (acc || cv) : (acc && cv);
        }
        return acc;
    }

    private boolean evalOne(JsonNode c, JsonNode formData) {
        String field = c.path("field").asText();
        String op = c.path("operator").asText();
        JsonNode expected = c.path("value");
        JsonNode actual = formData.path(field);
        return switch (op) {
            case "==" -> nodeEquals(actual, expected);
            case "!=" -> !nodeEquals(actual, expected);
            case ">"  -> cmp(actual, expected) > 0;
            case ">=" -> cmp(actual, expected) >= 0;
            case "<"  -> cmp(actual, expected) < 0;
            case "<=" -> cmp(actual, expected) <= 0;
            case "in" -> arrayContains(expected, actual);
            case "contains" -> arrayContains(actual, expected) || actual.asText().contains(expected.asText());
            default -> false;
        };
    }

    private boolean nodeEquals(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return cmp(a, b) == 0;
        return a.asText().equals(b.asText());
    }

    private int cmp(JsonNode a, JsonNode b) {
        try {
            return new BigDecimal(a.asText()).compareTo(new BigDecimal(b.asText()));
        } catch (NumberFormatException e) {
            return a.asText().compareTo(b.asText());
        }
    }

    private boolean arrayContains(JsonNode arr, JsonNode v) {
        if (!arr.isArray()) return false;
        for (JsonNode x : arr) if (nodeEquals(x, v)) return true;
        return false;
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn -q -o test -Dtest=ConditionEvaluatorTest`
Expected: PASS（4 tests）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/antflow/engine/condition/ConditionEvaluator.java \
        backend/src/test/java/com/antflow/engine/condition/ConditionEvaluatorTest.java
git commit -m "功能(后端): 新增条件分支求值 ConditionEvaluator"
```

---

## 阶段三：引擎改为递归树遍历

### Task 7: ProcessEngine 重写 —— start / advance / CC / 条件

**Files:**
- Modify: `backend/src/main/java/com/antflow/engine/ProcessEngine.java`
- Test: `backend/src/test/java/com/antflow/engine/ProcessEngineTreeTest.java`（Mockito，验证遍历/建任务/条件路由）

**核心算法（引擎从某节点向后"落地"到下一批任务）：**

```
resolveAndLand(pd, pi, formData, fromNode):
    node = childrenOf(fromNode)
    loop:
        if node == null: 实例 APPROVED; return []
        switch node.type:
          EMPTY:      node = childrenOf(node); continue
          CC:         为每个抄送人建 status='CC' 任务(不阻塞);
                      记 CC history; node = childrenOf(node); continue
          CONDITIONS: branch = 选第一个 matches 的 CONDITION (否则 isDefault);
                      若 branch 无匹配且无默认 → BizException BAD_FLOW;
                      inner = childrenOf(branch)
                      若 inner == null: node = childrenOf(node)  // 空分支→合流后续
                      else: node = inner
                      continue
          APPROVAL:   建 PENDING 任务(按 assignee + mode); 处理 nobody 空审批人;
                      pi.currentNodeId = node.id; return newTaskIds
          default:    BizException BAD_NODE_TYPE
```

- 空审批人（`NoAssigneeFoundException`）时按 `props.nobody.handler`：
  - `TO_PASS`：不建任务，记 `AUTO_PASS` history，`node = childrenOf(node)` 继续；
  - `TO_REFUSE`：实例 `REJECTED` 并 return。
- `approve` 完成一个审批任务后：
  - `mode=OR`：置同节点其它 `PENDING` 为 `SKIPPED`，`resolveAndLand(... fromNode=当前APPROVAL节点)`；
  - `mode=AND`：若同节点仍有 `PENDING`（排除自己）→ 不推进；否则 `resolveAndLand(...)`。
- `reject`：置同节点 `PENDING` `SKIPPED`，实例 `REJECTED`（首期驳回规则固定为到结束）。

- [ ] **Step 1: 写失败测试**（Mockito 模拟 mapper，验证纯遍历逻辑；`ObjectMapper` 真实）

```java
// 关键用例（示意，按现有测试注入风格补全 @Mock）：
// 1) start_singleApproval_createsOnePendingTask
// 2) or_sign_firstApproveAdvancesAndSkipsSiblings
// 3) and_sign_waitsForAllBeforeAdvance
// 4) conditions_routesByAmount (amount>=5000 走分支A，否则默认分支)
// 5) cc_isNonBlocking_andContinues
// 6) approval_emptyAssignee_TO_PASS_skips
```

每个用例构造一个 `ProcessDefinition`，其 `process` 为对应 JSON 字符串；`when(processDefinitionService.getById(...)).thenReturn(pd)`；`when(taskMapper.insert(any())).thenAnswer(inv -> { ((TaskEntity)inv.getArgument(0)).setId(seq++); return 1; })`；断言 `taskMapper` 收到的 insert 次数/字段与 instance 状态。

> 完整用例代码在实现时按此结构补齐（每用例约 20–30 行）。因依赖较多 mapper，建议每个 `when(...)` 只桩必要调用。

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn -q -o test -Dtest=ProcessEngineTreeTest`
Expected: FAIL。

- [ ] **Step 3: 实现 —— 重写 ProcessEngine**

要点：
- 注入新增依赖 `ConditionEvaluator conditionEvaluator;`。
- `start`：读 `pd.getProcess()` → `root`；`formData` = 解析 `cmd.data()`；`selfSelected = cmd.selfSelected()`；`resolveAndLand(pd, pi, formData, root)`。
- 用 `ProcessTreeNav.childrenOf` 遍历；用 `ProcessTreeNav.findById(root, task.nodeId)` 在 approve/reject 时定位当前节点。
- `parseAssignee(JsonNode approvalProps, Long starterId, Map selfSelected, String nodeId)` 构造 `AssigneeSpec`（含 leaderLevel = `props.leader.level`，selfSelected = `selfSelected.get(nodeId)`）。
- `mode` = `props.mode`（AND/OR）写入 `task.approvalMode`。
- 删除旧 `nextNodes(edges)` / `parseAssignee(JsonNode)` 旧版。

（本 Task 是本计划最大改动。实现时严格对照上文"核心算法"逐分支落地；每落地一个分支跑一次对应测试用例。）

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn -q -o test -Dtest=ProcessEngineTreeTest`
Expected: PASS（6 用例）。

- [ ] **Step 5: 全量测试 + 编译**

Run: `cd backend && mvn -q -o test`
Expected: 所有测试 PASS（原 16 + 新增）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/antflow/engine/ProcessEngine.java \
        backend/src/test/java/com/antflow/engine/ProcessEngineTreeTest.java
git commit -m "重构(后端): 审批引擎改为递归树遍历，支持抄送/条件分支/会签或签"
```

---

## 阶段四：前端流程设计器重写为钉钉式树

### Task 8: 流程树类型与 zustand store

**Files:**
- Create: `frontend/src/pages/designer/process/types.ts`
- Create: `frontend/src/pages/designer/process/useProcessDesignerStore.ts`

- [ ] **Step 1: 类型 + 默认 props**

```ts
// types.ts
export type NodeType = 'ROOT' | 'APPROVAL' | 'CC' | 'CONDITIONS' | 'CONDITION' | 'EMPTY';

export type TreeNode = {
  id: string;
  parentId?: string;
  type: NodeType;
  name?: string;
  props?: Record<string, any>;
  children?: TreeNode | null;
  branchs?: TreeNode[];        // 仅 CONDITIONS
};

export const APPROVAL_PROPS = () => ({
  assignedType: 'ASSIGN_USER', mode: 'OR',
  assignedUser: [], role: [], leader: { level: 1 },
  selfSelect: { multiple: false }, nobody: { handler: 'TO_PASS' },
});
export const CC_PROPS = () => ({ assignedUser: [], role: [] });
export const CONDITION_PROPS = () => ({
  isDefault: false, groupsType: 'OR',
  groups: [{ groupType: 'AND', conditions: [] }],
});
```

- [ ] **Step 2: store（镜像 wflow ProcessTree 的 insert/del/addBranch/move）**

```ts
// useProcessDesignerStore.ts
import { create } from 'zustand';
import { nanoid } from '@reduxjs/toolkit';
import type { TreeNode, NodeType } from './types';
import { APPROVAL_PROPS, CC_PROPS, CONDITION_PROPS } from './types';

const rid = () => 'node_' + nanoid(8);

type State = {
  process: TreeNode;
  selectedId: string | null;
  load(tree: TreeNode): void;
  select(id: string | null): void;
  insertAfter(parentId: string, type: NodeType): void;
  removeNode(id: string): void;
  addBranch(conditionsId: string): void;
  updateProps(id: string, props: any): void;
  updateName(id: string, name: string): void;
};

function freshRoot(): TreeNode {
  return { id: 'root', type: 'ROOT', name: '发起人', props: { assignedUser: [] }, children: null };
}

// 深度优先找节点并对其执行 mutator（返回新树）
function mutate(node: TreeNode, id: string, fn: (n: TreeNode) => void): TreeNode {
  const clone: TreeNode = { ...node };
  if (clone.id === id) fn(clone);
  if (clone.branchs) clone.branchs = clone.branchs.map((b) => mutate(b, id, fn));
  if (clone.children) clone.children = mutate(clone.children, id, fn);
  return clone;
}

export const useProcessDesignerStore = create<State>((set) => ({
  process: freshRoot(),
  selectedId: null,
  load: (tree) => set({ process: tree ?? freshRoot(), selectedId: null }),
  select: (id) => set({ selectedId: id }),

  insertAfter: (parentId, type) =>
    set((s) => ({
      process: mutate(s.process, parentId, (parent) => {
        const after = parent.children ?? null;
        if (type === 'CONDITIONS') {
          const empty: TreeNode = { id: rid(), type: 'EMPTY', children: after };
          parent.children = {
            id: rid(), type: 'CONDITIONS', name: '条件分支', children: empty,
            branchs: [
              { id: rid(), type: 'CONDITION', name: '条件1', props: CONDITION_PROPS(), children: null },
              { id: rid(), type: 'CONDITION', name: '默认条件', props: { isDefault: true }, children: null },
            ],
          };
        } else {
          const props = type === 'APPROVAL' ? APPROVAL_PROPS() : CC_PROPS();
          const name = type === 'APPROVAL' ? '审批人' : '抄送人';
          parent.children = { id: rid(), type, name, props, children: after };
        }
      }),
    })),

  addBranch: (conditionsId) =>
    set((s) => ({
      process: mutate(s.process, conditionsId, (c) => {
        if ((c.branchs?.length ?? 0) >= 8) return;
        const idx = (c.branchs?.length ?? 0);
        c.branchs = [...(c.branchs ?? [])];
        // 新分支插在默认分支之前
        c.branchs.splice(Math.max(0, c.branchs.length - 1), 0, {
          id: rid(), type: 'CONDITION', name: '条件' + idx, props: CONDITION_PROPS(), children: null,
        });
      }),
    })),

  removeNode: (id) =>
    set((s) => ({ process: removeFromTree(s.process, id), selectedId: null })),

  updateProps: (id, props) => set((s) => ({ process: mutate(s.process, id, (n) => { n.props = props; }) })),
  updateName: (id, name) => set((s) => ({ process: mutate(s.process, id, (n) => { n.name = name; }) })),
}));

// 删除节点：把其 children 接到父节点（分支删除见 ConditionsNode 内的 removeBranch）
function removeFromTree(root: TreeNode, id: string): TreeNode {
  const walk = (n: TreeNode): TreeNode => {
    const c = { ...n };
    if (c.branchs) c.branchs = c.branchs.map(walk);
    if (c.children) {
      if (c.children.id === id) c.children = c.children.children ?? null;
      else c.children = walk(c.children);
    }
    return c;
  };
  return walk(root);
}
```

- [ ] **Step 3: 编译类型**

Run: `cd frontend && npm run tsc`
Expected: 无类型错误（store 未被引用时也应通过）。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/pages/designer/process/types.ts \
        frontend/src/pages/designer/process/useProcessDesignerStore.ts
git commit -m "功能(前端): 流程树类型与 zustand store（插入/删除/分支）"
```

---

### Task 9: 递归渲染组件 ProcessTree + 节点卡片

**Files:**
- Create: `frontend/src/pages/designer/process/ProcessTree.tsx`
- Create: `frontend/src/pages/designer/process/nodes/RootNode.tsx`
- Create: `frontend/src/pages/designer/process/nodes/ApprovalNode.tsx`
- Create: `frontend/src/pages/designer/process/nodes/CcNode.tsx`
- Create: `frontend/src/pages/designer/process/nodes/ConditionsNode.tsx`
- Create: `frontend/src/pages/designer/process/process-tree.less`

- [ ] **Step 1: 节点卡片（以 ApprovalNode 为例，其余同构）**

```tsx
// nodes/ApprovalNode.tsx
import { CloseOutlined } from '@ant-design/icons';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function ApprovalNode({ node }: { node: any }) {
  const select = useProcessDesignerStore((s) => s.select);
  const remove = useProcessDesignerStore((s) => s.removeNode);
  const p = node.props ?? {};
  const summary =
    p.assignedType === 'ASSIGN_USER' ? `指定成员 ${p.assignedUser?.length ?? 0} 人`
    : p.assignedType === 'ROLE' ? `角色 ${p.role?.length ?? 0} 个`
    : p.assignedType === 'LEADER' ? `第 ${p.leader?.level ?? 1} 级主管`
    : p.assignedType === 'SELF' ? '发起人本人'
    : '发起人自选';
  return (
    <div className="pt-node pt-node--approval" onClick={() => select(node.id)}>
      <div className="pt-node__title">
        {node.name || '审批人'}
        <CloseOutlined className="pt-node__del" onClick={(e) => { e.stopPropagation(); remove(node.id); }} />
      </div>
      <div className="pt-node__body">{summary}·{p.mode === 'AND' ? '会签' : '或签'}</div>
    </div>
  );
}
```

`RootNode`/`CcNode` 同结构（Root 不可删、无删除按钮；Cc 展示抄送人数）。

- [ ] **Step 2: 递归树组件（把 wflow `ProcessTree.vue` 的 render 翻成 TSX）**

```tsx
// ProcessTree.tsx
import './process-tree.less';
import { Popover, Button } from 'antd';
import { RootNode } from './nodes/RootNode';
import { ApprovalNode } from './nodes/ApprovalNode';
import { CcNode } from './nodes/CcNode';
import { ConditionsNode } from './nodes/ConditionsNode';
import { useProcessDesignerStore } from './useProcessDesignerStore';
import type { TreeNode } from './types';

function AddButton({ parentId }: { parentId: string }) {
  const insert = useProcessDesignerStore((s) => s.insertAfter);
  const menu = (
    <div className="pt-add-menu">
      <Button size="small" block onClick={() => insert(parentId, 'APPROVAL')}>审批人</Button>
      <Button size="small" block onClick={() => insert(parentId, 'CC')}>抄送人</Button>
      <Button size="small" block onClick={() => insert(parentId, 'CONDITIONS')}>条件分支</Button>
    </div>
  );
  return (
    <div className="pt-add">
      <Popover content={menu} trigger="click" placement="right">
        <button className="pt-add__btn">+</button>
      </Popover>
    </div>
  );
}

// 渲染"一个业务节点 + 其后的 + 按钮 + 递归 children"
export function NodeChain({ node }: { node: TreeNode }) {
  const card =
    node.type === 'ROOT' ? <RootNode node={node} />
    : node.type === 'APPROVAL' ? <ApprovalNode node={node} />
    : node.type === 'CC' ? <CcNode node={node} />
    : node.type === 'CONDITIONS' ? <ConditionsNode node={node} />
    : null;

  return (
    <div className="pt-chain">
      {card}
      {node.type !== 'CONDITIONS' && <AddButton parentId={node.id} />}
      {node.children && <NodeChain node={node.children} />}
    </div>
  );
}

export function ProcessTree() {
  const process = useProcessDesignerStore((s) => s.process);
  return (
    <div className="pt-root">
      <NodeChain node={process} />
      <div className="pt-end">流程结束</div>
    </div>
  );
}
```

- [ ] **Step 3: 分支节点组件（并列分支 + 每分支内嵌 NodeChain + 分支后 AddButton）**

```tsx
// nodes/ConditionsNode.tsx
import { Button } from 'antd';
import { NodeChain } from '../ProcessTree';
import { useProcessDesignerStore } from '../useProcessDesignerStore';
import type { TreeNode } from '../types';

export function ConditionsNode({ node }: { node: TreeNode }) {
  const select = useProcessDesignerStore((s) => s.select);
  const addBranch = useProcessDesignerStore((s) => s.addBranch);
  return (
    <div className="pt-conditions">
      <div className="pt-conditions__head">
        <Button size="small" onClick={() => addBranch(node.id)}>+ 条件</Button>
      </div>
      <div className="pt-branches">
        {node.branchs?.map((b) => (
          <div className="pt-branch" key={b.id}>
            <div className="pt-branch__title" onClick={() => select(b.id)}>
              {b.name}{b.props?.isDefault ? '（默认）' : ''}
            </div>
            {b.children && <NodeChain node={b.children} />}
            {/* 分支内首个 + 按钮：插到该 CONDITION 之后 */}
            <BranchAdd branchId={b.id} />
          </div>
        ))}
      </div>
      {/* 分支合流后的后续链在 node.children 上，由外层 NodeChain 处理 */}
    </div>
  );
}

function BranchAdd({ branchId }: { branchId: string }) {
  const insert = useProcessDesignerStore((s) => s.insertAfter);
  return (
    <div className="pt-add pt-add--branch">
      <Button size="small" onClick={() => insert(branchId, 'APPROVAL')}>+ 审批</Button>
    </div>
  );
}
```

> 注意：`ConditionsNode` 里的 `NodeChain` 递归 + 外层 `NodeChain` 对 `CONDITIONS.children` 的递归，会导致 CONDITIONS 后续链正确接在分支下方。若出现循环 import（ProcessTree ↔ ConditionsNode），把 `NodeChain` 抽到单独文件 `NodeChain.tsx` 解耦。

- [ ] **Step 4: 样式 process-tree.less**（垂直居中链、连接线、分支左右布局）——参照 wflow `ProcessTree.vue` 的 `<style scoped>`（`.primary-node`/`.branch-node`/`.branch-node-item`/连接线）改写为本项目类名 `.pt-*`。

- [ ] **Step 5: 类型检查**

Run: `cd frontend && npm run tsc`
Expected: 无错误。

- [ ] **Step 6: 提交**

```bash
git add frontend/src/pages/designer/process/ProcessTree.tsx \
        frontend/src/pages/designer/process/nodes/ \
        frontend/src/pages/designer/process/process-tree.less
git commit -m "功能(前端): 钉钉式递归流程树渲染组件与节点卡片"
```

---

### Task 10: 配置面板（Root / Approval / Cc / Condition）

**Files:**
- Create: `frontend/src/pages/designer/process/config/ApprovalNodeConfig.tsx`
- Create: `frontend/src/pages/designer/process/config/CcNodeConfig.tsx`
- Create: `frontend/src/pages/designer/process/config/ConditionNodeConfig.tsx`
- Create: `frontend/src/pages/designer/process/config/RootNodeConfig.tsx`

- [ ] **Step 1: ApprovalNodeConfig（对照 wflow ApprovalNodeConfig.vue 核心子集）**

```tsx
import { Form, Input, Radio, InputNumber, Divider } from 'antd';
import { AssigneePicker } from '../../../../components/AssigneePicker';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function ApprovalNodeConfig({ node }: { node: any }) {
  const updateProps = useProcessDesignerStore((s) => s.updateProps);
  const updateName = useProcessDesignerStore((s) => s.updateName);
  const p = node.props;
  const set = (patch: any) => updateProps(node.id, { ...p, ...patch });

  return (
    <Form layout="vertical" style={{ padding: 16 }}>
      <Form.Item label="节点名称">
        <Input value={node.name} onChange={(e) => updateName(node.id, e.target.value)} />
      </Form.Item>
      <Form.Item label="审批对象">
        <Radio.Group value={p.assignedType} onChange={(e) => set({ assignedType: e.target.value })}
          options={[
            { value: 'ASSIGN_USER', label: '指定成员' },
            { value: 'ROLE', label: '角色' },
            { value: 'LEADER', label: '主管' },
            { value: 'SELF', label: '发起人自己' },
            { value: 'SELF_SELECT', label: '发起人自选' },
          ]} />
      </Form.Item>

      {p.assignedType === 'ASSIGN_USER' && (
        <Form.Item label="选择成员">
          <AssigneePicker mode="user" value={p.assignedUser} onChange={(ids) => set({ assignedUser: ids })} />
        </Form.Item>
      )}
      {p.assignedType === 'ROLE' && (
        <Form.Item label="选择角色">
          <AssigneePicker mode="role" value={p.role} onChange={(ids) => set({ role: ids })} />
        </Form.Item>
      )}
      {p.assignedType === 'LEADER' && (
        <Form.Item label="第几级主管（1=直接主管）">
          <InputNumber min={1} max={10} value={p.leader?.level ?? 1}
            onChange={(v) => set({ leader: { level: v ?? 1 } })} />
        </Form.Item>
      )}
      {p.assignedType === 'SELF_SELECT' && (
        <Form.Item label="自选方式">
          <Radio.Group value={p.selfSelect?.multiple ?? false}
            onChange={(e) => set({ selfSelect: { multiple: e.target.value } })}
            options={[{ value: false, label: '自选一人' }, { value: true, label: '自选多人' }]} />
        </Form.Item>
      )}

      <Divider />
      <Form.Item label="多人审批方式">
        <Radio.Group value={p.mode} onChange={(e) => set({ mode: e.target.value })}
          options={[
            { value: 'OR', label: '或签（一人通过即可）' },
            { value: 'AND', label: '会签（须全部通过）' },
          ]} />
      </Form.Item>
      <Form.Item label="审批人为空时">
        <Radio.Group value={p.nobody?.handler ?? 'TO_PASS'}
          onChange={(e) => set({ nobody: { handler: e.target.value } })}
          options={[{ value: 'TO_PASS', label: '自动通过' }, { value: 'TO_REFUSE', label: '自动驳回' }]} />
      </Form.Item>
    </Form>
  );
}
```

- [ ] **Step 2: CcNodeConfig（成员 + 角色）** — 结构同上，仅 `AssigneePicker(user)` + 名称。

- [ ] **Step 3: ConditionNodeConfig（条件组构建器）**

要点：编辑 `groups[]`，每组 `conditions[]`；`field` 下拉来自**关联表单的字段列表**（通过 props 传入 `formFields: {id,label,type}[]`，由 ProcessDesigner 拉 `/api/forms/definitions/by-id/{formDefId}` 的 schema 提供）；`operator` 下拉；`value` 输入。默认分支（`isDefault`）只显示名称、不显示条件编辑。给出可运行实现（约 90 行，含增删条件/组）。

```tsx
// 关键结构（示意，完整实现按此展开）
export function ConditionNodeConfig({ node, formFields }: { node: any; formFields: any[] }) {
  const updateProps = useProcessDesignerStore((s) => s.updateProps);
  const updateName = useProcessDesignerStore((s) => s.updateName);
  const p = node.props;
  if (p.isDefault) return <div style={{ padding: 16 }}>默认分支：其它条件都不满足时进入。</div>;
  // 渲染 groupsType 选择 + groups.map(组内 conditions.map(field/operator/value + 删除) + 加条件) + 加组
  // 每次变更 updateProps(node.id, nextProps)
  // ...
}
```

- [ ] **Step 4: RootNodeConfig（可发起人，可空=全员）** — `AssigneePicker(user)` 绑 `assignedUser`。

- [ ] **Step 5: 类型检查**

Run: `cd frontend && npm run tsc`
Expected: 无错误。

- [ ] **Step 6: 提交**

```bash
git add frontend/src/pages/designer/process/config/
git commit -m "功能(前端): 流程节点配置面板（审批/抄送/条件/发起人）"
```

---

### Task 11: ProcessDesigner 接线 + 移除 React Flow

**Files:**
- Modify: `frontend/src/pages/designer/process/ProcessDesigner.tsx`（整文件重写）
- Delete: `frontend/src/pages/designer/process/ApprovalNodeComponent.tsx`
- Delete: 旧 `frontend/src/pages/designer/process/ApprovalNodeConfig.tsx`（被 config/ 取代）
- Modify: `frontend/package.json`（移除 `@xyflow/react`）

- [ ] **Step 1: 重写 ProcessDesigner**

```tsx
import { Button, Space, message, Drawer } from 'antd';
import { useEffect } from 'react';
import { useParams, request } from '@umijs/max';
import { useQuery } from '@tanstack/react-query';
import { ProcessTree } from './ProcessTree';
import { useProcessDesignerStore } from './useProcessDesignerStore';
import { ApprovalNodeConfig } from './config/ApprovalNodeConfig';
import { CcNodeConfig } from './config/CcNodeConfig';
import { ConditionNodeConfig } from './config/ConditionNodeConfig';
import { RootNodeConfig } from './config/RootNodeConfig';

// 从树里按 id 找节点
function find(node: any, id: string): any {
  if (!node) return null;
  if (node.id === id) return node;
  for (const b of node.branchs ?? []) { const h = find(b, id); if (h) return h; }
  return find(node.children, id);
}

export default function ProcessDesigner() {
  const { formDefId } = useParams();
  const { process, selectedId, load, select } = useProcessDesignerStore();
  const [pdId, setPdId] = useState<number | null>(null);

  const { data: formDef } = useQuery({
    queryKey: ['form-def-by-id', formDefId],
    queryFn: () => request(`/api/forms/definitions/${formDefId}`),
  });
  const formFields = (formDef?.schema ?? []).map((n: any) => ({ id: n.id, label: n.props?.label ?? n.type, type: n.type }));

  useEffect(() => {
    (async () => {
      try {
        const pd = await request(`/api/processes/definitions/by-form/${formDefId}`);
        if (pd?.process) { setPdId(pd.id); load(pd.process); return; }
      } catch { /* 无则新建 */ }
    })();
  }, [formDefId]);

  const save = async () => {
    const res = await request('/api/processes/definitions', {
      method: 'POST', data: { id: pdId, formDefId: Number(formDefId), process },
    });
    setPdId(res.id); message.success('已保存草稿');
  };
  const publish = async () => {
    if (!pdId) await save();
    await request(`/api/processes/definitions/${pdId}/publish`, { method: 'POST' });
    message.success('已发布');
  };

  const selected = selectedId ? find(process, selectedId) : null;

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column' }}>
      <Space style={{ padding: 8 }}>
        <Button type="primary" onClick={save}>保存草稿</Button>
        <Button onClick={publish}>发布</Button>
      </Space>
      <div style={{ flex: 1, overflow: 'auto', background: '#f5f6f6', padding: 24 }}>
        <ProcessTree />
      </div>
      <Drawer open={!!selected} width={400} onClose={() => select(null)}
        title={selected?.name} destroyOnClose>
        {selected?.type === 'ROOT' && <RootNodeConfig node={selected} />}
        {selected?.type === 'APPROVAL' && <ApprovalNodeConfig node={selected} />}
        {selected?.type === 'CC' && <CcNodeConfig node={selected} />}
        {selected?.type === 'CONDITION' && <ConditionNodeConfig node={selected} formFields={formFields} />}
      </Drawer>
    </div>
  );
}
```

（补 `import { useState } from 'react';`）

- [ ] **Step 2: 删除旧文件 + 移除依赖**

```bash
git rm frontend/src/pages/designer/process/ApprovalNodeComponent.tsx
cd frontend && npm remove @xyflow/react
```

- [ ] **Step 3: lint + tsc + build**

Run: `cd frontend && npm run lint && npm run build`
Expected: 通过；产物中不再包含 `@xyflow/react`。

- [ ] **Step 4: 提交**

```bash
git add -A frontend/src/pages/designer/process/ frontend/package.json frontend/package-lock.json
git commit -m "重构(前端): 流程设计器改用钉钉式树，移除 React Flow 依赖"
```

---

## 阶段五：运行时打通 + 收尾

### Task 12: 发起时自选审批人 + 上送 selfSelected

**Files:**
- Modify: `frontend/src/pages/runtime/form/Fill.tsx`

- [ ] **Step 1: 发起前拉流程定义，收集 SELF_SELECT 节点**

`Fill` 在提交前 `request('/api/processes/definitions/by-form/'+fd.id)` 取 `process`，深度优先找出所有 `type==='APPROVAL' && props.assignedType==='SELF_SELECT'` 的节点。若存在，弹 `Modal` 用 `AssigneePicker(user)` 让发起人为每个节点选人，收集成 `selfSelected: { [nodeId]: number[] }`。

- [ ] **Step 2: start 请求带上 selfSelected**

```tsx
request('/api/instances/start', {
  method: 'POST',
  data: { formCode: code, data: val, selfSelected },
});
```

- [ ] **Step 3: 手动验证**

按"验证脚本"（见文末）跑一条含自选节点的流程，确认任务落到所选人。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/pages/runtime/form/Fill.tsx
git commit -m "功能(前端): 发起流程时为自选审批节点选择审批人"
```

---

### Task 13: 详情页展示抄送与流转路径（按需）

**Files:**
- Modify: `frontend/src/pages/proc/Detail.tsx`

- [ ] **Step 1:** 详情已返回 `tasks`（含 `status='CC'`）与 `history`。在时间线里区分 `CC`/`APPROVE`/`REJECT`/`SKIP`/`AUTO_PASS` 动作与抄送人。补充渲染即可（无接口改动）。
- [ ] **Step 2:** 手动核对详情展示。
- [ ] **Step 3: 提交** `git commit -m "功能(前端): 审批详情展示抄送与流转动作"`

---

### Task 14: 更新根 CLAUDE.md（消除认知漂移）

**Files:**
- Modify: `CLAUDE.md`（根）

- [ ] **Step 1:** 用 antflow 真实结构替换过时的 ant-design-pro-master/wflow-master 描述：monorepo `backend/`(Spring Boot 3) + `frontend/`(Umi Max 4) + `infra/` + `docs/`；补"流程模型 = 钉钉式递归树，参考 `D:\code\wflow-master`"；给出 backend/frontend 各自命令。
- [ ] **Step 2: 提交** `git commit -m "文档: 根 CLAUDE.md 更新为 antflow 真实结构与流程树约定"`

---

## 端到端验证脚本（阶段完成后跑）

```bash
# 1. 起库 + 后端
cd infra && docker compose up -d
cd ../backend && mvn -q -o spring-boot:run &   # 应用 V1..V4
# 2. 登录取 token
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ant.design"}' | jq -r .accessToken)
# 3. 建表单并发布（略，用现有接口）→ 拿 formDefId / code
# 4. 存流程树（含 1 审批 OR + 1 条件分支 + 1 抄送）
curl -s -X POST localhost:8080/api/processes/definitions -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d @process-tree.json
# 5. 发布流程 → publish
# 6. bob 提交（带 selfSelected 若有）→ /api/instances/start
# 7. 断言：inbox 出现任务；admin approve；条件分支按 amount 路由正确；CC 人可见；实例 APPROVED
```

后端单测：`cd backend && mvn -q -o test`（原 16 + ProcessTreeNav 3 + 校验 3 + Resolver +4 + Condition 4 + Engine 6）。
前端：`cd frontend && npm run lint && npm run tsc && npm run build`。

---

## 自检（Self-Review）

**规格覆盖：**
- 节点 ROOT/APPROVAL/CC/CONDITIONS → Task 8/9/10 建模+渲染+配置，Task 3 校验，Task 7 执行。✅
- 审批人 ASSIGN_USER/ROLE/LEADER/SELF/SELF_SELECT → Task 5 解析 + Task 10 配置 + Task 12 自选上送。✅
- 会签 AND / 或签 OR → Task 7 推进语义 + Task 10 配置写 `mode`。✅
- 条件分支求值 → Task 6 + Task 7 路由 + Task 10 条件构建器。✅
- 抄送非阻塞 → Task 7 + Task 13 展示。✅
- 移除 React Flow → Task 11。✅
- 认知漂移（CLAUDE.md）→ Task 14。✅

**类型一致性：** 后端 `AssigneeSpec(type, ids, leaderLevel, starterId, selfSelected)` 在 Task 4 定义、Task 5/7 使用一致；`process` 字段在 Task 1 定义、Task 3/7/11 使用一致；前端 `TreeNode` 在 Task 8 定义、Task 9/10/11 使用一致；`insertAfter/removeNode/addBranch/updateProps/updateName` 全程同名。

**已知取舍（非缺陷，二期）：** `LEADER_TOP` 连续多级主管、`NEXT` 依次会签、并行/延时/触发器、超时、驳回到指定节点、节点级表单字段权限、发布版本快照与已发布流程改版路径、列表分页、实例详情读权限——均列入二期，不影响本计划树模型的稳定性。

**并行/独立性：** 阶段一(Task1-3) → 阶段二(Task4-6，纯逻辑可与阶段一后半并行) → 阶段三(Task7，依赖 1/2) → 阶段四(Task8-11，依赖 Task1 的字段契约，可与阶段三并行) → 阶段五(Task12-14，依赖 3/4)。
