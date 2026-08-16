import { describe, expect, it } from 'vitest';
import type { MobileSchemaNode } from './types';
import {
  applySchemaDefaults,
  formatDateTime,
  schemaDefaultValue,
} from './defaults';

describe('form schema defaults', () => {
  it('formats date and datetime strings', () => {
    const date = new Date(2026, 7, 4, 10, 30, 0);
    expect(formatDateTime(date, 'YYYY-MM-DD')).toBe('2026-08-04');
    expect(formatDateTime(date, 'YYYY/MM/DD')).toBe('2026/08/04');
    expect(formatDateTime(date, 'YYYY-MM-DD HH:mm')).toBe('2026-08-04 10:30');
  });

  it('resolves current time as default for date fields', () => {
    const before = Date.now();
    const value = schemaDefaultValue({
      id: 'd',
      type: 'date',
      props: { defaultNow: true, format: 'YYYY-MM-DD HH:mm' },
    });
    const after = Date.now();
    expect(typeof value).toBe('string');
    expect(String(value)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    const parsed = Date.parse(String(value).replace(' ', 'T'));
    // 格式化只精确到分钟，截断后可能比 now 早最多 59 秒
    expect(parsed).toBeGreaterThanOrEqual(before - 60_000);
    expect(parsed).toBeLessThanOrEqual(after + 1000);
  });

  it('uses the configured custom default string', () => {
    expect(
      schemaDefaultValue({ id: 't', type: 'text', props: { defaultValue: '你好' } }),
    ).toBe('你好');
    expect(
      schemaDefaultValue({ id: 't', type: 'text', props: { defaultValue: '  ' } }),
    ).toBeUndefined();
  });

  it('supports single and multiple select defaults', () => {
    expect(
      schemaDefaultValue({ id: 's', type: 'select', props: { defaultValue: 'option_1' } }),
    ).toBe('option_1');
    expect(
      schemaDefaultValue({ id: 'm', type: 'multi_select', props: { defaultValue: ['a', 'b'] } }),
    ).toEqual(['a', 'b']);
  });

  it('fills empty fields but keeps existing values', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'a', type: 'text', props: { defaultValue: '默认A' } },
      { id: 'b', type: 'date', props: { defaultNow: true, format: 'YYYY-MM-DD' } },
      { id: 'c', type: 'text', props: { defaultValue: '默认C' } },
    ];
    const result = applySchemaDefaults(schema, { a: '已有A', b: '' });
    expect(result.a).toBe('已有A');
    expect(result.b).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(result.c).toBe('默认C');
  });

  it('does not apply defaults inside table_list rows', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'items',
        type: 'table_list',
        children: [{ id: 'name', type: 'text', props: { defaultValue: '行默认' } }],
      },
    ];
    const result = applySchemaDefaults(schema, {});
    expect(result.items).toBeUndefined();
  });
});
