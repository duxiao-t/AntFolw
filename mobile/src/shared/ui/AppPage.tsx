import type { CSSProperties, PropsWithChildren, ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import classes from "./AppPage.module.css";

export interface AppPageProps extends PropsWithChildren {
  title?: string;
  description?: string;
  flush?: boolean;
  style?: CSSProperties;
  toolbar?: ReactNode;
  action?: ReactNode;
  back?: boolean | string;
  onBack?: () => void;
  contentClassName?: string;
  contentStyle?: CSSProperties;
  variant?: "default" | "blank" | "head";
}

export function AppPage({
  children,
  title,
  description,
  flush = false,
  style,
  toolbar,
  action,
  back = true,
  onBack,
  contentClassName,
  contentStyle,
  variant = "default",
}: AppPageProps) {
  const navigate = useNavigate();
  const hasHeader = Boolean(title || action || back);
  const isBlank = variant === "blank";

  return (
    <main className={`af-page-frame ${classes.page}`} style={style}>
      {hasHeader ? (
        <header className="af-nav">
          <div>
            {back ? (
              <button
                type="button"
                className="af-nav__back"
                aria-label={typeof back === "string" ? back : "返回"}
                onClick={() => (onBack ? onBack() : navigate(-1))}
              >
                {"\u2039"}
              </button>
            ) : null}
          </div>
          {variant === "head" ? <span className="af-nav__spacer" /> : <h1 className="af-nav__title">{title}</h1>}
          <div className="af-nav__action-wrap">{action}</div>
        </header>
      ) : null}
      {!isBlank && (description || toolbar) ? (
        <div className={classes.intro}>
          {description ? <p className={classes.description}>{description}</p> : null}
          {toolbar}
        </div>
      ) : null}
      <div
        className={`${classes.content}${flush ? ` ${classes.flush}` : ""}${contentClassName ? ` ${contentClassName}` : ""}`}
        style={contentStyle}
      >
        {children}
      </div>
    </main>
  );
}

export default AppPage;
