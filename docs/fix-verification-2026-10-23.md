# 三项问题修复与验证（2026-10-23）

对应提交：`0093b01`（改密）、`5cf251e`（预览 iframe）、`110dd1d` + `a92bdc7`（导入功能）。
部署：`bf19518` 之后的最新 master，四容器 healthy，`/actuator/health` 200。

---

## 一、修改密码报 `internal error`

### 原因（后端日志实证）

```
HttpMessageNotReadableException: JSON parse error: Unrecognized field "confirmPassword"
(class com.antflow.auth.AccountController$PasswordChange),
not marked as ignorable (2 known properties: "newPassword", "currentPassword")
```

两层原因叠加：

1. **前端多发字段**：`frontend/src/pages/account/settings/index.tsx` 的 `onFinish` 把整个 antd 表单对象
   （含 `confirmPassword`）提交，而后端 `AccountController.PasswordChange` 只声明了
   `currentPassword` / `newPassword` 两个属性 → Jackson 反序列化阶段就抛异常。
2. **异常未映射**：`GlobalExceptionHandler` 只映射了 `MethodArgumentNotValidException`，
   `HttpMessageNotReadableException` 落到兜底的 `Exception` 分支 → **500 `internal error`**。
   业务层（`UserStatusService.changeOwnPassword`）的 `PASSWORD_INCORRECT` 根本没执行到。

**附带缺陷**：`onError` 判断 `error?.code === 'PASSWORD_INCORRECT'`，但 umi request 抛的是
AxiosError，业务码在 `error.response.data.code` → 「忘记原密码请联系管理员」的提示**从未出现**。

### 修复

| 文件 | 改动 |
| --- | --- |
| `frontend/src/pages/account/settings/index.tsx` | `onFinish` 只提交 `{currentPassword, newPassword}`；`onError` 改读 `error?.response?.data?.code` |
| `backend/.../auth/AccountController.java` | `PasswordChange` 加 `@JsonIgnoreProperties(ignoreUnknown = true)`（只放宽这一个入口，不动全局 `FAIL_ON_UNKNOWN_PROPERTIES`） |
| `backend/.../common/GlobalExceptionHandler.java` | 新增 `HttpMessageNotReadableException` → **400 `REQUEST_BODY_INVALID`**；不回显 Jackson 原文 |

### 验证

| 步骤 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- |
| 原密码填错 → 点「修改密码」 | 422 `PASSWORD_INCORRECT`，页面出现「原密码不正确」与「忘记原密码请联系管理员」，**不出现 internal error** | 接口 **422**；两个提示都在；无 internal error | ✅ |
| 原密码正确（改成同一密码，避免影响登录） | 200 + 「密码已修改」 | **200** + 提示出现 | ✅ |
| 单元测试 | 多余字段不再 500 | `AccountPasswordTest` 3 例（多余字段 200 / 业务错 422 / 坏 body 400）全绿 | ✅ |
| 前端测试 | 提交体恰好两字段 | 2 例（提交体 / 两次不一致不发请求）全绿 | ✅ |

---

## 二、新增「导入数据」

### 原因

功能不存在；且**导出不能当导入源**——导出值是 `detailText`（选项已翻成中文、明细表压成
`列=值；列=值`），而 `t_form_data.data` 需要**原始值**（`option_1`、行数组）。
`FormDataExport.java` 的注释已明确写"不是二次导入的数据源"。

### 设计（按确认的四项口径）

1. **仅历史补录**：直接插 `status='SUBMITTED'`，不建实例、不建待办、不写 revision
2. **先校验后导**：默认全量校验通过才落库（原子）；可勾「跳过错误行继续」
3. **提交人 = 导入人**（`created_by`）
4. **只接受系统模板**（表头第二行藏 fieldId，解决同名字段）

**模板**（xlsx 唯一格式）：

```
Sheet1「数据」  Row1 人类表头（加粗、冻结）
                Row2 fieldId（隐藏行）      ← 列匹配只认它
                Row3+ 数据
Sheet2「__antflow_meta」（隐藏）  A1 一行 JSON：formId / formVersion / schemaHash / fields[]
Sheet3「填写说明」 字段名 | 类型 | 必填 | 填写方式
```

**接口**：`GET /template?formId=`、`POST /inspect`、`POST /import`（均需 `form:data:import`）。

**反解析 26 类**：标量 / 选项 / 人员部门定位 / 结构化 JSON 四族。多值分隔接受 `、 , ， ; ；`；
选项按 label 反查 value（重名报候选、不猜）；人员按工号或姓名（重名报候选）。

**幂等**：模板可选列「业务单号」写入 `import_key`（**不写 `business_no`**——那会让
`BusinessNumberService` 的计数器不推进，将来正常提交撞号）。

### 验证

| 场景 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- |
| 模板下载 | 200 + xlsx | 200，6441 字节，6 列 | ✅ |
| 预检（2 行合法） | 可导入 2 / 错误 0 | 可导入 2 / 错误 0，原始值正确反解析 | ✅ |
| 提交导入 | 2 行落库 | `successCount=2`，库里 `status=SUBMITTED`、`created_by=1`、`company_id=1`、自动 `business_no`、`import_batch` 一致 | ✅ |
| 非 xlsx | 422 `IMPORT_FORMAT_UNSUPPORTED` | 422「只接受系统生成的 .xlsx 模板」 | ✅ |
| 空模板（无数据行） | 422 `IMPORT_EMPTY` | 422「模板里没有数据行」 | ✅ |
| 未发布表单 | 422 `IMPORT_FORM_NOT_PUBLISHED` | 422「只有已发布的表单可以导入数据」 | ✅ |
| 含 1 行非法值 + 原子模式 | 422 + **0 行落库** | 422 `IMPORT_VALIDATION_FAILED`，消息「第4行 验证-下拉单选 选项「不存在的选项」不存在」，行数 4→4 | ✅ |
| 含 1 行非法值 + 跳过模式 | 成功 1 / 失败 1 | `atomic=false, 成功 1, 失败 1`，失败定位 `row 4`，行数 4→5 | ✅ |
| 全部重复（业务单号已存在） | 422 `IMPORT_DUPLICATE` | 422「文件中的 1 行都已经导入过，没有新增数据」 | ✅ |
| 部分重复（1 旧 1 新） | 200 + skippedCount=1 | `successCount=1, skippedCount=1`，warning「1 行因业务单号或内容重复被跳过」，单号无重复 | ✅ |
| 工具栏按钮 | admin 可见「导入数据」 | 存在 | ✅ |
| 向导第 1 步 | 表单下拉 + 下载模板 | 正常，含「导入记录直接记为已提交」说明 | ✅ |
| 向导第 3 步 | 三色统计 + 列对账 | 「可导入 2 / 有错误 0 / 将跳过 0」，6 列全部「匹配」 | ✅ |

**错误定位示例**（跳过模式的失败明细）：`row 4` + `验证-下拉单选` + `IMPORT_VALUE_INVALID` +
「选项「不存在的选项」不存在」——行号是 Excel 真实行号（第 4 行）。

**规模**：初版 3 个类超 400 行，已按职责族拆成 13 个类（最大 371 行），
H7 后端超标类维持 2 个（`OptionSourceService` 751 既定保留、`MobileWorkflowMapper` 466 判定不拆）。

---

## 三、预览/发布页「localhost 拒绝了我们的连接请求」

### 原因（不是链接拼错）

预览是**同源 iframe**（`MobileFormPreview.tsx`：`<iframe src="/mobile/form-preview">`，根相对路径），
而 nginx 的 `X-Frame-Options: DENY` 阻止它渲染：

```
location /mobile/ { try_files $uri $uri/ /mobile/index.html; }   ← 预览走这里
        ↓ 内部重定向
location ~* /index\.html$ { add_header X-Frame-Options "DENY" always; ... }
```

Chrome/Edge 对被 XFO 拒绝的 iframe 显示「**\<host\> 拒绝了我们的连接请求**」，其中 host 取自
被框住文档的 URL —— 同源就是你访问的 host，所以从 localhost 访问就显示 localhost。
**全仓没有任何一处把 host 拼成 localhost**（前端无 `VITE_*` 参与 URL 拼接，后端没有 `previewUrl` 字段）。

### 修复

| 文件 | 改动 |
| --- | --- |
| `infra/docker/nginx.conf` | **3 处** `X-Frame-Options` DENY → `SAMEORIGIN`，并各加 `Content-Security-Policy: frame-ancestors 'self'` |
| `infra/mobile-nginx.example.conf` | 同上 **4 处** |
| `backend/.../auth/ExternalAuthProperties.java` | 加 `@PostConstruct`：URI 非法**直接启动失败**；scheme 限 http/https；指向本机只 WARN |
| `compose.yaml` / `README.md` | `ANTFLOW_PUBLIC_BASE_URL` 标明「非本机访问时必填」 |

> nginx 的 `add_header` **不继承**：某个 location 只要有自己的 `add_header`，就完全不继承
> server 级的那一组，所以 7 处必须逐个补齐（含 `location ~* /index\.html$` 这条直接元凶）。
> CSP 只下发 `frame-ancestors` 单个指令，**不引入 `default-src`**（SPA 有内联样式，整段会白屏）。

### 验证

**响应头**（改后部署）：

```
/                      X-Frame-Options: SAMEORIGIN   CSP: frame-ancestors 'self'
/index.html            X-Frame-Options: SAMEORIGIN   CSP: frame-ancestors 'self'
/mobile/form-preview   X-Frame-Options: SAMEORIGIN   CSP: frame-ancestors 'self'
/mobile/               X-Frame-Options: SAMEORIGIN   CSP: frame-ancestors 'self'
/approval/forms        X-Frame-Options: SAMEORIGIN   CSP: frame-ancestors 'self'
DENY 出现次数：0
```

**浏览器**：打开发布页，读取「手机端预览」iframe 内容 → **150 字**：
「‹ 填写表单 预览模式 部署验证表单 请填写以下内容，带 * 为必填项。验证-单行文本 验证-下拉单选 …」
—— iframe 真的渲染出来了，不再是被拒的灰框。✅

**单元测试**：`ExternalAuthPropertiesTest` 4 例（合法 origin / loopback 只 WARN / 非绝对地址 / 非 http scheme）。

---

## 四、未覆盖 / 需要注意

| 项 | 说明 |
| --- | --- |
| **媒体类字段导入** | 4 类上传控件不出列；若表单有**必填**媒体字段，模板下载时直接报 `IMPORT_FIELD_UNSUPPORTED`（整表单不可导入）。可选媒体字段会被跳过 |
| **跨批次重复（无业务单号）** | 无单号时只做文件内去重，预检的 warnings 会明说「未能确定公司归属，预检的重复判断可能不准确」 |
| **`schemaHash` 校验** | 表单 schema 一变，旧模板立即失效（需重新下载）。刻意从严，避免列错位 |
| **企微/OIDC 真实回跳** | 需真实 corpId / OIDC provider；本次只验证了配置校验与页面渲染 |
| **`import_batch` 整批撤回** | 数据层已落 `import_batch`，撤回操作暂无 UI（需走 DBA/工单） |
