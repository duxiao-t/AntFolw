# AntFlow Mobile

独立移动端应用，使用 React、Vite、React Router、TanStack Query 和 Ant Design Mobile。构建产物部署在同域 `/mobile/`，API 继续走 `/api/`。

> 注意：当前仓库位于 `E:\code\ant-flow`；下文命令中若出现旧路径 `D:\code\ant-flow\mobile`，请替换为 `E:\code\ant-flow\mobile`。


## 本地开发

后端开发端口按移动端约定使用 `8081`。附件存储默认并且必须走本机
MinIO；先在另一个终端启动 MinIO：

```powershell
$env:MINIO_ROOT_USER='minioadmin'
$env:MINIO_ROOT_PASSWORD='minioadmin'
minio.exe server E:\minio-data --address ':9000' --console-address ':9001'
```

MinIO 控制台：`http://localhost:9001`，默认账号密码：
`minioadmin / minioadmin`。

```powershell
Set-Location E:\code\ant-flow\backend
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/antflow?stringtype=unspecified'
$env:SPRING_DATASOURCE_USERNAME='antflow'
$env:SPRING_DATASOURCE_PASSWORD='antflow'
$env:JWT_SECRET='local-dev-secret-0123456789-local-dev-secret'
$env:PORT='8081'
$env:MOBILE_FILE_STORAGE='minio'
$env:MINIO_ENDPOINT='http://localhost:9000'
$env:MINIO_ACCESS_KEY='minioadmin'
$env:MINIO_SECRET_KEY='minioadmin'
$env:MINIO_BUCKET='antflow-mobile-files'
mvn -B spring-boot:run
```

移动端开发服务器使用 `5173`，Vite base 固定为 `/mobile/`：

```powershell
Set-Location D:\code\ant-flow\mobile
npm ci --no-audit --no-fund
npm run dev
```

访问 `http://localhost:5173/mobile/login`。

## 质量门

```powershell
Set-Location D:\code\ant-flow\mobile
npm run lint
npm test
npm run build
```

完整企业级门禁：`npm run check:enterprise`（lint + unit + build + bundle≤250KiB gzip）。


视觉回归测试使用 Playwright：

```powershell
Set-Location D:\code\ant-flow\mobile
npx playwright install chromium
npm run test:e2e -- shell-visual.spec.ts
```

## 部署

生产构建：

```powershell
Set-Location D:\code\ant-flow\mobile
npm ci --no-audit --no-fund
npm run build
```

将 `mobile/dist/` 的内容发布到站点根目录下的 `mobile/` 子目录，例如 `mobile/dist/index.html` 对应 `/usr/share/nginx/html/mobile/index.html`，`mobile/dist/assets/*` 对应 `/usr/share/nginx/html/mobile/assets/*`。Vite 产物使用绝对 `/mobile/` 路径，不能把 `dist/` 内容直接放到 Web 根目录。

示例 Nginx 配置见 `infra/mobile-nginx.example.conf`。

## 原单驳回重做（REWORK）

- 第一级驳回会生成申请人 `REWORK` 任务，原表单、附件、流程实例和单号保留。
- 移动端 API：`GET/PUT /api/mobile/rework-tasks/{id}`、`POST /api/mobile/rework-tasks/{id}/resubmit`。
- 普通同意/驳回接口会拒绝处理 `REWORK` 任务；申请人必须在原单上修改后重提。


## 品牌与企业微信

移动端启动时读取已发布品牌配置；接口不可用或返回非法颜色时使用内置品牌兜底，不接受服务端下发任意 CSS。

`PlatformAdapter` 会按 User-Agent 选择浏览器或企业微信实现。扫码、直接录音和定位是表单原生能力：普通浏览器使用摄像头、MediaRecorder 和 Geolocation，企业微信内切换到 JS-SDK；两端复用相同字段值、附件上传和提交校验。生产环境中浏览器硬件能力需要 HTTPS 和用户授权。
