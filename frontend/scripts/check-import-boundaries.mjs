#!/usr/bin/env node
// 导入边界门禁：**只拦新增违规**。
//
// 规则表达的是"应该是什么样"，不是"现在是什么样"。当前存量违规冻在
// `frontend/import-boundaries.baseline.json`（随仓库提交）：今天照旧，谁再加一条同类违规就红。
// Biome 的 `noRestrictedImports` 表达不了"跨页面内部依赖"，也没有冻结机制（它没有 ArchUnit 那样的
// FreezingArchRule），所以这里用一个小图检查 + 提交在仓库里的基线，语义与后端
// `ArchitectureBoundaryTest` 对齐。
//
// 用法（在 frontend/ 下）：
//   node scripts/check-import-boundaries.mjs            # 校验
//   node scripts/check-import-boundaries.mjs --write    # 重建基线（只在有意接受新违规时跑）
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..'); // frontend/
const SRC = join(root, 'src');
const BASELINE = join(root, 'import-boundaries.baseline.json');

// 三条规则：每条给出"哪些源 zone 不许依赖哪些目标 zone"。同 zone 内不算违规（页面自己内部随便连）。
const RULES = [
  {
    id: 'no-cross-page-import',
    why: '页面之间不许互相 import 内部实现；要复用就上移到 components / registry / utils',
    from: (z) => z.startsWith('pages/'),
    to: (z) => z.startsWith('pages/'),
    sameZoneAllowed: true,
  },
  {
    id: 'components-must-not-import-pages',
    why: '通用组件不许反向依赖页面，否则页面一变组件就跟着漂，且组件再也无法独立测试',
    from: (z) => z === 'components',
    to: (z) => z.startsWith('pages'),
  },
  {
    id: 'utils-must-not-import-ui',
    why: '纯工具层不许依赖页面/组件，否则工具层被 UI 绑死，无法在非 React 环境复用',
    from: (z) => z === 'utils',
    to: (z) => z === 'components' || z.startsWith('pages'),
  },
];

/** zone = `pages/<顶层目录>`（页面按顶层目录分家）；其余取 `src/` 下一段。 */
function zoneOf(relToSrc) {
  const parts = relToSrc.split('/');
  if (parts[0] === 'pages' && parts.length > 2) return `pages/${parts[1]}`;
  return parts[0];
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) {
      if (name === '.umi' || name === 'node_modules') continue;
      walk(abs, out);
    } else if (/\.(ts|tsx)$/.test(name)) {
      out.push(abs);
    }
  }
  return out;
}

// 行首的 import/export ... from '...' + 副作用 import '...'。行锚定是为了不把注释里的示例当成真依赖
// （`// import x from 'y'` 行首是 `/`，匹配不上）。动态 import() 单独抓。
const FROM_RE = /^\s*(?:import|export)\b[^;]*?from\s+['"]([^'"]+)['"]/gm;
const SIDE_RE = /^\s*import\s+['"]([^'"]+)['"]/gm;
const DYN_RE = /\bimport\(\s*['"]([^'"]+)['"]\s*\)/g;

function importsOf(source) {
  const specs = new Set();
  for (const re of [FROM_RE, SIDE_RE, DYN_RE]) {
    re.lastIndex = 0;
    let m = re.exec(source);
    while (m !== null) {
      specs.add(m[1]);
      m = re.exec(source);
    }
  }
  return [...specs];
}

/** 把 import 说明符解析成 src 下的相对路径；外部包或跳出了 src 的返回 null。 */
function resolveSpecifier(fileAbs, spec) {
  let abs;
  if (spec.startsWith('@/')) abs = join(SRC, spec.slice(2));
  else if (spec.startsWith('.')) abs = resolve(dirname(fileAbs), spec);
  else return null;
  const rel = relative(SRC, abs).split('\\').join('/');
  return rel.startsWith('..') ? null : rel;
}

function collect() {
  const violations = [];
  for (const file of walk(SRC)) {
    const relFile = relative(SRC, file).split('\\').join('/');
    const fromZone = zoneOf(relFile);
    const source = readFileSync(file, 'utf8');
    for (const spec of importsOf(source)) {
      const relTarget = resolveSpecifier(file, spec);
      if (!relTarget) continue;
      const toZone = zoneOf(relTarget);
      for (const rule of RULES) {
        if (!rule.from(fromZone) || !rule.to(toZone)) continue;
        if (rule.sameZoneAllowed && fromZone === toZone) continue;
        violations.push(`${rule.id} | ${relFile} -> ${relTarget}`);
      }
    }
  }
  return [...new Set(violations)].sort();
}

const current = collect();

if (process.argv.includes('--write')) {
  writeFileSync(BASELINE, `${JSON.stringify(current, null, 2)}\n`, 'utf8');
  console.log(`基线已写入 ${relative(root, BASELINE)}：${current.length} 条存量违规`);
  process.exit(0);
}

const baseline = new Set(JSON.parse(readFileSync(BASELINE, 'utf8')));
const added = current.filter((v) => !baseline.has(v));
const removed = [...baseline].filter((v) => !current.includes(v));

if (added.length) {
  console.error('新增导入边界违规（要么改掉，要么说明为什么该加进基线）：\n');
  for (const v of added) {
    const rule = RULES.find((r) => v.startsWith(`${r.id} |`));
    console.error(`  ${v}\n    ↳ ${rule?.why ?? ''}`);
  }
  console.error(`\n当前 ${current.length} 条（基线 ${baseline.size} 条）`);
  process.exit(1);
}

if (removed.length) {
  console.log(`基线里有 ${removed.length} 条违规已经不存在了——跑 --write 把基线改小，别留着当库存：`);
  for (const v of removed) console.log(`  ${v}`);
}

console.log(`导入边界 OK：存量 ${current.length} 条，无新增`);
