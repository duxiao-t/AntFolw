import type { MobileApp } from "../../../shared/api/types";

export interface AppGridProps {
  apps: ReadonlyArray<MobileApp>;
  onSelect?: (app: MobileApp) => void;
}

function initials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "?";
  return trimmed.charAt(0);
}

export function AppGrid({ apps, onSelect }: AppGridProps) {
  if (apps.length === 0) {
    return (
      <p style={{ margin: 0, fontSize: 11, color: "var(--af-color-muted)" }}>还没有常用应用</p>
    );
  }
  return (
    <ul className="af-app-grid" aria-label="常用应用" style={{ listStyle: "none", margin: 0, padding: 0 }}>
      {apps.map((app) => (
        <li key={app.formId} style={{ display: "contents" }}>
          <button
            type="button"
            className="af-app-grid__tile"
            onClick={() => onSelect?.(app)}
            aria-label={app.name}
          >
            <span className="af-app-grid__icon" aria-hidden="true">
              {app.iconUrl ? <img src={app.iconUrl} alt="" style={{ width: "100%", height: "100%", borderRadius: 10, objectFit: "cover" }} /> : initials(app.name)}
            </span>
            <span className="af-app-grid__name">{app.name}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

export default AppGrid;
