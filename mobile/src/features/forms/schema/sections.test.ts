import { describe, expect, it } from 'vitest';
import type { MobileSchemaNode } from './types';
import { buildFormSections, fieldIdsInSection } from './sections';

describe('buildFormSections', () => {
  it('wraps legacy schemas without section nodes in one default business section', () => {
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

    const sections = buildFormSections(schema, {});
    const firstSection = groupAt(sections, 0);

    expect(sections).toHaveLength(1);
    expect(firstSection).toMatchObject({ id: 'default-section', title: '表单内容' });
    expect(firstSection.nodes.map((node) => node.id)).toEqual(['leave-time', 'reason']);
    expect(fieldIdsInSection(firstSection)).toEqual(['start', 'end', 'reason']);
  });

  it('uses explicit section nodes as named mobile business sections', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'basic',
        type: 'section',
        label: '基础信息',
        props: { description: '填写申请人与时间' },
        children: [
          { id: 'applicant', type: 'text', label: '申请人' },
          { id: 'date', type: 'date', label: '申请日期' },
        ],
      },
      {
        id: 'files',
        type: 'section',
        label: '附件材料',
        children: [
          { id: 'attachment', type: 'file_upload', label: '附件' },
          { id: 'photos', type: 'image_upload', label: '图片' },
        ],
      },
    ];

    const sections = buildFormSections(schema, {});

    expect(sections.map((section) => section.title)).toEqual(['基础信息', '附件材料']);
    expect(sections[0]?.description).toBe('填写申请人与时间');
    expect(fieldIdsInSection(groupAt(sections, 0))).toEqual(['applicant', 'date']);
    expect(fieldIdsInSection(groupAt(sections, 1))).toEqual(['attachment', 'photos']);
  });

  it('keeps loose legacy nodes around explicit sections instead of dropping them', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'title', type: 'text', label: '标题' },
      {
        id: 'business',
        type: 'section',
        label: '业务信息',
        children: [{ id: 'reason', type: 'textarea', label: '事由' }],
      },
      { id: 'remark', type: 'textarea', label: '备注' },
    ];

    const sections = buildFormSections(schema, {});

    expect(sections.map((section) => section.title)).toEqual(['表单内容', '业务信息', '补充信息']);
    expect(fieldIdsInSection(groupAt(sections, 0))).toEqual(['title']);
    expect(fieldIdsInSection(groupAt(sections, 1))).toEqual(['reason']);
    expect(fieldIdsInSection(groupAt(sections, 2))).toEqual(['remark']);
  });

  it('filters hidden descendants from visible sections', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'details',
        type: 'section',
        label: '明细信息',
        children: [
          { id: 'visible', type: 'text', label: '显示字段' },
          {
            id: 'hidden',
            type: 'text',
            label: '隐藏字段',
            props: { displayCondition: { field: 'showHidden', operator: 'eq', value: true } },
          },
        ],
      },
      {
        id: 'empty-details',
        type: 'section',
        label: '空分组',
        children: [{ id: 'also-hidden', type: 'text', props: { hidden: true } }],
      },
    ];

    const sections = buildFormSections(schema, { showHidden: false });
    const firstSection = groupAt(sections, 0);
    const firstNode = firstSection.nodes[0];

    expect(sections).toHaveLength(1);
    expect(fieldIdsInSection(firstSection)).toEqual(['visible']);
    expect(firstNode?.id).toBe('visible');
  });

  it('keeps description fields inside the default section', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'desc-1', type: 'description', label: '说明', props: { text: '请填写真实金额' } },
      { id: 'amount', type: 'money', label: '报销金额' },
      { id: 'invoice', type: 'file_upload', label: '发票' },
    ];

    const sections = buildFormSections(schema, {});
    const firstSection = groupAt(sections, 0);

    expect(sections).toHaveLength(1);
    expect(firstSection.title).toBe('表单内容');
    expect(firstSection.nodes.map((node) => node.id)).toEqual(['desc-1', 'amount', 'invoice']);
    expect(fieldIdsInSection(firstSection)).toEqual(['amount', 'invoice']);
  });

  it('keeps table_list inside its owning section', () => {
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

    const sections = buildFormSections(schema, {});
    const firstSection = groupAt(sections, 0);

    expect(sections.map((section) => section.title)).toEqual(['表单内容']);
    expect(fieldIdsInSection(firstSection)).toEqual(['title', 'items', 'remark']);
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

    const sections = buildFormSections(schema, { needBudget: false });
    const firstSection = groupAt(sections, 0);

    expect(fieldIdsInSection(firstSection)).toEqual(['needBudget']);
  });
});

function groupAt(groups: ReturnType<typeof buildFormSections>, index: number) {
  const group = groups[index];
  expect(group).toBeDefined();
  if (!group) {
    throw new Error(`Expected group at index ${index}`);
  }
  return group;
}
