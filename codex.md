# codex.md

Agent / Codex 在本仓库工作时的速查说明。更完整的领域约定见 `CLAUDE.md`。

## 仓库结构

```text
ant-flow/
├── backend/     # Spring Boot 3 + Java 17 + MyBatis-Plus + Flyway + PostgreSQL
├── frontend/    # Umi Max 4 桌面管理端（表单/流程设计、组织、任务）
├── mobile/      # 独立移动端（Vite + React + Ant Design Mobile），base `/mobile/`
├── infra/       # docker-compose、nginx 示例
└── docs/        # superpowers specs/plans、企业级验收记录
```

**先 `cd` 进模块再执行命令**，不要在仓库根直接跑 `mvn` / `npm`。

## 本地运行

### 数据库

```powershell
Set-Location infra
docker compose up -d
```

### 后端

默认 `8080`；移动端联调常用 `8081`：

```powershell
Set-Location backend
$env:PORT='8081'
mvn -B spring-boot:run
# 测试
mvn -B test
```

种子账号：`admin / ant.design`、`bob / ant.design`。

### 桌面前端

```powershell
Set-Location frontend
npm ci
npm start          # http://localhost:8000，代理 /api → 后端
npm run biome:lint
npm test
npm run tsc
npm run build
```

### 移动端

```powershell
Set-Location mobile
npm ci
npm run dev        # http://localhost:5173/mobile/login ，Vite base=/mobile/
npm run check:enterprise   # lint + unit + build + bundle≤250KiB gzip
npm run test:e2e           # Playwright 四视口；本地 workers=1
```

E2E 默认端口优先 `5174`（`E2E_PORT`），避免与本机 5173 冲突。

## 移动端架构要点

- **部署**：同域 `/mobile/` 静态资源 + `/api/` 后端；生产把 `mobile/dist/` 挂到站点 `mobile/` 子目录。
- **壳层**：底部三栏工作台 / 待办 / 我的；详情与表单页无底栏。
- **路由**（basename `/mobile`）：
  - `/login`
  - `/workbench` `/tasks` `/profile`
  - `/apps` `/apps/favorites`
  - `/forms/drafts` `/forms/:code` `/forms/:code/self-select` `/forms/:code/confirm` `/forms/:code/success/:instanceId`
  - `/tasks/:taskId` `/processes/:instanceId` `/profile/security`
- **状态**：TanStack Query + feature store；access token 内存态，刷新走统一 HTTP 包装。
- **品牌**：`BrandProvider` 拉 `/api/public/branding`，非法色/失败用内置 fallback，不接受任意 CSS。
- **平台**：`PlatformAdapter`（Browser 实现；WeCom 二期），**页面禁止**直接判断企业微信环境。
- **安全**：实例/任务/附件均需归属校验；上传白名单类型与大小；发起/审批支持 Idempotency-Key。

## 品牌配置

- 移动端只消费**已发布**品牌 DTO → CSS variables。
- 桌面端品牌编辑/发布能力以平台基础分支相关提交为准；客户端始终可 fallback。
- 默认主色企业级对比：`#0b57d0`。

## 已知非目标（移动端一期）

- 手机端表单/流程设计、组织权限管理
- 企业微信免登 / JS-SDK / 应用消息（仅 adapter 边界）
- 完整主题编辑器、报表看板
- 依赖 PWA 安装或 Service Worker 才能完成的核心审批路径

## 验收文档

见 `docs/mobile-enterprise-verification.md`。

## Git 提交格式

中文约定：`类型(范围): 描述`  
示例：`功能(移动端): …` / `测试(移动端): …` / `文档: 更新企业级移动端运行与验收说明`  
作者历史常用：`AntFlow Bot <bot@antflow.local>`。

## 移动端 UI 设计稿与现状差异（2026-07-23）

设计稿源：`/.superpowers/brainstorm/mobile-design-20260718/content/`（含 `all-key-pages-gallery.html`、`core-page-map.html`、`enterprise-visual-directions.html`、`mobile-data-flow.html` 等）。本次以 `all-key-pages-gallery.html` 为最终对齐基准，遵循「A. 清晰秩序」企业蓝配色（`#1677ff`）。

### 关键差异清单

1. **登录（01-login）**：设计为蓝底圆角「A」色块 logo + 标题「登录 AntFlow」+ 副标题「移动审批，让每一次流转清晰可见」，输入框与登录按钮直接放在 bg 上、**不**包白卡，底部「企业微信免登录（二期）」链接；当前把表单放进白卡，副标题用「AntFlow 审批」。修复：去掉外层白卡，改用透明背景 + 单边框 input，按钮全宽主色。

2. **工作台（02-workbench）**：设计为顶部白底横条「工作台 / AntFlow 科技 / 头像」 → 蓝色品牌欢迎卡 → 常用应用 4×2 宫格（蓝绿交替）→ 最近流程带状态标签两条记录；当前只有 H1 欢迎语、无品牌蓝卡、应用宫格图标单色、空状态为主。修复：恢复顶部横条 + 蓝色品牌欢迎卡 + 蓝绿交替宫格 + 「全部应用 ›/查看全部 ›」链接。

3. **全部应用（03-apps）**：设计是「‹ 全部应用 / 管理」导航 + 搜索 + 分类 chip + 多分组卡片（最近使用 / 人事申请 / 行政申请）；当前是「应用目录」+ 选择型列表。修复：把「应用目录」改为浏览型「全部应用」分组宫格，「常用应用管理」独立入口承担编辑。

4. **常用应用管理（04-favorites）**：设计为「‹ 管理常用应用 / 完成」导航 + 「已添加」拖动排序 + 「可添加」分组；当前是「常用应用」+ ↑↓移除。修复：标题改为「管理常用应用」，操作改回拖拽 + 删除。

5. **动态表单（05-form）**：设计为「‹ 请假申请 / 草稿」导航 + 「请假信息」卡 + 「附件」dashed 上传区 + 底部固定「下一步」；当前把草稿保存放在 toolbar 按钮、字段未分组、无附件块。修复：字段分组到白卡，草稿入口改右上「草稿」链接，底部固定「下一步」。

6. **自选审批人（06-self-select）**：设计为「‹ 选择审批人 / 确定」导航 + 搜索 + 「直属主管 单选」+ 「最近选择」分组卡；当前用 antd-mobile `Selector`，缺少搜索和最近选择区。修复：搜索 + 节点卡（人员行 + radio），右上「确定」按钮。

7. **提交确认（07-confirm）**：设计为应用图标卡 + 「申请摘要 修改」KV + 「审批流程」时间线 + 底部「确认提交」；当前是「表单内容」+「审批人」两个块。修复：恢复设计稿版式，按钮文字「确认提交」。

8. **提交成功（08-success）**：设计为居中绿色 ✓ + 「提交成功」+ 双行说明 + 并排「返回工作台 / 查看进度」按钮；当前是「流程已发起」+ 垂直按钮。修复：居中布局 + 绿色 ✓ + 双行说明 + 并排按钮。

9. **任务中心（09 / 13 / 15）**：设计为「待办」H1 + 角标 + Tab「待我处理 / 我发起的 / 已处理」+ 任务卡（meta + 标题 + 摘要 + 申请人头像 + 状态 tag）；当前是「任务中心」+ Tab「待办 / 已处理 / 我发起的」+ 两行 label/value 卡片。修复：标题恢复「待办」，Tab 顺序改为「待我处理 / 我发起的 / 已处理」，卡片恢复设计稿版式。

10. **审批详情（10-task-detail）**：设计为「‹ 审批详情 / •••」导航 + 顶部白卡（图标 + 标题 + 申请人 + 状态条）+ 申请内容 KV + 审批进度时间线 + 附件 + 底部三按钮「更多 / 驳回 / 同意」；当前用 AppPage 拆块 + 仅「驳回 / 同意」两按钮。修复：恢复导航 + 顶部摘要 + KV + 时间线 + 附件 + 底部三按钮。

11. **同意 / 驳回弹层（11 / 12）**：设计为半透明蒙层 + 圆角底 sheet（h3 居中 + textarea + 并排两个按钮）；当前底部 sheet 用 Button block 垂直堆叠。修复：sheet 改为并排按钮。

12. **我发起的（13-started）**：与任务中心相同 Tab 版式，需切到「我发起的」。

13. **流程进度（14-process-detail）**：设计为「‹ 流程进度 / •••」导航 + 顶部摘要 + 审批进度时间线 + 申请摘要 KV + 底部全宽红「撤回流程」；当前标题「请假申请」+「状态：进行中」+ 分块卡 + 「撤回」按钮。修复：恢复导航 + 摘要 + 进度 + 摘要 KV，按钮全宽红底。

14. **已处理（15-done）**：与任务中心相同 Tab 版式，需切到「已处理」。

15. **我的（16-profile）**：设计为「我的」+ 个人卡 + 统计三栏（待处理 / 进行中 / 本月完成）+ 菜单组 1（常用应用管理 / 草稿箱 / 账号与安全）+ 菜单组 2（帮助与反馈 / 关于 AntFlow）+ 退出登录；当前是单菜单「待办 2 / 草稿箱 / 账号与安全」。修复：恢复统计 + 双菜单 + 退出登录。

16. **草稿箱（17-drafts）**：标题/版式基本一致；当前日期用 ISO 字符串，设计稿用「保存于今天 11:36」「保存于昨天 18:20」自然语言。修复：日期格式化。

17. **账号与安全（18-security）**：设计为登录账号 / 修改密码 / 登录设备 / 企业微信绑定 / 隐私与数据 + 底部退出当前账号；当前只展示登录设备 + 退出当前设备。修复：补齐全部行 + 底部退出红按钮。

18. **离线/失败（19-offline）**：设计是占满视口的离线空状态（图标 + 「网络暂时不可用」+ 「已保留未提交的表单内容\n网络恢复后可继续操作」+ 「重新加载」按钮）；当前是顶部横幅 + 工作台仍可滚动。修复：把 NetworkStatusProvider 改为居中空状态而非横幅。

### 共享结构调整

- `AppPage` 需要新增「无底栏 + 沉浸式」版式（用于审批详情、流程进度、提交成功、登录）。
- `tokens.css` 增加轻量阴影与状态色 token（orange/dot 颜色）。
- 任务中心 Tab 顺序倒置 → 影响 task-card 截图基线。

### 提交拆分建议

1. `修复(移动端): 重做登录页视觉与设计稿对齐`
2. `修复(移动端): 重做工作台欢迎卡与应用宫格`
3. `修复(移动端): 重做应用目录与常用应用管理版式`
4. `修复(移动端): 重做表单填写与自选审批人布局`
5. `修复(移动端): 重做提交确认与提交成功视觉`
6. `修复(移动端): 重做任务中心 Tab 顺序与卡片版式`
7. `修复(移动端): 重做审批详情与同意驳回弹层`
8. `修复(移动端): 重做流程进度与撤回版式`
9. `修复(移动端): 重做我的中心与账号安全页面`
10. `修复(移动端): 重做离线失败空状态与共享 AppPage`
11. `测试(移动端): 刷新关键页面视觉基线`
