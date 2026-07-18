# AntFlow Mobile Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复移动端接入前的后端契约与安全缺口，并交付可发布、可回滚的品牌配置后端和桌面管理页。

**Architecture:** 在现有 Spring Boot 应用中新增 `mobile` 聚合 DTO 层和 `branding` 领域，不向移动端直接暴露 MyBatis 实体或 JSONB 字符串。品牌配置采用不可变版本 + 单一已发布版本；桌面端新增 `/settings/branding` 管理页。现有流程引擎继续负责实例事务和快照，移动 API 只做授权、DTO 转换和调用编排。

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Security 6, MyBatis-Plus, PostgreSQL 17/Flyway, JUnit 5/Mockito, React 19, Umi Max 4, Ant Design 6, TanStack Query, Vitest.

---

## File Map

### Backend create

- `backend/src/main/resources/db/migration/V10__mobile_platform_foundation.sql`：品牌版本、用户移动偏好、刷新会话。
- `backend/src/main/java/com/antflow/branding/*`：品牌实体、Mapper、Service、DTO、Admin/Public Controller、资源存储。
- `backend/src/main/java/com/antflow/mobile/*`：移动 DTO、结构化 JSON 转换、bootstrap、应用目录和偏好 API。
- `backend/src/main/java/com/antflow/auth/RefreshSession*`：刷新令牌轮换和设备会话。
- `backend/src/main/java/com/antflow/common/IdempotencyFilter.java`：把现有幂等服务接入指定写接口。

### Backend modify

- `backend/src/main/java/com/antflow/engine/handler/ApprovalHandler.java`：SELF_SELECT 使用节点 ID。
- `backend/src/main/java/com/antflow/task/InstanceController.java`：资源级授权。
- `backend/src/main/java/com/antflow/task/TaskController.java`：资源级授权和幂等入口。
- `backend/src/main/java/com/antflow/auth/SecurityConfig.java`：公开品牌、刷新接口、真实 CORS 配置。
- `backend/src/main/resources/application.yml`：品牌存储和 CORS 配置。

### Frontend create/modify

- `frontend/src/pages/settings/Branding.tsx`：品牌配置页和移动预览。
- `frontend/src/pages/settings/Branding.test.tsx`：表单、草稿、发布和恢复交互。
- `frontend/config/routes.ts`：`/settings/branding` 路由。

## Task 1: Freeze The Baseline And Add Mobile Contract Fixtures

**Files:**
- Create: `backend/src/test/java/com/antflow/mobile/MobileContractFixture.java`
- Create: `backend/src/test/java/com/antflow/mobile/MobileFormDtoTest.java`
- Modify: `backend/pom.xml`

- [ ] **Step 1: Record the current baseline**

Run:

```powershell
Set-Location backend
mvn -B test
```

Expected: 59 tests, 0 failures, 1 skipped. If the count changed because the branch advanced, record the new passing baseline in the commit message body before continuing.

- [ ] **Step 2: Add a reusable JSON fixture**

Create `MobileContractFixture.java`:

```java
package com.antflow.mobile;

public final class MobileContractFixture {
    public static final String FORM_SCHEMA = """
        [{"id":"days","type":"number","props":{"label":"请假天数","required":true}}]
        """;

    public static final String PROCESS_TREE = """
        {"id":"root","type":"ROOT","children":{"id":"approve_1","type":"APPROVAL",
        "name":"主管审批","props":{"assignedType":"ASSIGN_USER","assignedUser":[1],
        "mode":"OR","nobody":{"handler":"TO_PASS"}},"children":null}}
        """;

    private MobileContractFixture() {
    }
}
```

- [ ] **Step 3: Write the failing structured DTO test**

Create `MobileFormDtoTest.java` with an assertion that schema is a JSON array rather than a String:

```java
package com.antflow.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MobileFormDtoTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesSchemaIntoStructuredJson() throws Exception {
        MobileJsonConverter converter = new MobileJsonConverter(objectMapper);
        var schema = converter.readArray(MobileContractFixture.FORM_SCHEMA, "schema");
        assertThat(schema.isArray()).isTrue();
        assertThat(schema.get(0).path("id").asText()).isEqualTo("days");
    }
}
```

- [ ] **Step 4: Run the focused test and verify RED**

```powershell
mvn -B -Dtest=MobileFormDtoTest test
```

Expected: compilation failure because `MobileJsonConverter` does not exist.

- [ ] **Step 5: Commit the test fixture only**

```powershell
git add backend/src/test/java/com/antflow/mobile
git commit -m "测试(移动端): 增加移动接口结构化数据契约"
```

## Task 2: Add Structured Mobile DTO Conversion

**Files:**
- Create: `backend/src/main/java/com/antflow/mobile/MobileJsonConverter.java`
- Create: `backend/src/main/java/com/antflow/mobile/MobileDtos.java`
- Test: `backend/src/test/java/com/antflow/mobile/MobileFormDtoTest.java`

- [ ] **Step 1: Implement strict JSON conversion**

Create `MobileJsonConverter.java`:

```java
package com.antflow.mobile;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MobileJsonConverter {
    private final ObjectMapper objectMapper;

    public ArrayNode readArray(String value, String field) {
        JsonNode node = read(value, field);
        if (!node.isArray()) {
            throw new BizException("BAD_" + field.toUpperCase(), field + " must be an array");
        }
        return (ArrayNode) node;
    }

    public JsonNode readObject(String value, String field) {
        JsonNode node = read(value, field);
        if (!node.isObject()) {
            throw new BizException("BAD_" + field.toUpperCase(), field + " must be an object");
        }
        return node;
    }

    private JsonNode read(String value, String field) {
        try {
            return objectMapper.readTree(value == null || value.isBlank() ? "null" : value);
        } catch (Exception exception) {
            throw new BizException("BAD_" + field.toUpperCase(), "invalid " + field);
        }
    }
}
```

- [ ] **Step 2: Define stable mobile DTOs**

Create `MobileDtos.java`:

```java
package com.antflow.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class MobileDtos {
    private MobileDtos() {
    }

    public record FormDefinitionDto(Long id, String code, String name, Integer version,
                                    JsonNode schema, JsonNode settings) {
    }

    public record ProcessStartContextDto(Long processDefinitionId, Integer version,
                                         JsonNode process, List<SelfSelectNodeDto> selfSelectNodes) {
    }

    public record SelfSelectNodeDto(String nodeId, String name, boolean multiple) {
    }

    public record AppDto(Long formId, String code, String name, String category,
                         String icon, boolean favorite, int favoriteOrder) {
    }

    public record RecentProcessDto(Long instanceId, String formName, String status,
                                   String currentNodeName, OffsetDateTime updatedAt) {
    }

    public record BootstrapDto(UserDto user, long pendingCount, List<AppDto> favoriteApps,
                               List<RecentProcessDto> recentProcesses, String brandingVersion) {
    }

    public record UserDto(Long id, String username, String displayName,
                          String departmentName, String position, List<String> roles) {
    }

    public record StartInstanceRequest(String formCode, Map<String, Object> data,
                                       Map<String, List<Long>> selfSelected, Long draftId) {
    }
}
```

- [ ] **Step 3: Run the DTO tests**

```powershell
mvn -B -Dtest=MobileFormDtoTest test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/java/com/antflow/mobile backend/src/test/java/com/antflow/mobile
git commit -m "功能(后端): 增加移动端结构化 DTO 转换"
```

## Task 3: Fix SELF_SELECT Node Identity

**Files:**
- Modify: `backend/src/main/java/com/antflow/engine/handler/ApprovalHandler.java`
- Test: `backend/src/test/java/com/antflow/engine/ProcessEngineTreeTest.java`

- [ ] **Step 1: Add a failing engine regression test**

Add a test whose process node ID is `self_select_1`, props contain no `id`, and start command supplies `selfSelected.self_select_1=[42]`. Assert the created task assignee is 42:

```java
@Test
void selfSelectUsesProcessNodeId() {
    String tree = """
        {"id":"root","type":"ROOT","children":{"id":"self_select_1","type":"APPROVAL",
        "props":{"assignedType":"SELF_SELECT","mode":"OR","selfSelect":{"multiple":false},
        "nobody":{"handler":"TO_REFUSE"}},"children":null}}
        """;
    stubPublishedDefinitions(tree);

    engine.start(new StartCmd("leave", Map.of("days", 1),
        Map.of("self_select_1", List.of(42L))), 7L);

    TaskEntity task = taskMapper.selectList(null).get(0);
    assertThat(task.getNodeId()).isEqualTo("self_select_1");
    assertThat(task.getAssigneeId()).isEqualTo(42L);
}
```

- [ ] **Step 2: Verify RED**

```powershell
mvn -B -Dtest=ProcessEngineTreeTest#selfSelectUsesProcessNodeId test
```

Expected: no task is created for user 42 because the handler reads `props.id`.

- [ ] **Step 3: Pass nodeId into assignee parsing**

Change the call and signature in `ApprovalHandler`:

```java
AssigneeSpec spec = parseAssignee(nodeId, node.path("props"), ctx);

private static AssigneeSpec parseAssignee(String nodeId, JsonNode props, NodeContext ctx) {
    String type = props.path("assignedType").asText();
    return switch (type) {
        case "ASSIGN_USER" -> AssigneeSpec.of("ASSIGN_USER", readIds(props.path("assignedUser")));
        case "ROLE" -> AssigneeSpec.of("ROLE", readIds(props.path("role")));
        case "LEADER" -> new AssigneeSpec("LEADER", List.of(),
            props.path("leader").path("level").asInt(1), ctx.starterId(), List.of());
        case "SELF" -> new AssigneeSpec("SELF", List.of(), 1, ctx.starterId(), List.of());
        case "SELF_SELECT" -> new AssigneeSpec("SELF_SELECT", List.of(), 1,
            ctx.starterId(), ctx.selfSelected() == null
                ? List.of() : ctx.selfSelected().getOrDefault(nodeId, List.of()));
        default -> throw new IllegalArgumentException("未识别审批人类型: " + type);
    };
}
```

保留现有 `readIds` 辅助方法；只改变节点 ID 的传递和 SELF_SELECT 分支，不改其他审批人策略的数据库查询行为。

- [ ] **Step 4: Verify focused and engine suites**

```powershell
mvn -B -Dtest=ProcessEngineTreeTest,AssigneeResolverTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/antflow/engine/handler/ApprovalHandler.java backend/src/test/java/com/antflow/engine/ProcessEngineTreeTest.java
git commit -m "修复(后端): 自选审批人按流程节点 ID 解析"
```

## Task 4: Add Resource-Level Instance And Task Authorization

**Files:**
- Create: `backend/src/main/java/com/antflow/task/WorkflowAuthorizationService.java`
- Modify: `backend/src/main/java/com/antflow/task/InstanceController.java`
- Modify: `backend/src/main/java/com/antflow/task/TaskController.java`
- Test: `backend/src/test/java/com/antflow/task/WorkflowAuthorizationServiceTest.java`

- [ ] **Step 1: Write authorization tests**

Cover starter, pending/completed assignee, admin, and unrelated user:

```java
@Test
void unrelatedUserCannotReadInstance() {
    when(instanceMapper.selectById(9L)).thenReturn(instanceStartedBy(1L));
    when(taskMapper.selectCount(any())).thenReturn(0L);

    assertThat(service.canReadInstance(9L, 99L, List.of("user"))).isFalse();
}

@Test
void taskParticipantCanReadInstance() {
    when(instanceMapper.selectById(9L)).thenReturn(instanceStartedBy(1L));
    when(taskMapper.selectCount(any())).thenReturn(1L);

    assertThat(service.canReadInstance(9L, 42L, List.of("user"))).isTrue();
}
```

- [ ] **Step 2: Verify RED**

```powershell
mvn -B -Dtest=WorkflowAuthorizationServiceTest test
```

Expected: compilation failure because the service does not exist.

- [ ] **Step 3: Implement one authorization source**

Create a service with these public methods:

```java
public void requireInstanceRead(Long instanceId, PrincipalHolder.Principal principal);
public void requireInstanceWithdraw(Long instanceId, PrincipalHolder.Principal principal);
public void requireTaskRead(Long taskId, PrincipalHolder.Principal principal);
public void requireTaskAction(Long taskId, PrincipalHolder.Principal principal);
public boolean canReadInstance(Long instanceId, Long userId, List<String> roles);
```

Rules:

```java
boolean admin = roles.contains("admin");
boolean starter = Objects.equals(instance.getStartedBy(), userId);
boolean participant = taskMapper.selectCount(new QueryWrapper<TaskEntity>()
    .eq("proc_inst_id", instanceId)
    .and(query -> query.eq("assignee_id", userId).or().eq("approved_by", userId))) > 0;
return admin || starter || participant;
```

Throw `AccessDeniedException` when a require method fails. Do not duplicate these rules in controllers.

- [ ] **Step 4: Apply checks to detail/history/children/actions**

Call the service before returning instance detail/history, task children, approve, reject, transfer, delegate, add-assignee and recall.

- [ ] **Step 5: Verify**

```powershell
mvn -B -Dtest=WorkflowAuthorizationServiceTest,ProcessEngineTreeTest,TaskOperationServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/antflow/task backend/src/test/java/com/antflow/task
git commit -m "修复(后端): 收紧流程实例与任务资源权限"
```

## Task 5: Add Brand Version Persistence

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__mobile_platform_foundation.sql`
- Create: `backend/src/main/java/com/antflow/branding/BrandConfig.java`
- Create: `backend/src/main/java/com/antflow/branding/BrandConfigMapper.java`
- Create: `backend/src/main/java/com/antflow/branding/BrandConfigService.java`
- Test: `backend/src/test/java/com/antflow/branding/BrandConfigServiceTest.java`

- [ ] **Step 1: Write service state-transition tests**

Test that saving creates/updates a DRAFT, publishing archives the previous PUBLISHED row, and restoring copies history into a new version.

- [ ] **Step 2: Verify RED**

```powershell
mvn -B -Dtest=BrandConfigServiceTest test
```

Expected: compilation failure because the branding domain does not exist.

- [ ] **Step 3: Add V10 migration**

```sql
CREATE TABLE t_brand_config (
    id BIGSERIAL PRIMARY KEY,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    app_name VARCHAR(64) NOT NULL,
    company_name VARCHAR(128) NOT NULL,
    primary_color VARCHAR(16) NOT NULL,
    mobile_header_title VARCHAR(64) NOT NULL,
    login_title VARCHAR(64) NOT NULL,
    footer_text VARCHAR(255),
    show_login_footer BOOLEAN NOT NULL DEFAULT TRUE,
    logo_light_url VARCHAR(512),
    logo_dark_url VARCHAR(512),
    favicon_url VARCHAR(512),
    login_bg_url VARCHAR(512),
    created_by BIGINT NOT NULL REFERENCES t_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by BIGINT REFERENCES t_user(id),
    published_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_brand_one_draft
    ON t_brand_config ((status)) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX ux_brand_one_published
    ON t_brand_config ((status)) WHERE status = 'PUBLISHED';
CREATE UNIQUE INDEX ux_brand_version ON t_brand_config(version);

CREATE TABLE t_user_mobile_preference (
    user_id BIGINT PRIMARY KEY REFERENCES t_user(id) ON DELETE CASCADE,
    favorite_form_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_refresh_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    user_agent VARCHAR(512),
    ip_address VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_from UUID REFERENCES t_refresh_session(id)
);
CREATE INDEX ix_refresh_session_user ON t_refresh_session(user_id, created_at DESC);

CREATE TABLE t_audit_log (
    id BIGSERIAL PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    operator_id BIGINT REFERENCES t_user(id),
    target_type VARCHAR(64),
    target_id VARCHAR(128),
    trace_id VARCHAR(64),
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_created ON t_audit_log(created_at DESC, id DESC);
CREATE INDEX ix_audit_log_operator ON t_audit_log(operator_id, created_at DESC);
```

- [ ] **Step 4: Implement immutable published versions**

Expose service methods:

```java
BrandConfig getPublishedOrDefault();
BrandConfig getDraftOrPublishedCopy(long operatorId);
BrandConfig saveDraft(BrandConfigCommand command, long operatorId);
BrandConfig publish(long draftId, long operatorId);
BrandConfig restore(int version, long operatorId);
List<BrandConfig> history();
```

Publishing must run in one transaction: archive current PUBLISHED, mark DRAFT as PUBLISHED, set operator/time, then create no implicit new draft. Editing a published config first creates a DRAFT copy.

- [ ] **Step 5: Validate config values**

Reject invalid hex color, excessive text lengths and asset URLs outside the configured asset prefix with `BizException("BAD_BRAND_CONFIG", ...)`.

- [ ] **Step 6: Verify and commit**

```powershell
mvn -B -Dtest=BrandConfigServiceTest test
git add backend/src/main/resources/db/migration/V10__mobile_platform_foundation.sql backend/src/main/java/com/antflow/branding backend/src/test/java/com/antflow/branding
git commit -m "功能(品牌): 增加品牌配置版本与发布模型"
```

## Task 6: Add Public/Admin Branding APIs And Safe Asset Upload

**Files:**
- Create: `backend/src/main/java/com/antflow/branding/BrandingDtos.java`
- Create: `backend/src/main/java/com/antflow/branding/PublicBrandingController.java`
- Create: `backend/src/main/java/com/antflow/branding/AdminBrandingController.java`
- Create: `backend/src/main/java/com/antflow/branding/BrandAssetService.java`
- Modify: `backend/src/main/java/com/antflow/auth/SecurityConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/antflow/branding/BrandAssetServiceTest.java`

- [ ] **Step 1: Add asset validation tests**

Cover PNG signature, maximum size, unsupported executable content and an SVG containing `<script>`.

- [ ] **Step 2: Implement DTOs and controllers**

Public DTO must omit draft/audit fields:

```java
public record PublicBrandingDto(String version, String appName, String companyName,
    String primaryColor, String mobileHeaderTitle, String loginTitle,
    String footerText, boolean showLoginFooter, String logoLightUrl,
    String logoDarkUrl, String faviconUrl, String loginBgUrl) {
}
```

Controller paths:

```java
@GetMapping("/api/public/branding")
@GetMapping(value = "/api/public/branding/manifest.webmanifest",
    produces = "application/manifest+json")
@GetMapping("/api/admin/branding")
@PostMapping("/api/admin/branding/drafts")
@PostMapping("/api/admin/branding/{id}/publish")
@PostMapping("/api/admin/branding/{version}/restore")
@PostMapping(path = "/api/admin/branding/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
```

All `/api/admin/branding/**` methods use `@PreAuthorize("hasRole('admin')")`.

The manifest endpoint returns published `appName`, `primaryColor`, favicon-derived icons, `start_url=/mobile/` and `display=standalone`; it uses the built-in AntFlow fallback when no row is published.

- [ ] **Step 3: Permit only the public read endpoint**

In `SecurityConfig`:

```java
.requestMatchers(HttpMethod.GET, "/api/public/branding").permitAll()
.requestMatchers(HttpMethod.POST, "/api/auth/refresh", "/api/auth/logout").permitAll()
```

Keep admin endpoints authenticated and method-protected.

- [ ] **Step 4: Configure storage explicitly**

Add:

```yaml
antflow:
  branding:
    asset-directory: ${BRAND_ASSET_DIRECTORY:./data/branding}
    public-prefix: ${BRAND_ASSET_PUBLIC_PREFIX:/api/public/branding/assets/}
    max-bytes: ${BRAND_ASSET_MAX_BYTES:5242880}
```

- [ ] **Step 5: Verify and commit**

```powershell
mvn -B -Dtest=BrandAssetServiceTest,BrandConfigServiceTest test
git add backend/src/main/java/com/antflow/branding backend/src/main/java/com/antflow/auth/SecurityConfig.java backend/src/main/resources/application.yml backend/src/test/java/com/antflow/branding
git commit -m "功能(品牌): 增加品牌发布接口与安全资源上传"
```

## Task 7: Add Refresh Session Rotation

**Files:**
- Create: `backend/src/main/java/com/antflow/auth/RefreshSession.java`
- Create: `backend/src/main/java/com/antflow/auth/RefreshSessionMapper.java`
- Create: `backend/src/main/java/com/antflow/auth/RefreshSessionService.java`
- Create: `backend/src/main/java/com/antflow/auth/SessionController.java`
- Modify: `backend/src/main/java/com/antflow/auth/LoginController.java`
- Test: `backend/src/test/java/com/antflow/auth/RefreshSessionServiceTest.java`

- [ ] **Step 1: Test issue, rotate, replay rejection and revoke**

The test must prove that a consumed refresh token cannot be used twice and that rotation creates a new session linked by `rotated_from`.

- [ ] **Step 2: Implement hashed refresh storage**

Store only `SHA-256(base64urlToken)` in `token_hash`. Return the raw 256-bit token once in an HttpOnly cookie:

```java
ResponseCookie.from("antflow-refresh", rawToken)
    .httpOnly(true)
    .secure(secureCookie)
    .sameSite("Lax")
    .path("/api/auth")
    .maxAge(refreshTtl)
    .build();
```

Login and successful refresh also rotate a separate random `antflow-csrf` cookie. It is not HttpOnly, and refresh/logout require the same value in `X-CSRF-Token`, compared in constant time. Tests must reject missing or mismatched values before rotating or revoking a session.

- [ ] **Step 3: Add session endpoints**

```text
POST /api/auth/refresh       rotate cookie, return short-lived accessToken
POST /api/auth/logout        revoke current refresh session and clear cookie
GET  /api/auth/sessions      list current user's active devices
DELETE /api/auth/sessions/{id} revoke one device
```

Keep current desktop login response compatible while also setting the refresh cookie.

- [ ] **Step 4: Verify and commit**

```powershell
mvn -B -Dtest=RefreshSessionServiceTest,JwtServiceConstructorTest test
git add backend/src/main/java/com/antflow/auth backend/src/test/java/com/antflow/auth
git commit -m "功能(认证): 增加刷新令牌轮换与设备会话"
```

## Task 8: Wire Idempotency Into Workflow Writes

**Files:**
- Create: `backend/src/main/java/com/antflow/common/IdempotencyFilter.java`
- Modify: `backend/src/main/java/com/antflow/common/IdempotencyService.java`
- Modify: `backend/src/main/java/com/antflow/auth/SecurityConfig.java`
- Test: `backend/src/test/java/com/antflow/common/IdempotencyFilterTest.java`

- [ ] **Step 1: Test replay and user isolation**

Verify the same user/key/path replays the first response, different users do not share responses, and requests without the header execute normally.

- [ ] **Step 2: Include method and path in the cache key**

Use:

```java
String fullKey = "idem:" + userId + ":" + method + ":" + path + ":" + key;
```

Cache only completed 2xx responses for:

```text
POST /api/mobile/instances
POST /api/mobile/instances/{id}/withdraw
POST /api/mobile/tasks/{id}/approve
POST /api/mobile/tasks/{id}/reject
```

- [ ] **Step 3: Add bounded expiry**

Replace the unbounded map value with `(response, expiresAt)` and remove expired entries on access. Default TTL: 24 hours. Document Redis as the multi-instance production implementation; do not add a fake reflection-based Redis branch.

- [ ] **Step 4: Verify and commit**

```powershell
mvn -B -Dtest=IdempotencyServiceTest,IdempotencyFilterTest test
git add backend/src/main/java/com/antflow/common backend/src/test/java/com/antflow/common backend/src/main/java/com/antflow/auth/SecurityConfig.java
git commit -m "功能(后端): 接入流程写操作幂等控制"
```

## Task 9: Add Mobile Bootstrap, App Catalog And Preferences

**Files:**
- Create: `backend/src/main/java/com/antflow/mobile/MobilePreference.java`
- Create: `backend/src/main/java/com/antflow/mobile/MobilePreferenceMapper.java`
- Create: `backend/src/main/java/com/antflow/mobile/MobileApplicationService.java`
- Create: `backend/src/main/java/com/antflow/mobile/MobileController.java`
- Test: `backend/src/test/java/com/antflow/mobile/MobileApplicationServiceTest.java`

- [ ] **Step 1: Write service tests**

Cover:

- Only PUBLISHED forms are returned.
- Favorites preserve persisted order and ignore unpublished IDs.
- Bootstrap returns pending count and at most three recent processes.
- Structured schema/process DTOs contain JsonNode, never JSON strings.

- [ ] **Step 2: Implement mobile endpoints**

```text
GET /api/mobile/bootstrap
GET /api/mobile/apps?keyword=&category=
PUT /api/mobile/preferences/apps
GET /api/mobile/forms/{code}
GET /api/mobile/forms/{code}/start-context
```

The preference request is exactly:

```java
public record FavoriteAppsRequest(List<Long> formIds) {
    public FavoriteAppsRequest {
        formIds = formIds == null ? List.of() : formIds.stream().distinct().toList();
        if (formIds.size() > 8) {
            throw new IllegalArgumentException("favorite apps cannot exceed 8");
        }
    }
}
```

- [ ] **Step 3: Open published reads without opening admin drafts**

Do not relax class-level security on existing definition controllers. MobileController calls services and returns only PUBLISHED DTOs.

- [ ] **Step 4: Verify and commit**

```powershell
mvn -B -Dtest=MobileApplicationServiceTest,FormDefinitionServiceSchemaTest,ProcessDefinitionServiceValidationTest test
git add backend/src/main/java/com/antflow/mobile backend/src/test/java/com/antflow/mobile
git commit -m "功能(后端): 增加移动工作台聚合与应用目录"
```

## Task 10: Build Desktop Branding Management Page

**Files:**
- Create: `frontend/src/pages/settings/Branding.tsx`
- Create: `frontend/src/pages/settings/Branding.test.tsx`
- Create: `frontend/src/pages/settings/branding.service.ts`
- Modify: `frontend/config/routes.ts`
- Modify: `frontend/src/locales/zh-CN/menu.ts`

- [ ] **Step 1: Inspect Ant Design APIs**

```powershell
Set-Location frontend
npx antd info Upload
npx antd info Form
npx antd info ColorPicker
npx antd info Tabs
```

Record the installed Ant Design 6 APIs in the plan execution notes; do not use memory-only props.

- [ ] **Step 2: Write failing component tests**

Mock the service and assert:

```tsx
expect(screen.getByLabelText('应用名称')).toHaveValue('AntFlow 审批');
expect(screen.getByText('保存草稿')).toBeInTheDocument();
expect(screen.getByText('发布配置')).toBeInTheDocument();
expect(screen.getByText('实时预览')).toBeInTheDocument();
```

Also assert publish calls `publishBranding(draft.id)` only after `form.validateFields()` resolves.

- [ ] **Step 3: Implement typed service functions**

```ts
export type BrandConfig = {
  id: number;
  version: number;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  appName: string;
  companyName: string;
  primaryColor: string;
  mobileHeaderTitle: string;
  loginTitle: string;
  footerText?: string;
  showLoginFooter: boolean;
  logoLightUrl?: string;
  logoDarkUrl?: string;
  faviconUrl?: string;
  loginBgUrl?: string;
};

export const getBranding = () => request<BrandConfig>('/api/admin/branding');
export const saveBrandingDraft = (data: BrandConfig) =>
  request<BrandConfig>('/api/admin/branding/drafts', { method: 'POST', data });
export const publishBranding = (id: number) =>
  request<BrandConfig>(`/api/admin/branding/${id}/publish`, { method: 'POST' });
```

- [ ] **Step 4: Implement the page structure**

Use one unframed form surface plus one sticky preview surface. Tabs: 品牌标识、标题与文案、登录页面、发布记录. Actions: 恢复已发布版本、保存草稿、发布配置. Do not create cards inside cards.

- [ ] **Step 5: Add route and menu label**

```ts
{ name: 'branding', icon: 'highlight', path: '/settings/branding', component: './settings/Branding' }
```

- [ ] **Step 6: Verify and commit**

```powershell
npm run biome:lint
npm test -- src/pages/settings/Branding.test.tsx
npm run tsc
npm run build
git add frontend/src/pages/settings/Branding.tsx frontend/src/pages/settings/Branding.test.tsx frontend/src/pages/settings/branding.service.ts frontend/config/routes.ts frontend/src/locales/zh-CN/menu.ts
git commit -m "功能(品牌): 增加品牌外观配置与移动预览"
```

Expected: all four commands exit 0. If the branch still has the known `Company.tsx setLogo` or access test failures, fix those baseline defects in a separate `修复(前端)` commit before claiming this task complete.

## Task 11: Align CORS And Environment Defaults

**Files:**
- Modify: `backend/src/main/java/com/antflow/auth/SecurityConfig.java`
- Create: `backend/src/main/java/com/antflow/auth/CorsProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `frontend/config/proxy.ts`
- Modify: `README.md`
- Test: `backend/src/test/java/com/antflow/auth/CorsPropertiesTest.java`

- [ ] **Step 1: Bind CORS config instead of deriving it from JWT properties**

```java
@Component
@ConfigurationProperties(prefix = "antflow.cors")
public class CorsProperties {
    private List<String> allowedOrigins = List.of("http://localhost:8000", "http://localhost:5173");
    // getters/setters
}
```

Inject `CorsProperties` into SecurityConfig and remove the current always-localhost conditional.

- [ ] **Step 2: Standardize local ports**

Use backend `8081`, desktop `8000`, mobile `5173` for the development documentation and proxy configuration. Keep production configurable by environment.

- [ ] **Step 3: Document Docker credentials explicitly**

README commands must set datasource username/password to Docker defaults or change Docker defaults and application defaults in the same commit. Do not leave two contradictory quick starts.

- [ ] **Step 4: Verify and commit**

```powershell
Set-Location backend
mvn -B test
Set-Location ..\frontend
npm run build
Set-Location ..
git add backend/src/main/java/com/antflow/auth backend/src/main/resources/application.yml backend/src/test/java/com/antflow/auth frontend/config/proxy.ts README.md
git commit -m "修复(配置): 对齐数据库端口与跨域配置"
```

## Task 11A: Add Security And Branding Audit Events

**Files:**
- Create: `backend/src/main/java/com/antflow/common/AuditLogService.java`
- Create: `backend/src/main/java/com/antflow/common/AuditLogController.java`
- Create: `backend/src/main/java/com/antflow/common/AuditLogEntity.java`
- Create: `backend/src/main/java/com/antflow/common/AuditLogMapper.java`
- Test: `backend/src/test/java/com/antflow/common/AuditLogServiceTest.java`
- Modify: `backend/src/main/java/com/antflow/branding/BrandConfigService.java`
- Modify: `backend/src/main/java/com/antflow/auth/RefreshSessionService.java`
- Modify: `frontend/src/pages/security/AuditLog.tsx`

- [ ] **Step 1: Test redacted audit details**

Assert brand publish, session revoke and permission denial create rows with operator, target, outcome and traceId, while detail never contains passwords, tokens, form values or storage keys.

- [ ] **Step 2: Implement the audit service**

```java
public void record(String category, String action, String outcome, Long operatorId,
                   String targetType, String targetId, String traceId,
                   Map<String, Object> safeDetail) {
    AuditLogEntity row = new AuditLogEntity();
    row.setCategory(category);
    row.setAction(action);
    row.setOutcome(outcome);
    row.setOperatorId(operatorId);
    row.setTargetType(targetType);
    row.setTargetId(targetId);
    row.setTraceId(traceId);
    row.setDetail(writeRedactedJson(safeDetail));
    mapper.insert(row);
}
```

- [ ] **Step 3: Add paged admin audit read**

`GET /api/admin/audit-logs?page=&pageSize=&category=&operatorId=` is admin-only and returns DTOs without raw JSON secrets. Replace the current desktop `Result` page with a read-only ProTable; filtering and pagination must use server parameters.

- [ ] **Step 4: Verify and commit**

```powershell
Set-Location backend
mvn -B -Dtest=AuditLogServiceTest test
Set-Location ..\frontend
npm run build
Set-Location ..
git add backend/src/main/java/com/antflow/common frontend/src/pages/security/AuditLog.tsx
git commit -m "功能(安全): 增加品牌与会话操作审计"
```

## Task 12: Foundation Verification Checkpoint

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `docs/mobile-platform-foundation-verification.md`

- [ ] **Step 1: Make existing quality gates blocking**

Remove `|| true` from Biome and TypeScript CI steps only after fixing the pre-existing failures in isolated commits.

- [ ] **Step 2: Run full verification**

```powershell
Set-Location backend
mvn -B test
Set-Location ..\frontend
npm run biome:lint
npm test
npm run tsc
npm run build
```

Expected: every command exits 0.

- [ ] **Step 3: Smoke brand and published mobile endpoints**

With PostgreSQL and backend running on 8081:

```powershell
Invoke-RestMethod http://localhost:8081/api/public/branding
Invoke-RestMethod http://localhost:8081/api/mobile/bootstrap -Headers @{Authorization="Bearer $token"}
```

Expected: branding works without token; bootstrap returns structured objects with token.

- [ ] **Step 4: Record evidence**

The verification document must include command, exit code, test count, timestamp and any intentionally skipped environment-only checks.

- [ ] **Step 5: Commit**

```powershell
git add .github/workflows/ci.yml docs/mobile-platform-foundation-verification.md
git commit -m "测试(平台): 收紧质量门并记录基础验收结果"
```

## Completion Gate

The next plan may start only when:

- Published form/process mobile DTOs are structured JSON.
- SELF_SELECT regression passes.
- Unrelated users receive 403 for instances/tasks.
- Idempotency replays workflow writes safely.
- Public branding and admin brand publish/restore work.
- Refresh session rotation tests pass.
- `/api/mobile/bootstrap` and app preferences work.
- Desktop branding page passes unit, type, lint and build checks.
- Full backend and frontend baselines are green.
