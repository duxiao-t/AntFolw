import { describe, expect, it } from 'vitest';
import { cellText, detailText, fieldColumns, type FormDataFieldValue } from './fieldValues';

function field(fieldId: string, fieldName: string, value: unknown): FormDataFieldValue {
  return { fieldId, fieldName, value };
}

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
