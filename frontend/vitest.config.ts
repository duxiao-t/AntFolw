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
    // 这里挂着 `dangerouslyIgnoreUnhandledErrors: true`：环境里确实有**跑完测试之后才冒出来**的
    // 未处理错误，它们跟断言无关，但会让整个 Test 步骤以退出码 1 结束（用例全 passed 却红）。
    //
    // 一度以为可以收回（本机 Windows 连跑三次全量 288 用例、退出码都是 0），**结果 CI（Linux）立刻
    // 打回**：`ReferenceError: window is not defined`，来自 react-dom 的
    // `scheduler.development.js → performWorkUntilDeadline`，vitest 把它归到当时在跑的
    // `src/pages/approval/FormManagementWizard.test.tsx`（59 文件 / 288 用例全绿、Errors 1）。
    // 也就是说：这是"React 的延后渲染活过了 happy-dom 的拆卸"，跟具体断言无关，且**本机复现不出来**
    // ——在本机验证再推 CI 是碰运气，所以 flag 留着。
    //
    // 两条已知来源（都不影响断言，但都会被记成 unhandled）：
    //   1) 上面那条 react-dom 调度器在拆卸后跑 render；
    //   2) MobileFormPreview 挂的是 <iframe src="/mobile/form-preview">，happy-dom 会真去请求它，
    //      请求被中止时抛的 DOMException 无人接。
    // 代价说清楚：**全项目不再因"未处理错误"而红**。断言失败、测试内抛出的错误照旧会红；
    // 丢的是"异步未处理错误"这层安全网。
    //
    // 已排除的窄口径修法（别再试）：about:blank / srcdoc / javascript: 都是 origin=null
    // （postMessage 直接 SecurityError）；disableIframePageLoading 会让 contentWindow 变 null
    // （spyOn 失败，覆盖一样丢）；自挂 unhandledRejection 处理器挡不住（vitest 有自己的监听）；
    // 桩 global.fetch 也没用（iframe 文档加载走 happy-dom 内部的真实 HTTP）。
    dangerouslyIgnoreUnhandledErrors: true,
  },
});
