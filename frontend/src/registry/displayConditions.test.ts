import { describe, expect, it } from 'vitest';
import { collectVisibleValues, firstVisibleValidationError } from './displayConditions';
import type { SchemaNode } from './types';

const schema: SchemaNode[] = [
  { id: 'kind', type: 'select' },
  {
    id: 'detail',
    type: 'text',
    label: '详情',
    props: {
      required: true,
      displayCondition: { fieldId: 'kind', operator: 'in', value: ['a', 'b'] },
    },
  },
];

describe('display conditions', () => {
  it('filters hidden values from submission without mutating the form session', () => {
    const values = { kind: 'c', detail: '保留的本次输入' };
    expect(collectVisibleValues(schema, values)).toEqual({ kind: 'c' });
    expect(values.detail).toBe('保留的本次输入');
    expect(firstVisibleValidationError(schema, values)).toBeNull();
    expect(firstVisibleValidationError(schema, { kind: 'a', detail: '' })).toBe('请填写详情');
  });

  it('treats cleared number bounds as unlimited', () => {
    const number: SchemaNode[] = [{ id: 'count', type: 'number', label: '数量', props: {} }];
    expect(firstVisibleValidationError(number, { count: -100 })).toBeNull();
    expect(firstVisibleValidationError(number, { count: 10000000 })).toBeNull();
  });

  it('hides downstream rules when their source field is itself hidden', () => {
    const nested: SchemaNode[] = [
      { id: 'level1', type: 'select' },
      {
        id: 'level2',
        type: 'select',
        props: { displayCondition: { fieldId: 'level1', operator: 'eq', value: 'show' } },
      },
      {
        id: 'level3',
        type: 'text',
        props: { displayCondition: { fieldId: 'level2', operator: 'eq', value: 'show' } },
      },
    ];
    const values = { level1: 'hide', level2: 'show', level3: '保留值' };

    expect(collectVisibleValues(nested, values)).toEqual({ level1: 'hide' });
    expect(values.level2).toBe('show');
  });
});
