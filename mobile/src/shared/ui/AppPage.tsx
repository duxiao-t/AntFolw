import type { PropsWithChildren, CSSProperties, ReactNode } from 'react';

export interface AppPageProps extends PropsWithChildren {
  title?: string;
  description?: string;
  flush?: boolean;
  style?: CSSProperties;
  toolbar?: ReactNode;
}

const baseStyle: CSSProperties = {
  minHeight: '100dvh',
  padding: '16px 16px 24px',
  background: 'var(--af-color-bg)',
  color: 'var(--af-color-text)',
};

export function AppPage({ children, title, description, flush, style, toolbar }: AppPageProps) {
  const merged = flush ? style : { ...baseStyle, ...style };
  return (
    <main className="app-page" style={merged}>
      {(title ?? toolbar) ? (
        <header style={{ marginBottom: 16 }}>
          {title ? (
            <h1 style={{ margin: '0 0 4px', fontSize: '1.25rem' }}>{title}</h1>
          ) : null}
          {description ? (
            <p style={{ margin: 0, color: 'rgba(0,0,0,0.55)' }}>{description}</p>
          ) : null}
          {toolbar}
        </header>
      ) : null}
      {children}
    </main>
  );
}

export default AppPage;
