import { describe, expect, it } from 'vitest';
import {
  createDefaultSelectOptions,
  mergeSelectOptions,
  normalizeDefaultValue,
  normalizeSelectOptions,
  parseBulkSelectOptions,
  visibleSelectOptions,
} from './selectOptions';

describe('select option schema helpers', () => {
  it('creates three editable defaults', () => {
    expect(createDefaultSelectOptions()).toEqual([
      { id: 'option_1', label: '选项1', value: 'option_1' },
      { id: 'option_2', label: '选项2', value: 'option_2' },
      { id: 'option_3', label: '选项3', value: 'option_3' },
    ]);
  });

  it('normalizes legacy options without losing metadata', () => {
    expect(normalizeSelectOptions([
      { label: '旧选项', value: 'legacy', hidden: true, disabled: true, color: '#F04438' },
    ])[0]).toMatchObject({
      label: '旧选项',
      value: 'legacy',
      hidden: true,
      disabled: true,
      color: '#F04438',
    });
    expect(visibleSelectOptions([{ label: '隐藏', value: 'hidden', hidden: true }])).toEqual([]);
  });

  it('parses only pipes and tabs as explicit values and keeps commas in labels', () => {
    const options = parseBulkSelectOptions('1. 北京\nsh|上海\n广州,gz\n上海,sh');
    expect(options.map(({ label, value }) => ({ label, value }))).toEqual([
      { label: '北京', value: '北京' },
      { label: '上海', value: 'sh' },
      { label: '广州,gz', value: '广州,gz' },
      { label: '上海,sh', value: '上海,sh' },
    ]);
  });

  it('preserves an empty draft label without changing its technical value', () => {
    expect(normalizeSelectOptions([{ id: 'a', label: '', value: 'stable' }]))
      .toEqual([{ id: 'a', label: '', value: 'stable', hidden: false, disabled: false, color: undefined, isOther: false }]);
  });

  it('treats extra pipe or tab columns as option text', () => {
    expect(parseBulkSelectOptions('a|b|c\n甲\t乙\t丙').map((option) => option.label))
      .toEqual(['a|b|c', '甲\t乙\t丙']);
  });

  it('removes hidden and other entries from defaults', () => {
    const options = normalizeSelectOptions([
      { label: '可见', value: 'visible' },
      { label: '隐藏', value: 'hidden', hidden: true },
      { label: '其他', value: '__antflow_other__', isOther: true },
    ]);
    expect(normalizeDefaultValue(['visible', 'hidden', '__antflow_other__'], options, true)).toEqual(['visible']);
    expect(normalizeDefaultValue('hidden', options, false)).toBeUndefined();
  });

  it('keeps the other option at the end when appending bulk options', () => {
    const merged = mergeSelectOptions(
      normalizeSelectOptions([
        { label: '现有', value: 'existing' },
        { label: '其他', value: '__antflow_other__', isOther: true },
      ]),
      [{ label: '新增', value: 'new' }],
    );
    expect(merged.map((option) => option.label)).toEqual(['现有', '新增', '其他']);
    expect(merged.at(-1)?.isOther).toBe(true);
  });
});
