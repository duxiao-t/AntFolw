import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SchemaNode } from '../../../registry/types';
import { OptionSourceSettings } from './OptionSourceSettings';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({
  request,
  Link: ({ children }: { children: React.ReactNode }) => children,
}));

const bindable = [{
  id: 9, code: 'city', name: '城市列表', versionId: 91, versionNo: 2,
  columns: ['code', 'name'], rowCount: 3, latestVersionNo: 2,
}];

function renderPanel(node: SchemaNode, schema: SchemaNode[], onChange = vi.fn(), onNodeChange = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const result = render(
    <QueryClientProvider client={client}>
      <OptionSourceSettings formId={5} node={node} schema={schema}
        update={onChange} updateNode={onNodeChange} />
    </QueryClientProvider>,
  );
  return { ...result, onChange, onNodeChange };
}

describe('外部数据与联动面板', () => {
  beforeEach(() => {
    request.mockReset();
    request.mockImplementation((url: string) => {
      if (url === '/api/forms/5/option-sources') return Promise.resolve(bindable);
      return Promise.resolve({ items: [], total: 0 });
    });
  });

  it('嵌套两级分栏里的上游字段也能被找到', async () => {
    // 服务端 scopeFor 会递归展开 span_layout，前端只摊平一层的话这里会是空的——
    // 表现出来就是"服务端允许的联动，面板里选不到上游"。
    const inner: SchemaNode = {
      id: 'inner', type: 'select', label: '城市',
      props: { optionSource: { sourceId: 9, versionId: 91, valueColumn: 'code', labelColumn: 'name' } },
    };
    const schema: SchemaNode[] = [{
      id: 'outer', type: 'span_layout',
      children: [{ id: 'nest', type: 'span_layout', children: [inner] }],
    }];
    const target: SchemaNode = { id: 'detail', type: 'text', label: '地址' };

    renderPanel(target, [...schema, target]);

    fireEvent.mouseDown(await screen.findByText('不联动'));
    expect(await screen.findByTitle('城市')).toBeInTheDocument();
  });

  it('改「保存值所在列」时，把下游的联动列一起带过去', async () => {
    // 服务端 validateChain 要求下游 matchColumn == 上游 valueColumn；不带过去就是
    // "面板看着配好了、表单发布不出去"。
    const upstream: SchemaNode = {
      id: 'city', type: 'select', label: '城市',
      props: { optionSource: { sourceId: 9, versionId: 91, valueColumn: 'code', labelColumn: 'name' } },
    };
    const downstream: SchemaNode = {
      id: 'area', type: 'select', label: '区县',
      props: { optionSource: { sourceId: 9, versionId: 91, valueColumn: 'code', labelColumn: 'name',
        dependency: { fieldId: 'city', matchColumn: 'code' } } },
    };

    const { onChange, onNodeChange } = renderPanel(upstream, [upstream, downstream]);

    fireEvent.mouseDown(await screen.findByTitle('code'));
    // 「保存值所在列」和「显示名称所在列」的候选列名完全一样，只能在**展开的那个**下拉里找。
    const openDropdown = await waitFor(() => {
      const found = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(found).toBeTruthy();
      return found as HTMLElement;
    });
    fireEvent.click(within(openDropdown).getByTitle('name'));

    await waitFor(() => expect(onNodeChange).toHaveBeenCalled());
    expect(onNodeChange.mock.calls[0][0]).toBe('area');
    expect(onNodeChange.mock.calls[0][1].props.optionSource.dependency).toEqual({
      fieldId: 'city', matchColumn: 'name',
    });
    expect(onChange).toHaveBeenCalled();
  });

  it('分段长度里有一个不合法就整段不写进 schema', async () => {
    // 以前是"过滤掉坏项"：面板显示 1,0,2、存进去的却是 [1,2]，级联层级静默变了。
    const node: SchemaNode = {
      id: 'zone', type: 'select', label: '区域',
      props: { optionSource: { sourceId: 9, versionId: 91, valueColumn: 'code', labelColumn: 'name',
        cascade: { kind: 'split', sourceColumn: 'code', split: { kind: 'fixed', lengths: [1, 1, 1] } } } },
    };

    const { onChange } = renderPanel(node, [node]);

    const input = await screen.findByDisplayValue('1,1,1');
    fireEvent.change(input, { target: { value: '1,0,2' } });

    expect(screen.getByText('每一级都要填正整数，用逗号分开')).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
    // 输入框保留用户敲的原文（不静默改写成 1,2）。
    expect(input).toHaveValue('1,0,2');

    fireEvent.change(input, { target: { value: '2,3' } });
    await waitFor(() => expect(onChange).toHaveBeenCalled());
    expect(onChange.mock.calls.at(-1)?.[0].optionSource.cascade.split.lengths).toEqual([2, 3]);
  });

  it('上游的数据源版本不可用时，不让选它做联动', async () => {
    const stale: SchemaNode = {
      id: 'stale', type: 'select', label: '已停用源的下拉',
      props: { optionSource: { sourceId: 9, versionId: 999, valueColumn: 'code', labelColumn: 'name' } },
    };
    const target: SchemaNode = { id: 'detail', type: 'text', label: '地址' };

    renderPanel(target, [stale, target]);

    fireEvent.mouseDown(await screen.findByText('不联动'));
    // 选它只能写出没有 valueColumn 的联动，服务端会拒——所以直接不给选，并说明原因。
    expect(await screen.findByTitle('已停用源的下拉（数据源版本当前不可用）')).toBeInTheDocument();
  });

  it('三要素齐全时给出候选项预览', async () => {
    const node: SchemaNode = {
      id: 'city', type: 'select', label: '城市',
      props: { optionSource: { sourceId: 9, versionId: 91, valueColumn: 'code', labelColumn: 'name' } },
    };
    request.mockImplementation((url: string) => {
      if (url === '/api/forms/5/option-sources') return Promise.resolve(bindable);
      return Promise.resolve({ items: [{ value: 'BJ', label: '北京市' }], total: 1 });
    });

    renderPanel(node, [node]);

    expect(await screen.findByText('预览')).toBeInTheDocument();
    expect(screen.getByText('北京市（存 BJ）')).toBeInTheDocument();
    expect(screen.getByText(/共 1 条候选/)).toBeInTheDocument();
  });
});
