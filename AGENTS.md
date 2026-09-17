# 项目协作说明

## 优先读取

1. 读取 `docs/PROJECT.md` 和 `docs/HANDOFF.md`。
2. 检查 Git 状态、分支、HEAD 和近期提交。
3. 只查阅 `docs/DECISIONS.md` 中与任务相关的决策。

## 常用命令

- 后端测试：`mvn -B -f backend/pom.xml test`
- 桌面检查：`npm --prefix frontend run tsc && npm --prefix frontend test && npm --prefix frontend run build`
- 移动检查：`npm --prefix mobile test && npm --prefix mobile run build`
- 本地运行：后端使用 `local` profile；完整环境见 `README.md`。

## 项目不变量

- Flyway 已执行迁移只前滚，不修改历史文件。
- 后端端点能力、资源授权和数据范围共同构成安全边界；前端与菜单不是安全边界。
- 受控表通用查询默认受数据权限拦截；自助查询必须使用专用语句并显式豁免。

## 文档约定

- 代码、配置、schema 和测试优先于文档。
- 持久架构事实变化时更新 `docs/PROJECT.md`；重要决策写入 `docs/DECISIONS.md`。
- 不记录秘密或生产数据。
