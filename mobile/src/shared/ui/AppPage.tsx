import type { CSSProperties, PropsWithChildren, ReactNode } from "react";
import { useNavigate } from "react-router-dom";

export interface AppPageProps extends PropsWithChildren {
  title?: string;
  description?: string;
  flush?: boolean;
  style?: CSSProperties;
  toolbar?: ReactNode;
  action?: ReactNode;
  bottomBar?: ReactNode;
  back?: boolean | string;
  onBack?: () => void;
  contentClassName?: string;
  contentStyle?: CSSProperties;
  variant?: "default" | "blank" | "head";
  tabbar?: boolean;
  brandHeader?: boolean;
  testId?: string;
}

export function AppPage({
  children,
  title,
  description,
  flush = false,
  style,
  toolbar,
  action,
  bottomBar,
  back = true,
  onBack,
  contentClassName,
  contentStyle,
  variant = "default",
  tabbar = false,
  brandHeader = false,
  testId,
}: AppPageProps) {
  const navigate = useNavigate();
  const hasHeader = Boolean(title || action || back);
  const isBlank = variant === "blank";

  return (
    <div className="app-screen" style={style} data-testid={testId}>
      {hasHeader ? (
        <header className={`app-bar${brandHeader ? " app-bar--brand" : ""}`}>
          <div>
            {back ? (
              <button
                type="button"
                className="app-bar__back"
                aria-label={typeof back === "string" ? back : "返回"}
                onClick={() => (onBack ? onBack() : navigate(-1))}
              >
                {"\u2039"}
              </button>
            ) : null}
          </div>
          {variant === "head" ? <span /> : <div className="app-bar__title">{title}</div>}
          <div>{action}</div>
        </header>
      ) : null}
      <main
        className={`page${flush ? " page--flush" : ""}${tabbar ? "" : " page--no-tabbar"}${contentClassName ? ` ${contentClassName}` : ""}`}
        style={contentStyle}
      >
        {!isBlank && (description || toolbar) ? (
          <div className="app-intro">
            {description ? <p>{description}</p> : null}
            {toolbar}
          </div>
        ) : null}
        {children}
      </main>
      {bottomBar}
    </div>
  );
}

export default AppPage;
