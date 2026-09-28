import { request } from '@umijs/max';

const CSRF_COOKIE_NAME = process.env.ANTFLOW_AUTH_CSRF_COOKIE_NAME || 'antflow-csrf';

export async function outLogin(options?: Record<string, unknown>) {
  const csrf = document.cookie.split('; ')
    .find((entry) => entry.startsWith(`${CSRF_COOKIE_NAME}=`))
    ?.split('=').slice(1).join('=');
  return request<void>('/api/auth/logout', {
    method: 'POST',
    credentials: 'include',
    headers: csrf ? { 'X-CSRF-Token': decodeURIComponent(csrf) } : undefined,
    skipErrorHandler: true,
    ...(options || {}),
  });
}
