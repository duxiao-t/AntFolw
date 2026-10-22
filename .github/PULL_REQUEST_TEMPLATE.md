## 这个 PR 做了什么

<!-- 一句话说清目的。若是类拆分，写明"搬了什么、行为未变"。 -->

## 类体积检查单

> 适用于所有改动。类体积门禁见 `scripts/health-metrics.mjs` 的 H7，拆分方案见
> `docs/class-decomposition-execution-2026-10-20.md`。

- [ ] **没有新增超标类**：`node scripts/health-metrics.mjs` 通过，且 `h7Offenders()` 清单里
      **没有本次新出现的文件名**（新类 ≤400 行 / ≤25 方法；前端 ≤600 行）。
- [ ] **新类只有一个变化原因**：能说清"它负责什么、不负责什么"；不是只做转发的空壳。
- [ ] **`@Transactional` 只在用例层**：被拆出去的校验器/投影器/查询器不带事务注解，
      也不存在同类内部调用带注解方法（Spring 自调用会静默失效）。
- [ ] **契约文件零 diff**：`git diff --stat -- frontend/config/antflow-api.json` 为空。
      若不为空，说明改了接口，需单独说明并补 `ApiContractSnapshotTest`。
- [ ] **新测试文件 ≤800 行**，且用 `@Nested` 按主题分组。

## 若是类拆分，额外确认

- [ ] **一次提交只搬一个方法簇**，提交信息含行数/方法数前后对比（如 `1403 行/76 方法 → 250 行/18 方法`）。
- [ ] **公开签名未变**：public 方法、构造器参数、注解、类名均未改；被搬走的方法在原类保留委托。
- [ ] **未顺手改行为**：没有改 SQL、事务传播、错误码，没有顺带修 bug。
- [ ] **验证已跑**：
  ```bash
  node scripts/health-metrics.mjs
  cd backend && mvn -B test        # 或有 Docker 时跑全量
  ```

## 关联

<!-- 关联 issue / ADR / 方案文档章节 -->
