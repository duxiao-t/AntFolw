/**
 * @see https://umijs.org/docs/max/access#access
 * */
export default function access(
  initialState: { currentUser?: API.CurrentUser } | undefined,
) {
  const { currentUser } = initialState ?? {};
  // AntFlow: pure role-based gating. Org admin / form design / process
  // design / publish all live behind canAdmin. End users (role `user`)
  // see fill-form and task-center pages.
  const roles: string[] = (currentUser as any)?.roles ?? [];
  return {
    canAdmin: roles.includes('admin'),
    canDesigner: roles.includes('admin'),
  };
}
