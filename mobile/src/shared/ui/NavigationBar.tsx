import type { CSSProperties, ReactNode } from "react";
import { useNavigate } from "react-router-dom";

export interface NavigationBarProps {
  title: string;
  back?: boolean | string;
  onBack?: () => void;
  action?: ReactNode;
  variant?: "default" | "blank" | "head";
}

const navStyle: CSSProperties = {};

export function NavigationBar({ title, back = true, onBack, action, variant = "default" }: NavigationBarProps) {
  const navigate = useNavigate();

  return (
    <header className={`af-nav ${variant === "head" ? "af-nav--head" : ""}`} style={navStyle}>
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
      {variant === "head" ? <span className="af-nav__spacer" /> : <span className="af-nav__title">{title}</span>}
      <div className="af-nav__action-wrap">{action}</div>
    </header>
  );
}

export default NavigationBar;
