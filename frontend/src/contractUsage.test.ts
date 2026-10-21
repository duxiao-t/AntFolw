import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 契约使用门禁（C6）：前端**功能代码**里出现的 `/api/...` 字面量，必须能在
 * `config/antflow-api.json` 里找到对应路径（按路径模板归一化后比对）。
 *
 * 为什么需要：契约生成链路只锁住了"后端 `/v3/api-docs` ↔ 提交的 json"
 * （`ApiContractSnapshotTest`），而**前端手写的 URL** 没有任何约束——拼错一个字母、
 * 后端改了路径没同步，都不会被任何测试发现，只会在运行时 404。
 *
 * 为什么不扫全仓：下面 `LEGACY_SCAFFOLD` 里的文件是 Ant Design Pro 脚手架残留
 * （未接入 `config/routes.ts`），引用的是 `/api/fake_list`、`/api/notices` 这类
 * 根本不存在的假接口。它们清掉之前显式排除，避免门禁一上来就被噪声淹没。
 */
const LEGACY_SCAFFOLD = [
  'services/ant-design-pro/', // 脚手架的假接口集合（含 login.ts 的旧版登录）
  'pages/list/', // 脚手架 list 系列页面
  'pages/table-list/', // 脚手架 table-list 页面
  'pages/account/', // 脚手架的账户中心/设置
  'pages/profile/', // 脚手架的 profile/basic（未接入路由）
  'pages/dashboard/', // 脚手架的 dashboard/{analysis,monitor,workplace}（未接入路由）
  'pages/form/', // 脚手架的 form/{basic-form,advanced-form}（未接入路由）
  // 已知缺口（不是"没问题"，是"还没做"）：注册页仍是脚手架，调用的 /api/register 后端并不存在。
  // 放在这里是为了让门禁不被噪声卡住；真要上线注册，先补后端接口，再把这条删掉。
  'pages/user/register/',
  'requestErrorConfig.ts', // 只出现 `/api/auth/` 前缀（用来判定登录态），不是具体端点
];

const SRC = join(__dirname);
const SPEC = JSON.parse(
  readFileSync(join(__dirname, '..', 'config', 'antflow-api.json'), 'utf8'),
) as { paths?: Record<string, unknown> };

/** 把 `${id}`、`{id}`、查询串、结尾斜杠统一成可比较的模板。 */
function normalize(url: string): string {
  return url
    .replace(/\$\{[^}]*\}/g, '*')
    .replace(/\{[^}]*\}/g, '*')
    .replace(/[?#].*$/, '')
    .replace(/\/+$/, '');
}

/**
 * 段级匹配：`*` 是通配段，可以匹配对面的**任意一段字面量**。
 *
 * 没有这条会误报：`/api/tasks/${id}/${action}` 这类"动作是变量"的调用，
 * 契约里对应的是 `/api/tasks/{id}/approve`、`/api/tasks/{id}/reject` 两个具体路径。
 */
function matches(left: string, right: string): boolean {
  const a = left.split('/');
  const b = right.split('/');
  if (a.length !== b.length) return false;
  return a.every((segment, index) => segment === '*' || b[index] === '*' || segment === b[index]);
}

const specTemplates = Object.keys(SPEC.paths ?? {}).map(normalize);
const specList = [...specTemplates].sort();

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === 'node_modules' || entry.startsWith('.umi')) continue;
      walk(full, out);
    } else if (/\.(ts|tsx)$/.test(entry) && !/\.(test|spec)\.(ts|tsx)$/.test(entry)) {
      out.push(full);
    }
  }
  return out;
}

describe('API 契约使用', () => {
  it('功能代码里的 /api 字面量都能在契约里找到', () => {
    const misses: string[] = [];
    for (const file of walk(SRC)) {
      const rel = relative(SRC, file).split(sep).join('/');
      if (LEGACY_SCAFFOLD.some((prefix) => rel.startsWith(prefix))) continue;
      const source = readFileSync(file, 'utf8');
      // 只认被引号包住的完整字面量；模板串里的 ${...} 由 normalize 折叠
      for (const match of source.matchAll(/['"`](\/api\/[^'"`\s]*)['"`]/g)) {
        const url = match[1];
        const template = normalize(url);
        if (specTemplates.some((candidate) => matches(candidate, template))) continue;
        misses.push(`${rel} → ${url}`);
      }
    }
    expect(
      misses,
      `以下前端调用在 config/antflow-api.json 里找不到对应路径。\n` +
        `要么改对 URL，要么先同步契约（mvn -B test -Dtest=ApiContractSnapshotTest ` +
        `-Dantflow.openapi.write=true）：\n${misses.join('\n')}\n\n` +
        `契约里最接近的候选（人工判断用）：\n${specList.slice(0, 5).join('\n')}`,
    ).toEqual([]);
  });
});
