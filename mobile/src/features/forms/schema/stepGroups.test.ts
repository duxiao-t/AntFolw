import { describe, expect, it } from 'vitest';
import type { MobileSchemaNode } from './types';
import { buildFormStepGroups, fieldIdsInStep } from './stepGroups';

describe('buildFormStepGroups', () => {
  it('uses span_layout nodes as named mobile steps', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'leave-time',
        type: 'span_layout',
        label: '请假时间',
        children: [
          { id: 'start', type: 'date', label: '开始时间' },
          { id: 'end', type: 'date', label: '结束时间' },
        ],
      },
      { id: 'reason', type: 'textarea', label: '请假事由' },
    ];

    const groups = buildFormStepGroups(schema, {});

    expect(groups).toHaveLength(2);
    expect(groups[0]).toMatchObject({ id: 'leave-time', title: '请假时间' });
    expect(fieldIdsInStep(groups[0])).toEqual(['start', 'end']);
    expect(groups[1]).toMatchObject({ id: 'auto-2', title: '补充信息' });
    expect(fieldIdsInStep(groups[1])).toEqual(['reason']);
  });

  it('uses a description before fields as step description', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'desc-1', type: 'description', label: '说明', props: { text: '请填写真实金额' } },
      { id: 'amount', type: 'money', label: '报销金额' },
      { id: 'invoice', type: 'file_upload', label: '发票' },
    ];

    const groups = buildFormStepGroups(schema, {});

    expect(groups).toHaveLength(1);
    expect(groups[0].title).toBe('报销金额');
    expect(groups[0].description).toBe('请填写真实金额');
    expect(fieldIdsInStep(groups[0])).toEqual(['amount', 'invoice']);
  });

  it('keeps table_list as its own step', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'title', type: 'text', label: '标题' },
      {
        id: 'items',
        type: 'table_list',
        label: '费用明细',
        children: [{ id: 'name', type: 'text', label: '名称' }],
      },
      { id: 'remark', type: 'textarea', label: '备注' },
    ];

    const groups = buildFormStepGroups(schema, {});

    expect(groups.map((group) => group.title)).toEqual(['基础信息', '费用明细', '补充信息']);
    expect(fieldIdsInStep(groups[1])).toEqual(['items']);
  });

  it('chunks ungrouped visible fields into groups of at most six', () => {
    const schema: MobileSchemaNode[] = Array.from({ length: 7 }, (_, index) => ({
      id: `field-${index + 1}`,
      type: 'text',
      label: `字段${index + 1}`,
    }));

    const groups = buildFormStepGroups(schema, {});

    expect(groups).toHaveLength(2);
    expect(fieldIdsInStep(groups[0])).toEqual(['field-1', 'field-2', 'field-3', 'field-4', 'field-5', 'field-6']);
    expect(fieldIdsInStep(groups[1])).toEqual(['field-7']);
  });

  it('excludes fields hidden by visibleWhen rules', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'needBudget', type: 'switch', label: '需要预算' },
      {
        id: 'budget',
        type: 'money',
        label: '预算金额',
        props: { displayCondition: { field: 'needBudget', operator: 'eq', value: true } },
      },
    ];

    const groups = buildFormStepGroups(schema, { needBudget: false });

    expect(fieldIdsInStep(groups[0])).toEqual(['needBudget']);
  });
});
