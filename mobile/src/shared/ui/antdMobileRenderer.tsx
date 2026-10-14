import type { ReactNode } from 'react';
import { unstableSetRender } from 'antd-mobile';
import { createRoot, type Root } from 'react-dom/client';

type RootContainer = (Element | DocumentFragment) & { __antdMobileRoot?: Root };

// Application and tests must register the same React 19 renderer for imperative overlays.
unstableSetRender((node: ReactNode, container: Element | DocumentFragment) => {
  const rootContainer = container as RootContainer;
  const root = rootContainer.__antdMobileRoot ?? createRoot(container);
  rootContainer.__antdMobileRoot = root;
  root.render(node);

  return async () => {
    await Promise.resolve();
    root.unmount();
    delete rootContainer.__antdMobileRoot;
  };
});
