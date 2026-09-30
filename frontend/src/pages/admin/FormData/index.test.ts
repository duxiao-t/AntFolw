import { describe, expect, it } from 'vitest';
import { fieldColumns, type FormDataFieldValue } from './fieldValues';

function field(fieldId: string, fieldName: string, value: unknown): FormDataFieldValue {
  return { fieldId, fieldName, value };
}

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
