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
});
