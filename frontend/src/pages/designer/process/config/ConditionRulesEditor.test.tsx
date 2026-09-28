import { fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { ProcessConditionProps } from '../types';
import { ConditionRulesEditor } from './ConditionRulesEditor';

const { mockRequest } = vi.hoisted(() => ({ mockRequest: vi.fn() }));
vi.mock('@umijs/max', () => ({ request: mockRequest }));

vi.mock('antd', async (importOriginal) => {
  const actual = await importOriginal<typeof import('antd')>();
  const Select = (props: ComponentProps<typeof actual.Select>) => {
    if (props.mode === 'tags') {
      return (
        <button
          type="button"
          data-testid="tags-select"
          onClick={() =>
            props.onChange?.(
              [' alpha ', 'beta', 'alpha', '', ' beta '],
              [] as never,
            )
          }
        >
          {props.placeholder}
        </button>
      );
    }
    if (props.mode === 'multiple') {
      return (
        <button
          type="button"
          data-testid="multiple-select"
          onClick={() =>
            props.onChange?.(
              props.options?.map((option) => option.value) ?? [],
              [] as never,
            )
          }
        >
          {props.options?.map((option) => String(option.label)).join('|')}
        </button>
      );
    }
    if (props.placeholder === '选择选项') {
      return (
        <button
          type="button"
          data-testid="option-select"
          data-value={props.value == null ? '' : String(props.value)}
          onClick={() =>
            props.onChange?.(props.options?.[0]?.value as never, {} as never)
          }
        >
          {props.options?.map((option) => String(option.label)).join('|')}
        </button>
      );
    }
    return (
      <span data-testid="select">
        {props.options?.map((option) => String(option.label)).join('|')}
      </span>
    );
  };
  return { ...actual, Select };
});

describe('ConditionRulesEditor', () => {
  it('shows the field title together with its identifier', () => {
    render(
      <ConditionRulesEditor
        props={{
          groups: [
            {
              id: 'group-1',
              groupType: 'AND',
              conditions: [
                {
                  id: 'condition-1',
                  field: 'department',
                  operator: '==',
                  value: 'engineering',
                },
              ],
            },
          ],
        }}
        formFields={[{ id: 'department', label: '申请部门', type: 'text' }]}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getAllByTestId('select')[0]).toHaveTextContent(
      '申请部门 · department',
    );
  });

  it('emits a trimmed and deduplicated array for the in operator', () => {
    const props: ProcessConditionProps = {
      groupsType: 'OR',
      groups: [
        {
          id: 'group-1',
          groupType: 'AND',
          conditions: [
            {
              id: 'condition-1',
              field: 'department',
              operator: 'in',
              value: [],
            },
          ],
        },
      ],
    };
    const onChange = vi.fn();
    render(
      <ConditionRulesEditor
        props={props}
        formFields={[{ id: 'department', label: '部门', type: 'text' }]}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByTestId('tags-select'));

    const next = onChange.mock.calls[0][0] as ProcessConditionProps;
    expect(next.groups?.[0].conditions[0].value).toEqual(['alpha', 'beta']);
  });

  it('shows option labels but emits their typed values', () => {
    const onChange = vi.fn();
    render(
      <ConditionRulesEditor
        props={{
          groups: [
            {
              id: 'group-1',
              groupType: 'AND',
              conditions: [
                {
                  id: 'condition-1',
                  field: 'kind',
                  operator: '==',
                  value: '',
                },
              ],
            },
          ],
        }}
        formFields={[
          {
            id: 'kind',
            label: '类型',
            type: 'select',
            options: [
              { label: '选项一', value: 1 },
              { label: '其他', value: '__other__', isOther: true },
            ],
          },
        ]}
        onChange={onChange}
      />,
    );

    expect(screen.getByTestId('option-select')).toHaveTextContent('选项一');
    expect(screen.getByTestId('option-select')).not.toHaveTextContent('其他');
    fireEvent.click(screen.getByTestId('option-select'));
    expect(onChange.mock.calls[0][0].groups[0].conditions[0].value).toBe(1);
  });

  it('keeps a numeric 0 option value selected', () => {
    // 0 是合法选项值：以前用 `scalarValue(v) || undefined` 判空，数字 0 被当成空值吞掉，
    // 重开条件只剩占位符。
    render(
      <ConditionRulesEditor
        props={{
          groups: [
            {
              id: 'group-1',
              groupType: 'AND',
              conditions: [
                { id: 'condition-1', field: 'kind', operator: '==', value: 0 },
              ],
            },
          ],
        }}
        formFields={[
          {
            id: 'kind',
            label: '类型',
            type: 'select',
            options: [
              { label: '零', value: 0 },
              { label: '一', value: 1 },
            ],
          },
        ]}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByTestId('option-select')).toHaveAttribute('data-value', '0');
  });

  it('deletes the last group for real and shows an empty state', () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <ConditionRulesEditor
        props={{
          groups: [
            { id: 'group-1', groupType: 'AND', conditions: [] },
          ],
        }}
        formFields={[]}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByText('删除组'));
    expect(onChange.mock.calls[0][0].groups).toEqual([]);

    // 删空的组不能"自己长回来"：受控组件拿到空数组就得是空态。
    rerender(
      <ConditionRulesEditor
        props={{ groups: [] }}
        formFields={[]}
        onChange={onChange}
      />,
    );
    expect(screen.queryByText('条件组 1')).not.toBeInTheDocument();
    expect(screen.getByText(/未配置条件组/)).toBeInTheDocument();
  });

  it('lists options from the external data source instead of the stale built-ins', async () => {
    mockRequest.mockResolvedValue({
      stage: 'OPTIONS',
      items: [{ value: 'ext', label: '外部选项' }],
    });
    render(
      <ConditionRulesEditor
        props={{
          groups: [
            {
              id: 'group-1',
              groupType: 'AND',
              conditions: [
                { id: 'condition-1', field: 'city', operator: '==', value: '' },
              ],
            },
          ],
        }}
        formFields={[
          {
            id: 'city',
            label: '城市',
            type: 'select',
            // 绑定外部数据源后这些内置选项就是残留，不该再出现在条件里。
            options: [{ label: '内置选项', value: 'builtin' }],
            optionSource: { sourceId: 9, versionId: 3 },
          },
        ]}
        optionPreview={{ formId: 5, schema: [] }}
        onChange={vi.fn()}
      />,
    );

    expect(await screen.findByText('外部选项')).toBeInTheDocument();
    expect(screen.getByTestId('option-select')).not.toHaveTextContent('内置选项');
    expect(mockRequest).toHaveBeenCalledWith(
      '/api/runtime/form-options/preview/5',
      expect.objectContaining({ method: 'POST' }),
    );
  });
});
