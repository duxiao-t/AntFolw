import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../features/auth/auth.store';
import { useFavoriteDraftStore } from '../features/workbench/apps.store';
import { beginSubmitFlow, useSubmitFlowStore } from '../features/forms/submitFlow.store';
import { isRefreshExcludedAuthEndpoint, queryClient } from './AppProviders';

afterEach(() => useAuthStore.getState().reset());

describe('AppProviders auth endpoint policy', () => {
  it.each([
    ['/api/auth/login', true],
    ['/api/auth/refresh', true],
    ['/api/auth/logout', true],
    ['/api/auth/sessions', false],
    ['/api/auth/sessions/phone', false],
  ])('classifies %s as refresh-excluded: %s', (path, expected) => {
    expect(isRefreshExcludedAuthEndpoint(path)).toBe(expected);
  });
});

describe('account-scoped caches', () => {
  it('clears cached server data and unsaved state before a new account renders', () => {
    useAuthStore.setState({ status: 'anonymous', user: null, accessToken: null });
    queryClient.setQueryData(['mobile', 'apps'], ['old-account-data']);
    useFavoriteDraftStore.getState().reset([1, 2]);
    beginSubmitFlow({ formCode: 'leave', draftId: 1, values: { secret: 'old-account' } });

    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'token',
      user: { id: 7, username: 'test1', displayName: 'Test 1', roles: ['employee'] },
    });

    expect(queryClient.getQueryData(['mobile', 'apps'])).toBeUndefined();
    expect(useFavoriteDraftStore.getState().ids).toEqual([]);
    expect(useSubmitFlowStore.getState().formCode).toBeNull();
  });

  it('clears during initial session restoration but keeps same-account refresh data', () => {
    const user = { id: 7, username: 'test1', displayName: 'Test 1', roles: ['employee'] };
    useAuthStore.setState({ status: 'unknown', user: null });
    queryClient.setQueryData(['mobile', 'apps'], ['stale-data']);
    useAuthStore.setState({ status: 'authenticated', user });
    expect(queryClient.getQueryData(['mobile', 'apps'])).toBeUndefined();

    queryClient.setQueryData(['mobile', 'apps'], ['current-account-data']);
    useAuthStore.setState({ accessToken: 'refreshed-token', user: { ...user } });
    expect(queryClient.getQueryData(['mobile', 'apps'])).toEqual(['current-account-data']);
  });

  it('drops permission-scoped caches when the same account gets new roles', () => {
    const user = { id: 7, username: 'test1', displayName: 'Test 1', roles: ['employee'] };
    useAuthStore.setState({ status: 'unknown', user: null });
    useAuthStore.setState({ status: 'authenticated', user });
    queryClient.setQueryData(['mobile', 'apps'], ['employee-apps']);

    useAuthStore.setState({ status: 'authenticated', user: { ...user, roles: ['employee', 'approver'] } });
    expect(queryClient.getQueryData(['mobile', 'apps'])).toBeUndefined();
  });
});
