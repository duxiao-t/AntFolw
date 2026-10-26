import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import FormManagementWizard from './FormManagementWizard';
import { useFormDesignerStore } from '../designer/form/useFormDesignerStore';

const { request, push, location } = vi.hoisted(() => ({
  request: vi.fn(), push: vi.fn(), location: { search: '?step=basic' },
}));
vi.mock('@umijs/max', () => ({
  request,
  history: { push },
  useParams: () => ({ id: '10' }),
  useLocation: () => location,
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
  beforeEach(() => {
    // store 是模块级单例，测试之间要清干净。
    location.search = '?step=basic';
    useFormDesignerStore.setState({
      schema: [], loadedFormId: null, selectedId: null, history: { past: [], future: [] },
    });
  });

  it('保存草稿带上设计器里还没保存的编辑，而不是服务端那份', async () => {
    const definition = {
      id: 10, code: 'leave', name: '请假申请', version: 1, status: 'PUBLISHED',
      schema: [{ id: 'a', type: 'text', label: 'A' }], settings: {},
    };
    let posted: any = null;
    request.mockImplementation(async (url: string, options?: { method?: string; data?: any }) => {
      if (url === '/api/forms/definitions') {
        posted = options?.data;
        return { ...definition, status: 'DRAFT', schema: options?.data?.schema };
      }
      if (url === '/api/forms/definitions/10') return definition;
      if (url === '/api/forms/10/grants/candidates') return { roles: [], departments: [] };
      if (url === '/api/forms/10/grants') {
        return { version: 7, userIds: [], roleIds: [], departmentIds: [],
          users: [], roles: [], departments: [] };
      }
      if (url === '/api/forms/10/maintainers') return { version: 7, userIds: [], users: [] };
      throw new Error(`Unexpected request: ${url}`);
    });
    location.search = '?step=publish';
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <FormManagementWizard />
      </QueryClientProvider>,
    );

    // 进页面就会把服务端那份灌进 store（不打开设计器也有内容）。
    await waitFor(() => expect(useFormDesignerStore.getState().loadedFormId).toBe('10'));
    // 模拟在设计器里改了字段标题，但**没点设计器的保存**。
    useFormDesignerStore.setState({
      schema: [{ id: 'a', type: 'text', label: 'A改了' }],
    });

    fireEvent.click(await screen.findByRole('button', { name: '保存草稿' }));

    await waitFor(() => expect(posted).not.toBeNull());
    // 以前这里发的是 definition.schema（旧内容），于是后端看什么都没变：
    // 不降级为草稿、版本号也不动，而编辑被悄悄丢掉。
    expect(posted.schema).toEqual([{ id: 'a', type: 'text', label: 'A改了' }]);
  });

  it('saves usage and maintenance as separate lists with consecutive CAS versions', async () => {
    let permissionVersion = 7;
    const definition = {
      id: 10, code: 'leave', name: '请假申请', version: 1, status: 'DRAFT',
      schema: [], settings: {},
    };
    request.mockImplementation(async (url: string, options?: { method?: string }) => {
      if (url === '/api/forms/definitions' || url === '/api/forms/definitions/10') {
        return definition;
      }
      if (url === '/api/forms/10/grants/candidates') return { roles: [], departments: [] };
      if (url === '/api/forms/10/grants') {
        if (options?.method === 'PUT') permissionVersion += 1;
        // 必须带公司：公司是使用门槛，只有主体授权没有公司的配置会被前端拦下（单独有拦禁用例）。
        return {
        version: permissionVersion,
        userIds: [3], roleIds: [], departmentIds: [], companyIds: [1],
        users: [], roles: [], departments: [], companies: [{ id: 1, name: '甲公司' }],
        };
      }
      if (url === '/api/forms/10/maintainers') {
        if (options?.method === 'PUT') permissionVersion += 1;
        return { version: permissionVersion, userIds: [5], users: [] };
      }
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

    fireEvent.click(screen.getByRole('button', { name: '保存使用范围' }));
    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/forms/10/grants', {
      method: 'PUT', data: { userIds: [3], roleIds: [], departmentIds: [], companyIds: [1], version: 7 },
    }));
    await waitFor(() => expect(permissionVersion).toBe(8));

    fireEvent.change(maintainerInput, { target: { value: '5,6' } });
    fireEvent.click(screen.getByRole('button', { name: '保存维护人员' }));
    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/forms/10/maintainers', {
      method: 'PUT', data: { userIds: [5, 6], version: 8 },
    }));
  });

  it('只选了人员/角色/部门却没选公司时，拦下保存并说明原因', async () => {
    const definition = {
      id: 10, code: 'leave', name: '请假申请', version: 1, status: 'PUBLISHED',
      schema: [{ id: 'a', type: 'text', label: 'A' }], settings: {},
    };
    request.mockImplementation(async (url: string) => {
      if (url === '/api/forms/definitions/10') return definition;
      if (url === '/api/forms/10/grants/candidates') return { roles: [], departments: [] };
      if (url === '/api/forms/10/grants') {
        return {
          version: 7, userIds: [3], roleIds: [], departmentIds: [], companyIds: [],
          users: [], roles: [], departments: [], companies: [],
        };
      }
      if (url === '/api/forms/10/maintainers') return { version: 7, userIds: [5], users: [] };
      throw new Error(`Unexpected request: ${url}`);
    });
    render(
      <QueryClientProvider client={new QueryClient({
        defaultOptions: { queries: { retry: false } },
      })}>
        <FormManagementWizard />
      </QueryClientProvider>,
    );
    await waitFor(() => expect(screen.getByLabelText('选择可使用表单的人员')).toHaveValue('3'));
    // request 的调用记录在用例之间是累积的，只看本次点击之后新增的调用。
    const before = request.mock.calls.length;
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '保存使用范围' }));
    });
    // 保存被拦在本地：不应该发出授权 PUT，否则会出现"保存成功但谁也用不了"。
    const after = request.mock.calls.slice(before);
    expect(after.some((call) => call[1]?.method === 'PUT')).toBe(false);
  });

  it('后台重新拉取使用范围时，不会把没保存的改动冲掉', async () => {
    let grantFetches = 0;
    request.mockImplementation(async (url: string) => {
      if (url === '/api/forms/definitions/10') {
        return { id: 10, code: 'leave', name: '请假申请', version: 1, status: 'DRAFT',
          schema: [], settings: {} };
      }
      if (url === '/api/forms/10/grants/candidates') {
        // 有 employee 角色，「全公司可见」这个开关才可用。
        return { roles: [{ id: 2, code: 'employee', name: '员工' }], departments: [] };
      }
      if (url === '/api/forms/10/grants') {
        grantFetches += 1;
        // 第二次拉取版本号变了——保存维护人员和保存使用范围共用同一个版本计数器，
        // 所以「保存维护人员」或任何一次保存都会让这份数据变成新对象。
        return { version: grantFetches === 1 ? 7 : 8, userIds: [3], roleIds: [], departmentIds: [],
          users: [], roles: [], departments: [] };
      }
      if (url === '/api/forms/10/maintainers') {
        return { version: 7, userIds: [5], users: [] };
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <FormManagementWizard />
      </QueryClientProvider>,
    );

    const toggle = screen.getByRole('switch', { name: '已授权公司内全部员工' });
    // 先等候选角色到位（开关在拿到 employee 角色之前是禁用的），否则点了不会生效。
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).not.toBeChecked();
    // 打开但**不保存**——这一段本来就要按「保存使用范围」才落库，所以必须提示未保存。
    fireEvent.click(toggle);
    // 提示是 useWatch 驱动的，下一帧才出现，不能同步断言。
    await waitFor(() => expect(screen.getByText('有未保存的修改')).toBeInTheDocument());

    // 模拟切走再切回来：react-query 默认 refetchOnWindowFocus，会换出一个新的 data 对象
    // （内容一样、引用不同），旧的实现会让初始化 effect 重跑、把没保存的改动冲掉。
    await client.invalidateQueries({ queryKey: ['form-management-grant'] });
    await waitFor(() => expect(grantFetches).toBe(2));
    // 等这次 refetch 的数据真的渲染进组件再断言，否则会在"重置发生之前"侥幸通过。
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(toggle).toBeChecked();
    expect(screen.getByText('有未保存的修改')).toBeInTheDocument();
  });

  it('keeps company selections across refetch and saves them with the grant version', async () => {
    request.mockImplementation(async (url: string, options?: { method?: string; data?: any }) => {
      if (url === '/api/forms/definitions/10') {
        return { id: 10, code: 'leave', name: '申请', version: 1, status: 'DRAFT', schema: [], settings: {} };
      }
      if (url === '/api/forms/10/grants/candidates') {
        return { roles: [{ id: 2, code: 'employee', name: '员工' }], departments: [], companies: [{ id: 20, name: '甲公司' }, { id: 30, name: '乙公司' }] };
      }
      if (url === '/api/forms/10/grants') {
        return { version: options?.method === 'PUT' ? 8 : 7,
          userIds: [3], roleIds: [], departmentIds: [], companyIds: options?.data?.companyIds ?? [20],
          users: [], roles: [], departments: [], companies: [{ id: 20, name: '甲公司' }] };
      }
      if (url === '/api/forms/10/maintainers') return { version: 7, userIds: [5], users: [] };
      throw new Error(`Unexpected request: ${url}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><FormManagementWizard /></QueryClientProvider>);
    const selector = await screen.findByLabelText('允许使用的公司');
    await waitFor(() => expect(screen.getByText('甲公司')).toBeInTheDocument());
    fireEvent.mouseDown(selector);
    fireEvent.click(await screen.findByText('乙公司'));
    fireEvent.click(screen.getByRole('switch', { name: '已授权公司内全部员工' }));
    await waitFor(() => expect(screen.getByText('有未保存的修改')).toBeInTheDocument());
    await client.invalidateQueries({ queryKey: ['form-management-grant'] });
    fireEvent.click(screen.getByRole('button', { name: '保存使用范围' }));
    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/forms/10/grants', {
      method: 'PUT', data: { userIds: [3], roleIds: [2], departmentIds: [], companyIds: [20, 30], version: 7 },
    }));
  });
});
