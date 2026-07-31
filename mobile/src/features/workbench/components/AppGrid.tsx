import type { MobileApp } from "../../../shared/api/types";

export interface AppGridProps {
  apps: ReadonlyArray<MobileApp>;
  onSelect?: (app: MobileApp) => void;
  onMore?: () => void;
}

export function AppGrid({ apps, onSelect, onMore }: AppGridProps) {
  return (
    <div className="stripe-card app-grid">
      {apps.map((app) => (
        <button key={app.formId} type="button" className="app-tile" onClick={() => onSelect?.(app)} aria-label={app.name}>
          <span className="app-tile__icon">{app.iconUrl ? <img src={app.iconUrl} alt="" /> : app.name.trim().charAt(0) || "?"}</span>
          <span className="app-tile__name">{app.name}</span>
        </button>
      ))}
      <button type="button" className="app-tile app-tile--more" onClick={onMore}><span className="app-tile__icon">+</span><span className="app-tile__name">更多</span></button>
    </div>
  );
}

export default AppGrid;
