import { StrictMode, type ReactNode } from 'react';
import { unstableSetRender } from 'antd-mobile';
import { createRoot, type Root } from 'react-dom/client';
import App from './app/App';
import 'antd-mobile/es/global';
import './styles/global.css';

type RootContainer = (Element | DocumentFragment) & { __antdMobileRoot?: Root };

// antd-mobile's imperative overlays need an explicit React 19 renderer.
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

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('Root element #root not found');
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
