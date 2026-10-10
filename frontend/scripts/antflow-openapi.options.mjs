// 生成与检查共用的一份配置（`openapi-antflow.mjs` 的两个模式都从这里取）。
// 用绝对路径，免得脚本被从别的目录调用时把文件写到 `process.cwd()` 底下。
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

/** 前端根目录（`frontend/`）。 */
export const FRONTEND_ROOT = resolve(here, '..');

/** 契约真源：后端快照测试（ApiContractSnapshotTest）导出、随代码提交。 */
export const SCHEMA_PATH = join(FRONTEND_ROOT, 'config', 'antflow-api.json');

export const SERVERS_PATH = join(FRONTEND_ROOT, 'src', 'services');
export const PROJECT_NAME = 'antflow';

/** 生成目录：`<serversPath>/<projectName>`（生成器的拼接规则）。 */
export const GENERATED_DIR = join(SERVERS_PATH, PROJECT_NAME);

/**
 * 命名空间必须跟模板那条 `openAPI`（默认 `API`）分开：生成的 `typings.d.ts` 是全局
 * `declare namespace`，两份同名会声明合并冲突。换个名字还有个好处——页面写
 * `AntFlowAPI.FormDefinition` 不用 import，不碰导入边界门禁。
 */
export const generateOptions = (serversPath = SERVERS_PATH) => ({
  schemaPath: SCHEMA_PATH,
  serversPath,
  projectName: PROJECT_NAME,
  namespace: 'AntFlowAPI',
  requestLibPath: "import { request } from '@umijs/max'",
});
