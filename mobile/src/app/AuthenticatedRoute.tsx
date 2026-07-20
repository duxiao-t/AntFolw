import { Outlet } from 'react-router-dom';

export function AuthenticatedRoute() {
  // Real auth gate lands in Task 4; this Task 2 stub is a pure passthrough so
  // the router boundary can mount lazy children without coupling to the auth
  // store yet. Task 4 will replace it with a state-driven redirect.
  return <Outlet />;
}

export default AuthenticatedRoute;
