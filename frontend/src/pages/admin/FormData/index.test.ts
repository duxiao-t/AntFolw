import { describe, expect, it } from 'vitest';
import {
  cellText,
  cellTextFor,
  detailText,
  detailTextFor,
  fieldColumns,
  fieldMetas,
  type FieldMeta,
  type FormDataFieldValue,
} from './fieldValues';

function field(fieldId: string, fieldName: string, value: unknown): FormDataFieldValue {
  return { fieldId, fieldName, value };
}

const selectMeta: FieldMeta = {
  type: 'select',
  options: [{ value: 'option_1', label: '选项1' }],
  results: [],
  items: [],
};

const checklistMeta: FieldMeta = {
  type: 'checklist',
  options: [],
  results: [{ id: 'pass', label: '通过' }, { id: 'na', label: '不适用' }],
  items: [{ id: 'item-1', label: '检查项1' }, { id: 'item-2', label: '检查项2' }],
};

describe('台账单元格', () => {
  it('标量直接显示，空值留空由列渲染成破折号', () => {
    expect(cellText('张三')).toBe('张三');
    expect(cellText(128.5)).toBe('128.5');
    expect(cellText(null)).toBe('');
    expect(cellText('')).toBe('');
  });

  it('按值的形状收敛复合值，而不是丢一坨 JSON', () => {
    expect(cellText(['甲', '乙'])).toBe('甲、乙');
    expect(cellText([{ name: 'a.png', contentUrl: '/f/a' }, { name: 'b.png', contentUrl: '/f/b' }]))
      .toBe('2 个附件');
    // 检查项也带 name，但它是业务数据不是附件——靠 contentUrl 区分。
    expect(cellText([{ id: 'item-1', name: '检查项1', status: 'na' }])).toBe('1 项');
    expect(cellText([])).toBe('');
  });
});

describe('台账详情', () => {
  it('附件展开成文件名，结构化值保持可读', () => {
    expect(detailText([{ name: 'a.png', contentUrl: '/f/a' }])).toBe('a.png');
    expect(detailText({ status: 'pass' })).toBe('{\n  "status": "pass"\n}');
    expect(detailText(null)).toBe('—');
  });
});

describe('台账列', () => {
  it('取本页出现过的字段并集，按首次出现排序，不重复', () => {
    expect(fieldColumns([
      [field('applicant', '申请人', '张三'), field('amount', '金额', 1)],
      [field('amount', '金额', 2), field('dept', '部门', '研发部')],
    ])).toEqual([
      { id: 'applicant', label: '申请人' },
      { id: 'amount', label: '金额' },
      { id: 'dept', label: '部门' },
    ]);
  });
});

describe('拿得到字段类型时', () => {
  it('下拉显示选项名，取不到类型时只能退化成原始值', () => {
    expect(cellTextFor('option_1', selectMeta)).toBe('选项1');
    expect(cellTextFor('option_1')).toBe('option_1');
  });

  it('检查项汇总成各状态数量，详情按条目展开', () => {
    const value = [
      { id: 'item-1', name: '检查项1', status: 'pass', images: [{ name: 'a.png', contentUrl: '/f/a' }] },
      { id: 'item-2', name: '检查项2', status: 'na', images: [] },
    ];
    expect(cellTextFor(value, checklistMeta)).toBe('通过 1 · 不适用 1');
    expect(detailTextFor(value, checklistMeta)).toBe('检查项1：通过（1 张图）\n检查项2：不适用');
  });

  it('fieldMetas 会递归 children，明细表里的字段也能拿到类型', () => {
    const metas = fieldMetas([
      { id: 'a', type: 'select', props: { options: [{ value: 'x', label: 'X' }] } },
      { id: 't', type: 'table_list', children: [{ id: 'b', type: 'number', props: {} }] },
    ]);
    expect(metas.get('a')?.type).toBe('select');
    expect(metas.get('a')?.options).toEqual([{ value: 'x', label: 'X' }]);
    expect(metas.get('b')?.type).toBe('number');
  });
});
