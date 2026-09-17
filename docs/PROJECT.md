# 项目上下文

## 项目目标

AntFlow 是面向企业的表单与审批平台，提供桌面管理端、移动审批端、流程运行时和第三方集成。

## 系统地图

| 区域 | 职责 | 事实来源 |
| --- | --- | --- |
| `backend` | Spring Boot API、审批引擎、RBAC、审计、迁移 | `backend/src/main` |
| `frontend` | Umi Max 桌面管理端 | `frontend/src`、`frontend/config` |
| `mobile` | Vite 移动审批端 | `mobile/src` |
| PostgreSQL | 业务数据、权限、任务及菜单版本 | `backend/src/main/resources/db/migration` |
| MinIO | 附件和审计归档 | `compose.yaml`、后端存储配置 |

## 关键流程

- 登录 → JWT 与数据库会话校验 → 请求主体快照 → 端点能力 → 资源授权 → 数据范围。
- 表单发布 → 版本快照 → 流程发起 → 任务流转 → 历史与事务消息。
- 服务端页面能力清单 + 数据库菜单结构 → 后端过滤导航 → 桌面注册表解析路由。

## 不变量与约束

- Flyway V40 已进入现存数据库；修正从 V41 前滚。
- 非管理员读取表单数据必须同时满足表单授权和对应能力的数据范围。
- `form:runtime:read` 等不可配范围能力视为 ALL，但不绕过表单资源授权。
- 移动端 users/departments 选择器登录可见；桌面选择器使用 `/api/pickers/**`。

## 外部依赖

- PostgreSQL 17：主数据与迁移。
- MinIO：附件与归档。
- OIDC、企业微信：外部身份和组织集成，按配置启用。

## 决策索引

- D-20260917-authz-layers：三层鉴权与默认拒绝。
- D-20260917-flyway-forward-only：V40 后只前滚。
- D-20260917-menu-authority：后端裁决菜单可见性。
