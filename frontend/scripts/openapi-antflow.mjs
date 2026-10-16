#!/usr/bin/env node
/**
 * AntFlow 契约 client 的生成 / 检查。
 *
 * 为什么不用 `npm run openapi`（umi 插件那条）当门禁：
 *   1. 它遍历 `openAPI` 数组的每一条 → 模板那条没设 `projectName`，会按 package.json 的
 *      name 再生成一个目录，跟既有 `src/services/ant-design-pro/` 的全局 `declare namespace API`
 *      撞名（声明合并 → Duplicate identifier）；
 *   2. `openAPIConfig.map((item) => genAllFiles(item))` **没有 await** → 命令返回不等于生成完成，
 *      拿它当门禁是竞态。
 * 这里直接调已装的同一个生成器（`@umijs/openapi` 的 `generateService`）并 await。
 *
 *   --write   生成到 `src/services/antflow/`
 *   --check   生成到临时目录，比对**文件集合 + 逐文件内容**，不写工作区
 *
 * 为什么 --check 不能就 `git diff --exit-code`：那比的是工作树与索引，覆盖不到**新增的未跟踪
 * 文件**——"漏提交一个新生成的服务文件"正是它抓不到的漏。所以这里自己比四个方向：
 * 新增 / 遗留（生成不出来）/ 内容不一致。
 */
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, sep } from 'node:path';
import { generateService } from '@umijs/openapi';
import {
  FRONTEND_ROOT,
  GENERATED_DIR,
  PROJECT_NAME,
  SERVERS_PATH,
  generateOptions,
} from './antflow-openapi.options.mjs';

const mode = process.argv[2];
if (mode !== '--write' && mode !== '--check') {
  console.error('用法：node scripts/openapi-antflow.mjs --write|--check');
  process.exit(2);
}

const shown = (dir) => relative(FRONTEND_ROOT, dir).split(sep).join('/');

/** 递归列出目录下所有文件（相对 root，统一 `/`，排序后用于比对）。目录不存在 = 空集合。 */
function listFiles(root) {
  const out = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else out.push(relative(root, full).split(sep).join('/'));
    }
  };
  if (existsSync(root)) walk(root);
  return out.sort();
}

const generateInto = (serversPath) => generateService(generateOptions(serversPath));
const sharedTypes = join(FRONTEND_ROOT, '..', 'shared', 'api.generated.d.ts');
const portableTypes = (directory) => '// Generated from the backend OpenAPI snapshot. Run npm --prefix frontend run openapi:generate.\n'
  + readFileSync(join(directory, 'typings.d.ts'), 'utf8')
    .replace('declare namespace AntFlowAPI', 'export declare namespace AntFlowAPI');

if (mode === '--write') {
  await generateInto(SERVERS_PATH);
  writeFileSync(sharedTypes, portableTypes(GENERATED_DIR));
  console.log(`[openapi] 已生成 ${shown(GENERATED_DIR)}/`);
  process.exit(0);
}

const scratch = mkdtempSync(join(tmpdir(), 'antflow-openapi-'));
try {
  await generateInto(scratch);
  const fresh = join(scratch, PROJECT_NAME);
  const freshFiles = listFiles(fresh);
  const committedFiles = listFiles(GENERATED_DIR);

  const missing = freshFiles.filter((file) => !committedFiles.includes(file));
  const stale = committedFiles.filter((file) => !freshFiles.includes(file));
  const changed = freshFiles
    .filter((file) => committedFiles.includes(file))
    .filter(
      (file) =>
        !readFileSync(join(fresh, file)).equals(readFileSync(join(GENERATED_DIR, file))),
    );

  const problems = [];
  if (!existsSync(sharedTypes) || readFileSync(sharedTypes, 'utf8') !== portableTypes(fresh)) {
    problems.push('shared/api.generated.d.ts 与后端契约不一致');
  }
  if (missing.length > 0) problems.push(`新增（生成了但没提交）：\n  ${missing.join('\n  ')}`);
  if (stale.length > 0) problems.push(`遗留（生成不出来，该删）：\n  ${stale.join('\n  ')}`);
  if (changed.length > 0) problems.push(`内容不一致：\n  ${changed.join('\n  ')}`);

  if (problems.length > 0) {
    console.error(`[openapi] 与提交的 ${shown(GENERATED_DIR)}/ 不一致：`);
    console.error(problems.join('\n'));
    console.error('\n跑 `npm run openapi:generate` 重新生成，review diff 后再提交。');
    process.exitCode = 1;
  } else {
    console.log(`[openapi] OK：${freshFiles.length} 个文件与提交一致`);
  }
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
