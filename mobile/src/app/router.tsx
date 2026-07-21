import { createBrowserRouter, createMemoryRouter, type RouteObject } from 'react-router-dom';
import { LoginPage } from '../features/auth/LoginPage';
import { WorkbenchPage } from '../features/workbench/WorkbenchPage';
import { TaskCenterPage } from '../features/tasks/TaskCenterPage';
import { ProfilePage } from '../features/profile/ProfilePage';
import { SecurityPage } from '../features/profile/SecurityPage';
import { AppCatalogPage } from '../features/workbench/AppCatalogPage';
import { FavoriteAppsPage } from '../features/workbench/FavoriteAppsPage';
import { FormFillPage } from '../features/forms/FormFillPage';
import { DraftListPage } from '../features/forms/DraftListPage';
import { SelfSelectPage } from '../features/forms/SelfSelectPage';
import { SubmitConfirmPage } from '../features/forms/SubmitConfirmPage';
import { SubmitSuccessPage } from '../features/forms/SubmitSuccessPage';
import { ProcessDetailPage } from '../features/processes/ProcessDetailPage';
import { RouteErrorPage } from './RouteErrorPage';
import { AuthenticatedRoute } from '../features/auth/AuthenticatedRoute';
import { MobileShell } from './MobileShell';

const NoHydrate: React.ComponentType = () => null;

const routes: RouteObject[] = [
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
            Component: TaskCenterPage,
            HydrateFallback: NoHydrate,
          },
          {
            path: '/profile',
            Component: ProfilePage,
            HydrateFallback: NoHydrate,
          },
        ],
      },
      {
        path: '/apps',
        Component: AppCatalogPage,
        HydrateFallback: NoHydrate,
      },
      {
        path: '/apps/favorites',
        Component: FavoriteAppsPage,
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/drafts',
        Component: DraftListPage,
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/:code',
        Component: FormFillPage,
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/:code/self-select',
        Component: SelfSelectPage,
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/:code/confirm',
        Component: SubmitConfirmPage,
        HydrateFallback: NoHydrate,
      },
      {
        path: '/forms/:code/success/:instanceId',
        Component: SubmitSuccessPage,
        HydrateFallback: NoHydrate,
      },
      {
        path: '/processes/:instanceId',
        Component: ProcessDetailPage,
        HydrateFallback: NoHydrate,
      },
      {
        path: '/profile/security',
        Component: SecurityPage,
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
