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

- Flyway 当前版本为 V43；V40–V42 已冻结，后续迁移只前滚。
- `t_form_resource_grant` 只定义已发布表单的使用范围（目录、打开、草稿、发起）。
- `t_form_maintainer` 定义模板维护成员；非管理员还必须持有对应的表单/流程原子能力。
- 每张有效表单至少保留一名有效维护人；停用最后一名维护人由服务层和数据库双重拒绝。
- 表单数据与审批实例按各自能力和数据范围裁决，不依赖表单使用授权。
- 移动端 users/departments 选择器登录可见；桌面选择器使用 `/api/pickers/**`。

## 外部依赖

- PostgreSQL 17：主数据与迁移。
- MinIO：附件与归档。
- OIDC、企业微信：外部身份和组织集成，按配置启用。

## 决策索引

- D-20260917-authz-layers：三层鉴权与默认拒绝。
- D-20260917-flyway-forward-only：V40 后只前滚。
- D-20260917-menu-authority：后端裁决菜单可见性。
- D-20260918-form-usage-maintenance-separation：表单使用范围与模板维护职责分离。
