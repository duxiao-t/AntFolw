# AntFlow Mobile

独立移动端应用，使用 React、Vite、React Router、TanStack Query 和 Ant Design Mobile。构建产物部署在同域 `/mobile/`，API 继续走 `/api/`。

## 本地开发

后端开发端口按移动端约定使用 `8081`：

```powershell
Set-Location D:\code\ant-flow\backend
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/antflow?stringtype=unspecified'
$env:SPRING_DATASOURCE_USERNAME='antflow'
$env:SPRING_DATASOURCE_PASSWORD='antflow'
$env:JWT_SECRET='local-dev-secret-0123456789-local-dev-secret'
$env:PORT='8081'
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

## 品牌与企业微信

移动端启动时读取已发布品牌配置；接口不可用或返回非法颜色时使用内置品牌兜底，不接受服务端下发任意 CSS。

企业微信平台适配已通过 `PlatformAdapter` 预留边界，当前阶段只启用浏览器行为。企业微信静默登录、JS-SDK 文件预览和绑定流程属于二期。
