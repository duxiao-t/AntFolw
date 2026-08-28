import { createBrowserRouter, createMemoryRouter, Navigate, type RouteObject } from 'react-router-dom';
import { LoginPage } from '../features/auth/LoginPage';
import { WorkbenchPage } from '../features/workbench/WorkbenchPage';
import { RouteErrorPage } from './RouteErrorPage';
import { AuthenticatedRoute } from '../features/auth/AuthenticatedRoute';
import { MobileShell } from './MobileShell';

const NoHydrate: React.ComponentType = () => null;

const routes: RouteObject[] = [
  {
    index: true,
    element: <Navigate to="/workbench" replace />,
  },
  {
    path: '/login',
    Component: LoginPage,
    HydrateFallback: NoHydrate,
    errorElement: <RouteErrorPage title="登录失败" />,
  },
  {
    element: <AuthenticatedRoute />,
    children: [
      {
        element: <MobileShell />,
        children: [
          {
            path: '/workbench',
            Component: WorkbenchPage,
            HydrateFallback: NoHydrate,
          },
          {
            path: '/tasks',
            lazy: () => import('../features/tasks/TaskCenterPage').then(({ TaskCenterPage }) => ({ Component: TaskCenterPage })),
            HydrateFallback: NoHydrate,
          },
          {
            path: '/profile',
            lazy: () => import('../features/profile/ProfilePage').then(({ ProfilePage }) => ({ Component: ProfilePage })),
            HydrateFallback: NoHydrate,
          },
        ],
      },
      {
        path: '/tasks/:taskId',
        lazy: () => import('../features/tasks/TaskDetailPage').then(({ TaskDetailPage }) => ({ Component: TaskDetailPage })),
        HydrateFallback: NoHydrate,
      },
      {
        path: '/apps',
        lazy: () => import('../features/workbench/AppCatalogPage').then(({ AppCatalogPage }) => ({ Component: AppCatalogPage })),
        HydrateFallback: NoHydrate,
      },
      {
        path: '/apps/favorites',
        lazy: () => import('../features/workbench/FavoriteAppsPage').then(({ FavoriteAppsPage }) => ({ Component: FavoriteAppsPage })),
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/drafts',
        lazy: () => import('../features/forms/DraftListPage').then(({ DraftListPage }) => ({ Component: DraftListPage })),
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/:code',
        lazy: () => import('../features/forms/FormFillPage').then(({ FormFillPage }) => ({ Component: FormFillPage })),
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/:code/self-select',
        lazy: () => import('../features/forms/SelfSelectPage').then(({ SelfSelectPage }) => ({ Component: SelfSelectPage })),
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/:code/confirm',
        lazy: () => import('../features/forms/SubmitConfirmPage').then(({ SubmitConfirmPage }) => ({ Component: SubmitConfirmPage })),
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/:code/success/:instanceId',
        lazy: () => import('../features/forms/SubmitSuccessPage').then(({ SubmitSuccessPage }) => ({ Component: SubmitSuccessPage })),
        HydrateFallback: NoHydrate,
      },
      {
        path: '/processes/:instanceId',
        lazy: () => import('../features/processes/ProcessDetailPage').then(({ ProcessDetailPage }) => ({ Component: ProcessDetailPage })),
        HydrateFallback: NoHydrate,
      },
      {
        path: '/profile/security',
        lazy: () => import('../features/profile/SecurityPage').then(({ SecurityPage }) => ({ Component: SecurityPage })),
        HydrateFallback: NoHydrate,
      },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(routes, { basename: '/mobile' });
}

export function createTestRouter(initialPath: string) {
  return createMemoryRouter(routes, { initialEntries: [initialPath], basename: '/' });
}

export const __appRoutes = routes;
