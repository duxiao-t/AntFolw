# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Workspace Layout

This directory is a **workspace of two independent projects** — neither is a sub-package of the other. Each has its own `package.json`, lockfile, and dependencies. Always `cd` into the project you intend to work on; do not run commands from the workspace root.

```
ant-flow/
├── ant-design-pro-master/   # React admin boilerplate (see ant-design-pro-master/CLAUDE.md)
└── wflow-master/           # Vue 2 workflow designer
```

## Projects at a Glance

### `ant-design-pro-master/`

React 19 + TypeScript + Umi Max 4 enterprise boilerplate on antd 6 / ProComponents 3, built with utoopack. **Has its own CLAUDE.md** — read `ant-design-pro-master/CLAUDE.md` first. Key entry points: `npm start`, `npm run build`, `npm run lint` (Biome + tsc). Ships two built-in Claude Code skills: `/pro-upgrade` and `/antd`.

### `wflow-master/`

Front-end of `wflow-web` (by willianfu): a visual form designer + approval-flow designer for OA workflows. Vue 2.6 + Vue Router 3 + Vuex 3 + Element UI 2 + vuedraggable + codemirror 6 + signature_pad. Designed for normal business users — no BPMN jargon. The accompanying Java/Spring Boot backend lives at `wflow-master/server/code/` (Maven, Spring Boot 2.0.4, MyBatis-Plus 3.5, Java 8); the schema is in `wflow-master/server/sql/wflow.sql`. The OSS edition is front-end only — back-end support is paid `wflow-pro`.

#### Run / build (wflow)

```bash
cd wflow-master
npm install
npm run serve         # dev server on port 88 (see vue.config.js)
npm run build         # production build → dist/
npm run lint          # eslint (plugin:vue/essential)
```

- **Node**: vue-cli-service v4.5 requires Node 14–16; newer Node may need `--openssl-legacy-provider`.
- **Less**: `src/assets/theme.less` is auto-injected globally by `style-resources-loader` (see `vue.config.js`). Edit this file to change theme tokens used across all components.
- **axios**: all API calls go through `src/api/request.js`. Per-domain modules: `design.js`, `org.js`, `process.js`.

#### Source map (wflow)

```
src/
├── api/                       # axios wrappers — design.js, org.js, process.js
├── assets/                    # theme.less, iconfont, images
├── components/common/         # shared widgets: Ellipsis, OrgPicker, Tip, WDialog
├── router/                    # routes
├── store/                     # Vuex — designer state (formItems, process tree, etc.)
├── utils/CustomUtil.js        # helpers
└── views/
    ├── workspace/             # user-facing workspace
    └── admin/
        ├── FormsPanel.vue     # form/process list
        ├── LayoutHeader.vue
        └── layout/
            ├── FormDesign.vue + form/      # form designer surface
            └── ProcessDesign.vue + process/ # process designer surface
    └── common/
        ├── form/   components | config | components == renderable form fields
        │   ├── components/  AmountInput, DateTime, DeptPicker, FileUpload, SignPannel, TableList, UserPicker, ...
        │   └── config/      per-component config panels (e.g. TextInputConfig.vue)
        └── process/          ApprovalNode, CcNode, ConcurrentNode, ConditionNode, DelayNode, TriggerNode, RootNode, EmptyNode
            ├── nodes/        node renderers
            └── config/       node config panels (ApprovalNodeConfig, ConditionGroupItemConfig, ...)
```

**Convention**: every component type comes in **two siblings** — a renderer under `views/common/{form|process}/{components|nodes}/` and a config panel under `views/common/{form|process}/config/`. `ComponentExport.js` and `ComponentsConfigExport.js` are the registries; new field/node types must be registered here or they won't appear in the designer palette.

**State shape** (Vuex store — see `src/store/index.js` and README §"设计器数据"): `{ formId, formName, logo, settings: {commiter, admin, sign, notify}, group, formItems[], process{}, remark }`. This whole object is what you POST to the backend.

#### wflow gotchas

- `npm run serve` defaults to **port 88** (`vue.config.js`). If 88 is busy, dev server will still start but on another port — watch the log.
- `lintOnSave: false` — ESLint is not wired to the dev server. Run `npm run lint` manually.
- `productionSourceMap: true` — sourcemaps ship to production; reduce by toggling if size matters.
- `signature_pad` is pinned to `3.0.0-beta.4`; the new API differs from v4+. Don't bump without auditing `SignPannel.vue`.
- Front-end is the open-source piece; the real process engine is **not** in this repo. Integration points (URLs in `api/*.js`) are stubs against the pro backend.

## Working in this workspace

- **Pick a project, then `cd`.** Never run `npm`/`mvn` from `E:\code\ant-flow` directly — the lockfiles aren't workspaces.
- The two projects use different stacks (React/TS vs Vue 2/JS), different package managers (npm), and unrelated lint configs (Biome vs ESLint). Don't conflate them.
- If asked to work on "antd" or "pro", go into `ant-design-pro-master/` and read its CLAUDE.md first; the `/antd` and `/pro-upgrade` skills live there.
