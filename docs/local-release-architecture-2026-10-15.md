# 本机全路线发布与验收

2026-10-15 11:14（+08:00），仅本机 `antflow-local`，入口 `127.0.0.1:7070`。
分支 `feat/architecture-completion`，基线 `c14f055`，通过验收后提交；生产未执行。

## 最终检查

| 范围 | 实测结果 |
| --- | --- |
| 后端全量并打包，全新独立 PostgreSQL | 710 条，0 failure / error，2 条既有 skip，退出 0 |
| 桌面全量 | 70 文件 / 420 条，退出 0 |
| 移动全量 | 48 文件 / 443 条，退出 0 |
| 类型、构建、真实契约与两份生成物、导入边界、健康指标、体积 | 全通过；移动入口 gzip 69.61 KiB，预算 160/180 KiB 未放松 |
| Lint | 桌面 4、移动 10 条既有 warnings；antd 47 deprecated / 43 usage，退出 0 |

后端覆盖公司 ALL/CUSTOM 与快照、本人/任务例外、NULL/inferred 拒绝、附件及导出/报表，
74 条字段校验 fixture、checkbox Java/SQL 显示、真实媒体探测、旧文件补测、缺工具与超限回滚，
终态/旧轮次/任务类型、同意/驳回竞态、并行合流、重提、加签撤回，
企微失租停写/过期接管/恢复幂等/失败拉取不误停用、outbox 重放/审计/回滚和既有 OIDC 规则。
BI 使用实际受限 session authorization，五个视图可读，基表读取、视图更新、建表与角色提权均拒绝。

两条 skip 为既有应用 smoke 和需专用环境开关的 MinIO lifecycle；
本次真实 MinIO 上的上传/关联/拒绝下载已通过下方部署后验收。未冒充外部企业账号联调。
桌面测试仍保留既有 unhandled-errors 配置与 iframe abort 日志，未放宽门禁；
移动全量无 unhandled error。

## 备份、恢复与发布

构建前保留旧镜像：
`antflow-local-backend:before-architecture-20261015-105859`、
`antflow-local-web:before-architecture-20261015-105859`。

备份位于仓外临时目录 `antflow-architecture-20261015-105859`，不提交备份或凭据。
数据库 dump SHA256：
`54F367D1D25AFC3A7DA56D567724078F640B724F7D4102CD409E6ECE4C049088`。
恢复到独立库 `antflow_architecture_restore_20261015105859`，原库与恢复库一致：
44 实例 / 50 填报 / 270 文件行 / 9 附件关联。
MinIO 整卷快照独立解包，687 个物理文件逐一 SHA256 比对一致；
快照 SHA256：
`4B040392857A1F244C4F9D1EC0EF78F91CA7A6E1E62FDE901CD73911DEC529A2`。

先构建 JAR、桌面和移动 dist，再 build backend/web。Compose 启动客户端请求曾停滞，
结束该客户端后逐个 `docker start` 成功；未清卷或修改 Docker 配置。
V55→V58 前滚成功，ffprobe 与中文字体可用，health UP。

| 产物 | SHA256 / 运行镜像 ID |
| --- | --- |
| JAR | `ead1a65eed2f1e76a8a3317ad461eee1e4ba5bb6d4bcd8801364c0c9530e3ccd` |
| backend | `sha256:825da547a4423d3bdc70c6af3e2ac2f872ba466e30476b56c0814330623e1839` |
| web（桌面+移动） | `sha256:0e162ee02d08fad52db44ed318bc8d6afb4927188a9da7569f75f7dad205393b` |

## 真实浏览器与数据库

独立 Chrome/Playwright 访问部署页面，业务 API 未 mock，最终八组检查通过、页面异常 0。
验收前几次脚本因创建账号响应只回 id、音频配置最小 5 秒、夹具未授附件能力、按钮实际名为
“提交”而中断；均修正脚本/夹具，没有为通过验收放宽产品规则。

1. 同角色双公司非 admin ALL 台账/count 仅本公司；B 的直提详情及运行时选项不可越权。
   关联附件的元数据/下载也同样拒绝跨公司。
2. 真正 6 秒 WAV 上传返回实测 6 秒，伪造 0.1 秒不能绕过 5 秒上限；
   正式提交 422 且原草稿保留，0.25 秒音频可正常关联提交。
3. 移动真实页面填写→确认→提交成功。B 员工可读/批准 A 的指派任务，
   新实例 45 为 V2 / APPROVED / company_id=26 / inferred=false；重复批准 422。
4. A 成员调到 B 后可读本人旧提交，公司快照仍为 26；把专用附件提交标记 inferred 后，
   本人正文与本人已关联附件均 404，数据没有自动删除或改公司。
5. 监控页失败消息不含 payload 或原始错误；无独立能力重放 403；
   管理员点击重新投递后原 UUID 事件变 DELIVERED，attempts=1，审计一次。

填报 65/66 分别保留 company_id 26/27。成功验收成员 35/36 均 DISABLED，
表单 40/41 均 DEPRECATED；失败脚本创建的专用账号/表单也在 finally 停用/下线。
本机 V1 仍有历史终态记录，活跃/重提/待办/可恢复作业预检零行，
该结果不是生产证据，未删除 V1 写路径。NULL/inferred 原有记录保持原样等待人工确认。

忽略产物：`backend/target/architecture-*.log`、`architecture-release-state.json`、
`architecture-browser/result.json` 与截图。成功以 result.json 的 passed=true 为准。
生产离线构建包：`backend/target/architecture-delivery-20261015.tar.gz`，
包含已验收 JAR、两端 dist、Docker/Compose 配置、预检 SQL、发布/回退文档和提交/校验清单；
不包含运行凭据、业务数据库或附件备份。包内 RELEASE.json 固定最终提交及构建产物摘要。
生产预检与回退见 `architecture-release-runbook.md`；生产、真实企微/OIDC 联调及历史人工确认仍未执行。
