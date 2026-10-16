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
//
// 三个要点（前两条是审计指出的、已经踩过的坑）：
//  1. **分母来自完整渠道声明**，不是已接入的数量。写死"/4"而名单里根本没有 sql，就会让人把
//     "3/4" 读成"还缺一块"，其实口径从来没定义清楚。
//  2. **按维度分开记**。并集 4/4 只能说明"四条通道各自被某份 fixture 覆盖过"，**不能**读成
//     "同一套语义已经四端全部对拍"——可见性只有桌面/移动两份，显示文本是 Java/SQL 两份。
//  3. 渠道判定按目录，且 java 只扫 `backend/src/main`：扫整个 `backend/src` 会把真库集成测试
//     目录也算进来，与 sql 渠道重复记账。
const H2_DECLARED_CHANNELS = ['desktop', 'mobile', 'java', 'sql'];
const H2_DIMENSIONS = {
  visibility: {
    fixture: 'fixtures/field-semantics/visibility.json',
    consumers: { desktop: ['frontend/src'], mobile: ['mobile/src'] },
  },
  fieldTypes: {
    fixture: 'fixtures/field-semantics/field-types.json',
    consumers: {
      desktop: ['frontend/src'],
      mobile: ['mobile/src'],
      java: ['backend/src/main'],
    },
  },
  displayText: {
    fixture: 'fixtures/field-semantics/display-text.json',
    consumers: {
      java: ['backend/src/test/java/com/antflow/form/runtime'],
      sql: ['backend/src/test/java/com/antflow/integration'],
    },
  },
  validation: {
    fixture: 'fixtures/field-semantics/validation.json',
    consumers: {
      desktop: ['frontend/src/registry'],
      mobile: ['mobile/src/features/forms/schema'],
      java: ['backend/src/test/java/com/antflow/form'],
    },
  },
};

function h2() {
  const byDimension = {};
  const union = new Set();
  for (const [dimension, spec] of Object.entries(H2_DIMENSIONS)) {
    const wired = [];
    for (const [channel, dirs] of Object.entries(spec.consumers)) {
      const hit = dirs.some((dir) => {
        const files = walk(p(dir), (n) => /\.(ts|tsx|java)$/.test(n));
        return referencing(files, spec.fixture.split('/').at(-1)).length > 0;
      });
      if (hit) {
        wired.push(channel);
        union.add(channel);
      }
    }
    byDimension[dimension] = wired.sort();
  }
  return { declared: H2_DECLARED_CHANNELS, union: [...union].sort(), byDimension };
}

// ── H3 手写重复业务 DTO：独立声明的 FormDefinition 副本数 ──────────────────────
//
// 只数**独立对象定义**（`type FormDefinition = { ... }` / `interface FormDefinition {`）——
// 那才是"本可以引用契约、却自己又写了一遍"。另外两类**不算**：
//   1. 生成目录（`frontend/src/services/`）：那里的声明是从契约真源生成的，**它就是契约**；
//      不排除的话，本指标会把"接通契约"读成变差。（旧版逐行正则扫 `\.ts$`，把
//      `typings.d.ts` 也算了进来。）
//   2. **派生别名**（`= AntFlowAPI.FormDefinition` / `Pick<...>` / `X & {...}`）：它引用生成
//      类型，没有重复定义任何字段。旧版把这两类混在一起数，于是"改名成派生"和"删掉"分不清。
//
// 分类规则抽成纯函数，`--self-test` 里直接钉住，免得以后又混回去。
function classifyFormDefinition(line) {
  const decl = /^\s*(?:export\s+)?(interface|type)\s+FormDefinition\b(.*)$/.exec(line);
  if (!decl) return null;
  const [, keyword, rest] = decl;
  if (keyword === 'interface') return 'independent';
  return rest.replace(/^\s*=\s*/, '').startsWith('{') ? 'independent' : 'derived';
}

function h3() {
  const files = [
    ...walk(p('frontend', 'src'), (n) => /\.(ts|tsx)$/.test(n)),
    ...walk(p('mobile', 'src'), (n) => /\.(ts|tsx)$/.test(n)),
  ].filter((file) => !rel(file).startsWith('frontend/src/services/'));
  const declarations = [];
  const derived = [];
  for (const file of files) {
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      const kind = classifyFormDefinition(line);
      if (kind === 'independent') declarations.push(`${rel(file)}:${i + 1}`);
      else if (kind === 'derived') derived.push(`${rel(file)}:${i + 1}`);
    });
  }
  return { declarations: declarations.sort(), derived: derived.sort() };
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
  // H2 的方向与 H1 **相反**：H1 新增"缺口"才叫变差，H2 新增"渠道"是变好——丢渠道才是变差。
  // 这里曾经借用上面的 subset()，方向是反的：加 sql 渠道被判变差、丢 java 渠道反而通过。
  for (const channel of base.H2.union) {
    if (!now.H2.union.includes(channel)) worse.push(`H2 渠道丢失：${channel}`);
  }
  for (const [dimension, wired] of Object.entries(base.H2.byDimension)) {
    for (const channel of wired) {
      if (!(now.H2.byDimension[dimension] ?? []).includes(channel)) {
        worse.push(`H2 ${dimension} 维度丢失渠道：${channel}`);
      }
    }
  }
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

// 方向自检：这两条守的正是"比较写反了"这个已经犯过的错。用 `__probe__` 这种不存在的渠道名，
// 免得真渠道恰好已接入时断言变成空跑。
if (process.argv.includes('--self-test')) {
  const assert = (condition, message) => {
    if (!condition) {
      console.error(`✗ ${message}`);
      process.exit(1);
    }
    console.log(`✓ ${message}`);
  };
  const withUnion = (union) => ({ ...current, H2: { ...current.H2, union } });
  assert(
    compare(current, withUnion([...current.H2.union, '__probe__'])).length === 0,
    '新增渠道必须判为变好（不报变差）',
  );
  assert(
    compare(current, withUnion(current.H2.union.filter((c) => c !== current.H2.union[0]))).length > 0,
    '丢失基线里的渠道必须报变差',
  );
  // 分维度同样只许增不许减。
  const [dimension, wired] = Object.entries(current.H2.byDimension)[0];
  assert(
    compare(current, {
      ...current,
      H2: { ...current.H2, byDimension: { ...current.H2.byDimension, [dimension]: wired.slice(1) } },
    }).length > 0,
    `分维度丢失渠道必须报变差（${dimension}）`,
  );
  // H3 分类：独立对象定义才算副本；引用生成类型的派生别名不算。
  assert(
    classifyFormDefinition('type FormDefinition = {') === 'independent',
    'H3：对象体声明算独立副本',
  );
  assert(
    classifyFormDefinition('interface FormDefinition {') === 'independent',
    'H3：interface 算独立副本',
  );
  assert(
    classifyFormDefinition('type FormDefinition = AntFlowAPI.FormDefinition;') === 'derived',
    'H3：生成类型派生不算副本',
  );
  assert(
    classifyFormDefinition('type FormDefinition = Pick<AntFlowAPI.Summary, "id">;') === 'derived',
    'H3：Pick 派生不算副本',
  );
  assert(classifyFormDefinition('const x = 1;') === null, 'H3：非声明返回 null');
  assert(
    !current.H3.declarations.some((d) => d.startsWith('frontend/src/services/')),
    'H3：生成目录（src/services）不计入副本',
  );
  // 方向：加回一份独立副本必须报变差；多一个派生别名不算变差。
  assert(
    compare(current, {
      ...current,
      H3: { ...current.H3, declarations: [...current.H3.declarations, 'frontend/src/x.ts:1'] },
    }).length > 0,
    'H3 新增独立副本必须报变差',
  );
  assert(
    compare(current, {
      ...current,
      H3: { ...current.H3, derived: [...current.H3.derived, 'frontend/src/x.ts:1'] },
    }).length === 0,
    'H3 派生别名不判变差',
  );
  process.exit(0);
}

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
// H2 同时给并集与**分维度明细**：并集只说明"每条通道被某份 fixture 覆盖过"，
// 不能读成"同一套语义已四端全部对拍"。
const h2breakdown = Object.entries(current.H2.byDimension)
  .map(([dimension, wired]) => `${dimension}: ${wired.join('/') || '无'}`)
  .join('；');
console.log(
  `健康指标 OK：H1 缺口 桌面 ${current.H1.missingDesktop.length}/移动 ${current.H1.missingMobile.length}、` +
    `H2 渠道并集 ${current.H2.union.length}/${current.H2.declared.length}（${h2breakdown}）、` +
    `H3 ${current.H3.declarations.length} 份手写副本（另有 ${current.H3.derived.length} 个派生别名）、` +
    `H4 ${current.H4.writableRuntimes} 套运行时、H5 Java ${current.H5.javaGates}/TS ${current.H5.tsGates} 类门禁、` +
    `H6 预算 ${current.H6.entryBudgetKiB}/${current.H6.lazyBudgetKiB} KiB${h4note}`,
);
