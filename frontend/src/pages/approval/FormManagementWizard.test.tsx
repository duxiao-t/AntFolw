import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { describe, expect, it, vi } from 'vitest';
import FormManagementWizard from './FormManagementWizard';

const { request, push } = vi.hoisted(() => ({ request: vi.fn(), push: vi.fn() }));
vi.mock('@umijs/max', () => ({
  request,
  history: { push },
  useParams: () => ({ id: '10' }),
  useLocation: () => ({ search: '?step=basic' }),
  useModel: () => ({ initialState: { currentUser: {
    id: 1, roles: ['admin'], username: 'admin',
  } } }),
}));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: PropsWithChildren) => <div>{children}</div>,
}));
vi.mock('../designer/form/FormDesigner', () => ({ FormDesignerSurface: () => null }));
vi.mock('../designer/process/ProcessDesigner', () => ({ ProcessDesignerSurface: () => null }));
vi.mock('./MobileFormPreview', () => ({ default: () => null }));
vi.mock('./BusinessNumberEditor', () => ({ default: () => null }));
vi.mock('./FormGrantUserPicker', () => ({
  default: ({ title, value, onChange }: {
    title: string; value?: number[]; onChange?: (ids: number[]) => void;
  }) => <input aria-label={title} value={(value ?? []).join(',')}
    onChange={(event) => onChange?.(event.target.value.split(',').filter(Boolean).map(Number))} />,
}));

describe('FormManagementWizard permission settings', () => {
  it('saves usage and maintenance as separate lists with consecutive CAS versions', async () => {
    const definition = {
      id: 10, code: 'leave', name: '请假申请', version: 1, status: 'DRAFT',
      schema: [], settings: {},
    };
    request.mockImplementation(async (url: string, options?: { method?: string }) => {
      if (url === '/api/forms/definitions' || url === '/api/forms/definitions/10') {
        return definition;
      }
      if (url === '/api/forms/10/grants/candidates') return { roles: [], departments: [] };
      if (url === '/api/forms/10/grants') return {
        version: options?.method === 'PUT' ? 8 : 7,
        userIds: [3], roleIds: [], departmentIds: [], users: [], roles: [], departments: [],
      };
      if (url === '/api/forms/10/maintainers') return {
        version: 7, userIds: [5], users: [],
      };
      throw new Error(`Unexpected request: ${url}`);
    });
    render(
      <QueryClientProvider client={new QueryClient({
        defaultOptions: { queries: { retry: false } },
      })}>
        <FormManagementWizard />
      </QueryClientProvider>,
    );
    const maintainerInput = screen.getByLabelText('选择维护人员');
    await waitFor(() => expect(maintainerInput).toHaveValue('5'));
    expect(screen.getByLabelText('选择可使用表单的人员')).toHaveValue('3');

    fireEvent.change(maintainerInput, { target: { value: '5,6' } });
    fireEvent.click(screen.getByRole('button', { name: '保存并进入表单制作' }));

    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/forms/10/maintainers', {
      method: 'PUT', data: { userIds: [5, 6], version: 8 },
    }));
    expect(request).toHaveBeenCalledWith('/api/forms/10/grants', {
      method: 'PUT', data: { userIds: [3], roleIds: [], departmentIds: [], version: 7 },
    });
  });
});
