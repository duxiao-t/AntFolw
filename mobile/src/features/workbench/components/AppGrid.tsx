import classes from './AppGrid.module.css';
import type { MobileApp } from '../../../shared/api/types';

export interface AppGridProps {
  apps: ReadonlyArray<MobileApp>;
  onSelect?: (app: MobileApp) => void;
}

function initials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return '?';
  return trimmed.charAt(0);
}

export function AppGrid({ apps, onSelect }: AppGridProps) {
  if (apps.length === 0) {
    return null;
  }
  return (
    <ul
      className={classes.appGrid}
      aria-label="常用应用"
      style={{ listStyle: 'none', margin: 0, padding: 0 }}
    >
      {apps.map((app) => (
        <li key={app.formId} style={{ display: 'contents' }}>
          <button
            type="button"
            className={classes.tile}
            onClick={() => onSelect?.(app)}
            aria-label={app.name}
            style={{ minHeight: 80, minWidth: 44 }}
          >
            <span className={classes.icon} aria-hidden="true">
              {app.iconUrl ? <img src={app.iconUrl} alt="" /> : initials(app.name)}
            </span>
            <span className={classes.name}>{app.name}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

export default AppGrid;
