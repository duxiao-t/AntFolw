import { Outlet } from 'react-router-dom';

export function MobileShell() {
  // Real shell with bottom tab bar lands in Task 6; keep a passthrough so
  // the router boundary can render authenticated children during Task 2.
  return (
    <div className="mobile-shell">
      <Outlet />
    </div>
  );
}

export default MobileShell;
