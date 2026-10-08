import { join } from 'node:path';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  resolve: {
    alias: {
      '@': join(__dirname, 'src'),
      '@root': join(__dirname),
      '@@': join(__dirname, 'src', '.umi'),
    },
  },
  test: {
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./tests/setupTests.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    // Exclude Umi integration tests that depend on @umijs/max test infrastructure
    // These require Umi's Jest runner and cannot be used with Vitest directly
    exclude: [
      'src/pages/user/login/login.test.tsx',
      'node_modules',
      'dist',
      '.umi',
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/.umi/**',
        'src/services/ant-design-pro/**',
        'src/**/*.d.ts',
        'src/**/index.style.ts',
      ],
    },
    passWithNoTests: true,
    // 15s 在负载下不够：app.test.tsx 这类重挂载的用例单跑 7/7 全过、在全量里跑到 18s+ 就超时，
    // 表现成"每次红的文件都不一样"的 flake。
    testTimeout: 30000,
    // 这里曾经挂着 `dangerouslyIgnoreUnhandledErrors: true`：MobileFormPreview 挂的是
    // <iframe src="/mobile/form-preview">，happy-dom 会真去请求它，请求被中止时抛的 DOMException
    // 无人接，于是被记成 unhandled error。那个 flag 的代价是**全项目不再因未处理错误而红**，
    // 而这层安全网值钱（断言失败照样红，丢的是"异步未处理错误"）。
    //
    // 现在收回（vitest 4.1.10，连跑三次全量 59 文件 / 288 用例都是绿的，退出码 0）：那条
    // DOMException 仍然会打到控制台，但不再让 run 失败。若它在负载下又真的变红，按这个顺序试：
    //   1) 把握手抽成"接收 contentWindow 的函数"单测，DOM 级用例只留 src/allow 两个属性断言；
    //   2) 都不行再把这个 flag 加回来，并把当时的原因写在它旁边。
    // 已排除的窄口径修法（别再试）：about:blank / srcdoc / javascript: 都是 origin=null
    // （postMessage 直接 SecurityError）；disableIframePageLoading 会让 contentWindow 变 null
    // （spyOn 失败，覆盖一样丢）；自挂 unhandledRejection 处理器挡不住（vitest 有自己的监听）；
    // 桩 global.fetch 也没用（iframe 文档加载走 happy-dom 内部的真实 HTTP）。
  },
});
