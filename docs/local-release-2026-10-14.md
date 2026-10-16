# 本机发布与验收：P1-b、COMPANY 门槛、移动端修复

记录时间：2026-10-14 18:10（+08:00）。对象仅为本机 antflow-local 栈，入口 `127.0.0.1:7070`。
本记录不证明生产已发布；P4a 的生产窗口、历史草稿人工确认及生产量级预演仍按原 runbook 执行。

## 发布与回退材料

旧镜像 tag 均在 build 之前留存：`antflow-local-backend:before-company-renderer-20261014-174143`、
`antflow-local-web:before-company-renderer-20261014-174143`。
数据库备份位于仓外临时目录 `antflow-release-20261014-174143`，文件 `pre-company-renderer.dump`；
SHA256 为 `1EC6413C9D2123E345C540C0DD6C3B2B8FCCA0E097A8D01AD7B5D78049C7D794`。
已恢复到独立验证库，44 条实例、47 条填报记录与原库一致，原版本 V54。

JAR 和两端 dist 均在宿主机重建，随后构建 Dockerfile.local 的 backend/web 镜像并更新两个容器。
本机 Flyway V54→V55 成功，健康检查 `{"status":"UP"}`；数据库与 MinIO 卷保留。
实际容器镜像：

| 容器 | 镜像 ID |
| --- | --- |
| antflow-local-backend-1 | sha256:e053c7942505a80ecb237a003e8042434d9999d4cbe98933e848d4bd22414a18 |
| antflow-local-web-1 | sha256:a069ae0bf9d9ceb2250076f8d472ff8adf8f671d8e8dcd6616c1546158cf95dd |

## 回归结果

| 检查 | 最终结果 |
| --- | --- |
| 后端全量（独立全新探针库） | 610 条，0 失败，0 错误，2 skipped；退出 0 |
| 桌面全量 | 65 文件 / 338 用例；退出 0 |
| 移动全量 | 47 文件 / 368 用例，无 unhandled errors；退出 0 |
| 类型/构建/契约/导入边界/健康指标 | 全通过；移动入口 gzip 69.61 KiB，预算通过 |
| Lint | 桌面 4 条、移动 10 条既有 warnings；无新增 error/warning |

另跑 antd 专用检查：47 deprecated / 45 usage、退出 0；这些是已记录的兼容和布局迁移事项。
历史交接里的 44 deprecated 是当时统计，本轮没有把专用检查写成零警告。

## 浏览器验收

Chrome DevTools 的共享 profile 被占用，使用仓内已有 Playwright 的独立 Chrome 会话。
测试真实部署页面和真实 API，不使用网络 mock。

1. 移动预览 Toast 两次显示并自动卸载，单选/多选弹层可打开、选择、关闭。
2. 专用设计器草稿保存 text maxLength=300 / trim=true，刷新读回一致，未知属性 customProperty 保留。
3. 新表单初始 companyIds 为空；通过桌面选择公司及全员工开关保存，刷新后 UI 与服务端一致。
4. 已授权公司的员工可以打开、保存草稿；另一公司的同角色员工直开和创建草稿均 404。
5. 同一员工从甲公司调到乙公司后，原草稿仍可保存、进入确认页并真实提交。提交记录 54 归公司 8，
   company_inferred=false；整个验收过程页面异常 0。

验收使用专用公司、部门、账号和表单。账号已停用，验收发布表单 34 已下线，
配置草稿 33 和测试提交保留以供核对；原有已发布业务表单未修改。

## 证据与后续

原始日志位于 `backend/target/release-backend-full.log`、`release-frontend-full.log`、
`mobile-final-full.log`；浏览器结果与截图位于 `backend/target/browser-acceptance/`。
这些都是本机忽略产物，未提交真实会话、凭据或数据库备份。

本次浏览器还定位并修复了移动会话丢 deptId 和 employee 授权双入口问题，均有行为回归。
下一片方案见 `docs/p4-next-step-plan.md`；公司完整业务数据隔离与 BI 隔离仍未实施。
