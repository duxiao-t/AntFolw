#!/usr/bin/env node
// 六个健康指标门禁（见 docs/architecture-review-2026-10-09.md 的 B4）：**冻结真实基线，只禁止变差**。
//
// 三条纪律：
//   1. 测不到的量记 `null`，**不写 0**。"零违规"和"没查"必须能分辨——报告里专门点了这条。
//   2. 基线是实测出来的，不是目标值。目标（比如差距收敛到 0）写在报告里，不写进基线。
//   3. 变好不拦（但会提示把基线收紧）；变差当场红。
//
// 用法（在仓库根）：
//   node scripts/health-metrics.mjs            # 校验
//   node scripts/health-metrics.mjs --write    # 重建基线（只在有意接受时跑）
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BASELINE = join(ROOT, 'docs', 'health-metrics.baseline.json');
const p = (...parts) => join(ROOT, ...parts);
const read = (path) => (existsSync(path) ? readFileSync(path, 'utf8') : null);

function walk(dir, test, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) {
      if (['.umi', 'node_modules', 'target', 'dist'].includes(name)) continue;
      walk(abs, test, out);
    } else if (test(name)) out.push(abs);
  }
  return out;
}
const rel = (abs) => relative(ROOT, abs).split('\\').join('/');

/** 在 `src` 里找所有引用了 `needle` 的文件（相对仓库根）。 */
function referencing(files, needle) {
  return files.filter((f) => readFileSync(f, 'utf8').includes(needle)).sort();
}

// ── H1 字段支持差集：后端 spec 声明的类型，两端注册表各缺哪些 ──────────────────
function h1() {
  const spec = JSON.parse(read(p('fixtures', 'field-semantics', 'field-types.json')));
  const declared = spec.fieldTypes.map((t) => t.id);
  const between = (src, end, re, file) => {
    const text = read(p(file));
    const body = text.slice(text.indexOf(src), text.indexOf(end, text.indexOf(src)));
    return [...body.matchAll(re)].map((m) => m[1]);
  };
  const desktop = between(
    'export const formRegistry', '\n};', /^\s{2}([a-z_][a-z_0-9]*):/gm,
    'frontend/src/registry/formRegistry.ts',
  );
  const mobile = between(
    'export const registeredFields', '\n];', /\bfield\(\s*'([a-z_]+)'/g,
    'mobile/src/features/forms/schema/fieldRegistry.ts',
  );
  return {
    backend: declared.length,
    desktop: desktop.length,
    mobile: mobile.length,
    missingDesktop: declared.filter((id) => !desktop.includes(id)).sort(),
    missingMobile: declared.filter((id) => !mobile.includes(id)).sort(),
  };
}

// ── H2 共用语义测试接入数：哪几个渠道在读同一份 fixture ────────────────────────
// 渠道 = 桌面 / 移动 / Java / SQL。SQL 那一格现在没人读（BI 视图还是自己一套口径）。
function h2() {
  const channels = { desktop: 'frontend/src', mobile: 'mobile/src', java: 'backend/src' };
  const wired = [];
  for (const [name, dir] of Object.entries(channels)) {
    const files = walk(p(dir), (n) => /\.(ts|tsx|java)$/.test(n));
    if (referencing(files, 'field-semantics').length > 0) wired.push(name);
  }
  return { channels: wired.sort() };
}

// ── H3 手写重复业务 DTO：独立声明的 FormDefinition 副本数 ──────────────────────
function h3() {
  const files = [
    ...walk(p('frontend', 'src'), (n) => /\.(ts|tsx)$/.test(n)),
    ...walk(p('mobile', 'src'), (n) => /\.(ts|tsx)$/.test(n)),
  ];
  const declarations = [];
  for (const file of files) {
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      if (/^\s*(?:export\s+)?(?:interface|type)\s+FormDefinition\b/.test(line)) {
        declarations.push(`${rel(file)}:${i + 1}`);
      }
    });
  }
  return { declarations: declarations.sort() };
}

// ── H4 可写运行时实现数：legacy 与 V2 两套都在写 ───────────────────────────────
// 活跃 v1 实例数要连生产库查，CI 里拿不到 —— 记 null，附上查法，别拿 0 冒充。
function h4() {
  const engine = read(p('backend', 'src', 'main', 'java', 'com', 'antflow', 'engine', 'ProcessEngine.java'));
  const v2 = read(p('backend', 'src', 'main', 'java', 'com', 'antflow', 'engine', 'WorkflowRuntimeV2.java'));
  return {
    writableRuntimes: (engine ? 1 : 0) + (v2 ? 1 : 0),
    activeV1Instances: null,
    how: 'SELECT engine_version, status, count(*) FROM t_process_instance GROUP BY 1,2;',
  };
}

// ── H5 专用架构边界门禁：Java/TS 各几类，加必需 V2 的可选注入口 ─────────────────
function h5() {
  const archunit = read(p('backend', 'src', 'test', 'java', 'com', 'antflow', 'ArchitectureBoundaryTest.java'));
  const tsGates = read(p('frontend', 'scripts', 'check-import-boundaries.mjs'));
  const optionalV2 = read(p('backend', 'src', 'main', 'java', 'com', 'antflow', 'engine', 'ProcessEngine.java'));
  return {
    javaGates: (archunit?.match(/FreezingArchRule\.freeze\(/g) ?? []).length,
    tsGates: (tsGates?.match(/^\s{4}id: '/gm) ?? []).length,
    requiredFalseRuntimeInjections:
      (optionalV2?.match(/@Autowired\(required = false\)\s*\n\s*private WorkflowRuntimeV2/g) ?? []).length,
  };
}

// ── H6 移动端体积预算：阈值不许放松；实测值由 mobile 自己的预算门禁产出 ─────────
function h6() {
  const gate = read(p('mobile', 'scripts', 'check-bundle-budget.mjs'));
  const num = (name) => Number(gate?.match(new RegExp(`${name}\\s*=\\s*(\\d+)`))?.[1]);
  const report = read(p('mobile', 'test-results', 'bundle-budget.json'));
  return {
    entryBudgetKiB: num('ENTRY_BUDGET_KIB'),
    lazyBudgetKiB: num('LAZY_BUDGET_KIB'),
    measured: report ? JSON.parse(report) : null,
  };
}

// ── 比较：每条自己定义"什么是变差"，不做通用引擎 ───────────────────────────────
function compare(base, now) {
  const worse = [];
  const subset = (name, b, n) => {
    for (const item of n) if (!b.includes(item)) worse.push(`${name} 新增：${item}`);
  };
  subset('H1 desktop 缺的字段类型', base.H1.missingDesktop, now.H1.missingDesktop);
  subset('H1 mobile 缺的字段类型', base.H1.missingMobile, now.H1.missingMobile);
  subset('H2 已接入共用 fixture 的渠道', base.H2.channels, now.H2.channels);
  if (now.H3.declarations.length > base.H3.declarations.length) {
    worse.push(`H3 手写 FormDefinition 副本 ${base.H3.declarations.length} → ${now.H3.declarations.length}`);
  }
  if (now.H4.writableRuntimes > base.H4.writableRuntimes) {
    worse.push(`H4 可写运行时 ${base.H4.writableRuntimes} → ${now.H4.writableRuntimes}`);
  }
  for (const key of ['javaGates', 'tsGates']) {
    if (now.H5[key] < base.H5[key]) worse.push(`H5 ${key} ${base.H5[key]} → ${now.H5[key]}`);
  }
  if (now.H5.requiredFalseRuntimeInjections > base.H5.requiredFalseRuntimeInjections) {
    worse.push(`H5 可选注入的必需 V2 ${base.H5.requiredFalseRuntimeInjections} → ${now.H5.requiredFalseRuntimeInjections}`);
  }
  if (now.H6.entryBudgetKiB > base.H6.entryBudgetKiB) worse.push('H6 入口预算被放宽');
  if (now.H6.lazyBudgetKiB > base.H6.lazyBudgetKiB) worse.push('H6 异步块预算被放宽');
  return worse;
}

const current = { H1: h1(), H2: h2(), H3: h3(), H4: h4(), H5: h5(), H6: h6() };

if (process.argv.includes('--write')) {
  const json = JSON.stringify(current, null, 2);
  writeFileSync(BASELINE, `${json}\n`, 'utf8');
  console.log(`基线已写入 ${rel(BASELINE)}`);
  console.log(json);
  process.exit(0);
}

if (!existsSync(BASELINE)) {
  console.error(`基线缺失：${rel(BASELINE)}。用 --write 建一份（并在评审里说明每个数字怎么来的）。`);
  process.exit(1);
}
const baseline = JSON.parse(read(BASELINE));
const worse = compare(baseline, current);

if (worse.length) {
  console.error('健康指标变差：\n');
  for (const line of worse) console.error(`  ✗ ${line}`);
  console.error('\n当前：');
  console.error(JSON.stringify(current, null, 2));
  process.exit(1);
}

const h4note = current.H4.activeV1Instances === null ? '，H4 活跃 v1 实例数仍是"未测"（不当作 0）' : '';
console.log(
  `健康指标 OK：H1 缺口 桌面 ${current.H1.missingDesktop.length}/移动 ${current.H1.missingMobile.length}、` +
    `H2 ${current.H2.channels.length}/4 渠道、H3 ${current.H3.declarations.length} 份副本、` +
    `H4 ${current.H4.writableRuntimes} 套运行时、H5 Java ${current.H5.javaGates}/TS ${current.H5.tsGates} 类门禁、` +
    `H6 预算 ${current.H6.entryBudgetKiB}/${current.H6.lazyBudgetKiB} KiB${h4note}`,
);
