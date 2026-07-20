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

  it('uses an explicit unsupported field definition for unknown type codes', () => {
    const field = getFieldDefinition('legacy_field');

    expect(field.type).toBe('unsupported');
    expect(field.summarize({ id: 'x', type: 'legacy_field' }, 'abc')).toBe('不支持的字段');
  });
});
