import { fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { ProcessConditionProps } from '../types';
import { ConditionRulesEditor } from './ConditionRulesEditor';

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
});
