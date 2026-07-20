import { createBrowserRouter, createMemoryRouter, type RouteObject } from 'react-router-dom';
import { RouteErrorPage } from './RouteErrorPage';
import { AuthenticatedRoute } from './AuthenticatedRoute';
import { MobileShell } from './MobileShell';

const routes: RouteObject[] = [
  {
    path: '/login',
    lazy: async () => {
      const module = await import('../features/auth/LoginPage');
      return { Component: module.LoginPage };
    },
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
            lazy: async () => {
              const module = await import('../features/workbench/WorkbenchPage');
              return { Component: module.WorkbenchPage };
            },
          },
          {
            path: '/tasks',
            lazy: async () => {
              const module = await import('../features/tasks/TaskCenterPage');
              return { Component: module.TaskCenterPage };
            },
          },
          {
            path: '/profile',
            lazy: async () => {
              const module = await import('../features/profile/ProfilePage');
              return { Component: module.ProfilePage };
            },
          },
        ],
      },
      {
        path: '/apps',
        lazy: async () => {
          const module = await import('../features/workbench/AppCatalogPage');
          return { Component: module.AppCatalogPage };
        },
      },
      {
        path: '/apps/favorites',
        lazy: async () => {
          const module = await import('../features/workbench/FavoriteAppsPage');
          return { Component: module.FavoriteAppsPage };
        },
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
