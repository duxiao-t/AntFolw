import { describe, expect, it } from 'vitest';
import { getFieldDefinition, registeredFields, validateSchemaValues } from './fieldRegistry';
import type { MobileSchemaNode } from './types';

describe('mobile field registry', () => {
  it('registers exactly the supported mobile field types once', () => {
    const typeCodes = registeredFields.map((field) => field.type);

    expect(typeCodes).toHaveLength(14);
    expect(new Set(typeCodes).size).toBe(typeCodes.length);
    expect(typeCodes).toEqual([
      'text',
      'textarea',
      'number',
      'money',
      'date',
      'date_range',
      'select',
      'multi_select',
      'user_picker',
      'dept_picker',
      'file_upload',
      'description',
      'span_layout',
      'table_list',
    ]);
  });

  it('returns field-id keyed validation errors', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
      { id: 'days', type: 'number', label: '请假天数', props: { required: true } },
    ];

    const errors = validateSchemaValues(schema, { reason: '', days: 3 });

    expect(errors).toEqual({ reason: '请填写请假事由' });
  });

  it('validates table-list children inside each row without top-level child collisions', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'items',
        type: 'table_list',
        label: '明细',
        children: [
          { id: 'name', type: 'text', label: '名称', props: { required: true } },
        ],
      },
    ];

    const errors = validateSchemaValues(schema, { items: [{ name: '' }], name: '顶层名称不应影响明细' });

    expect(errors).toEqual({ items: '第1行: 请填写名称' });
    expect(errors.name).toBeUndefined();
  });

  it('does not recurse table-list children against top-level missing values', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'items',
        type: 'table_list',
        label: '明细',
        children: [
          { id: 'name', type: 'text', label: '名称', props: { required: true } },
        ],
      },
    ];

    const errors = validateSchemaValues(schema, { items: [{ name: '行内名称' }] });

    expect(errors).toEqual({});
  });

  it('keys span-layout child validation errors by child field id', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'layout',
        type: 'span_layout',
        children: [
          { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
        ],
      },
    ];

    const errors = validateSchemaValues(schema, { reason: '' });

    expect(errors).toEqual({ reason: '请填写请假事由' });
    expect(errors.layout).toBeUndefined();
  });

  it('rejects malformed optional picker values while allowing empty optional values', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'approver', type: 'user_picker', label: '审批人' },
      { id: 'deptId', type: 'dept_picker', label: '部门' },
    ];

    expect(validateSchemaValues(schema, { approver: '', deptId: null })).toEqual({});
    expect(validateSchemaValues(schema, { approver: 'bad-user', deptId: 'bad-dept' })).toEqual({
      approver: '请填写审批人',
      deptId: '请填写部门',
    });
  });

  it('uses an explicit unsupported field definition for unknown type codes', () => {
    const field = getFieldDefinition('legacy_field');

    expect(field.type).toBe('unsupported');
    expect(field.summarize({ id: 'x', type: 'legacy_field' }, 'abc')).toBe('不支持的字段');
  });
});
