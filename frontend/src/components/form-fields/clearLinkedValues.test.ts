import { describe, expect, it } from 'vitest';
import { clearLinkedValues } from './clearLinkedValues';

const linkage = (fieldId: string) => ({ props: { optionSource: { dependency: { fieldId } } } });

describe('clearLinkedValues', () => {
  it('嵌套 span_layout 里的联动字段也要被清掉', () => {
    const schema: any = [
      { id: 'province', type: 'select', ...linkage('') , props: {} },
      {
        id: 'outer',
        type: 'span_layout',
        props: {},
        children: [
          {
            id: 'inner',
            type: 'span_layout',
            props: {},
            children: [
              { id: 'city', type: 'select', ...linkage('province') },
              { id: 'district', type: 'multi_select', ...linkage('city') },
            ],
          },
        ],
      },
    ];

    const next = clearLinkedValues(schema, 'province', { province: 'ZJ', city: 'HZ', district: ['XH'] });

    expect(next.city).toBeUndefined();
    // 级联第二跳：city 被清掉后 district 也要跟着清。
    expect(next.district).toEqual([]);
  });
});
