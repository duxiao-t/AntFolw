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
    // 这里曾经把 src/pages/user/login/login.test.tsx 排除在外（它依赖 Umi 的 jest 运行器与
    // @@/requestRecordMock，vitest 跑不了），后果是登录页**在 CI 里零覆盖**、还留下一份孤儿快照。
    // 该用例已替换为 vitest 原生的 src/pages/user/login/index.test.tsx，不再需要任何排除项。
    exclude: ['node_modules', 'dist', '.umi'],
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
    // 45s：`app.test.tsx` 里的 `getInitialState` 用例会真实挂载整棵树，单跑 3.6s、
    // 全量并行时在负载高的机器上会爬到 30s~43s（已在 30s 阈值上反复偶发超时）。
    // 这不是断言变慢，是 CPU 争用；与其让门禁随机变红，不如把阈值放到 45s——
    // 真正的死锁/挂起仍然会在 45s 被抓住。
    testTimeout: 45000,
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
