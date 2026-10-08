import { describe, expect, it } from 'vitest';
import { withPendingUpload } from '../components/form-fields/nativeMedia';
import { collectVisibleValues, firstVisibleValidationError } from './displayConditions';
import type { SchemaNode } from './types';

const schema: SchemaNode[] = [
  { id: 'kind', type: 'select' },
  {
    id: 'detail',
    type: 'text',
    label: '详情',
    props: {
      required: true,
      displayCondition: { fieldId: 'kind', operator: 'in', value: ['a', 'b'] },
    },
  },
];

describe('display conditions', () => {
  it('filters hidden values from submission without mutating the form session', () => {
    const values = { kind: 'c', detail: '保留的本次输入' };
    expect(collectVisibleValues(schema, values)).toEqual({ kind: 'c' });
    expect(values.detail).toBe('保留的本次输入');
    expect(firstVisibleValidationError(schema, values)).toBeNull();
    expect(firstVisibleValidationError(schema, { kind: 'a', detail: '' })).toBe('请填写详情');
  });

  it('treats cleared number bounds as unlimited', () => {
    const number: SchemaNode[] = [{ id: 'count', type: 'number', label: '数量', props: {} }];
    expect(firstVisibleValidationError(number, { count: -100 })).toBeNull();
    expect(firstVisibleValidationError(number, { count: 10000000 })).toBeNull();
  });

  it('hides downstream rules when their source field is itself hidden', () => {
    const nested: SchemaNode[] = [
      { id: 'level1', type: 'select' },
      {
        id: 'level2',
        type: 'select',
        props: { displayCondition: { fieldId: 'level1', operator: 'eq', value: 'show' } },
      },
      {
        id: 'level3',
        type: 'text',
        props: { displayCondition: { fieldId: 'level2', operator: 'eq', value: 'show' } },
      },
    ];
    const values = { level1: 'hide', level2: 'show', level3: '保留值' };

    expect(collectVisibleValues(nested, values)).toEqual({ level1: 'hide' });
    expect(values.level2).toBe('show');
  });

  /**
   * 条件引用**后面才声明**的字段。以前是一遍 forEach 边走边判，那时来源字段还没算过，
   * "来源不可见"被判成"依赖它的字段不可见"——同一份数据前后端能得出两套可见性。
   */
  it('resolves conditions whose source field is declared later in the schema', () => {
    const reversed: SchemaNode[] = [
      {
        id: 'second',
        type: 'text',
        props: { displayCondition: { fieldId: 'first', operator: 'eq', value: 'show' } },
      },
      { id: 'first', type: 'select' },
    ];

    expect(collectVisibleValues(reversed, { first: 'show', second: '值' }))
      .toEqual({ first: 'show', second: '值' });
    expect(collectVisibleValues(reversed, { first: 'hide', second: '值' }))
      .toEqual({ first: 'hide' });
  });

  /** `Number(null)`/`Number('')` 都是 0：空数字字段能"满足" `gte 0`，后端（BigDecimal 解析失败即 false）不能。 */
  it('does not let an empty number satisfy a numeric condition', () => {
    const nodes: SchemaNode[] = [
      { id: 'amount', type: 'number' },
      {
        id: 'note',
        type: 'text',
        props: { displayCondition: { fieldId: 'amount', operator: 'gte', value: 0 } },
      },
    ];

    expect(Object.keys(collectVisibleValues(nodes, { amount: null, note: 'x' }))).toEqual(['amount']);
    expect(Object.keys(collectVisibleValues(nodes, { amount: '', note: 'x' }))).toEqual(['amount']);
    expect(Object.keys(collectVisibleValues(nodes, { amount: 0, note: 'x' })))
      .toEqual(['amount', 'note']);
  });

  it('validates every detail row and enforces the row bounds', () => {
    const nodes: SchemaNode[] = [{
      id: 'items',
      type: 'table_list',
      label: '明细',
      props: { minRows: 1, maxRows: 2 },
      children: [{ id: 'name', type: 'text', label: '名称', props: { required: true } }],
    }];

    expect(firstVisibleValidationError(nodes, { items: [{ name: '' }] }))
      .toBe('第1行: 请填写名称');
    expect(firstVisibleValidationError(nodes, { items: [{ name: 'A' }] })).toBeNull();
    expect(firstVisibleValidationError(nodes, {
      items: [{ name: 'A' }, { name: 'B' }, { name: 'C' }],
    })).toBe('最多可填写2行');
    // 没填的选填明细表：不能因为 `minRows` 的默认值被拦住（0 行交给通用必填判断）。
    expect(firstVisibleValidationError(nodes, {})).toBeNull();
  });

  /** 按行条件隐藏的列：提交时会被剔除，校验（含"还在传"扫描）也不能看它的原始值。 */
  it('ignores upload markers on a column hidden for that row', () => {
    const nodes: SchemaNode[] = [{
      id: 'items',
      type: 'table_list',
      label: '明细',
      props: { minRows: 1 },
      children: [
        { id: 'hasPhoto', type: 'select' },
        {
          id: 'photo',
          type: 'image_upload',
          props: { displayCondition: { fieldId: 'hasPhoto', operator: 'eq', value: 'yes' } },
        },
      ],
    }];
    const marked = withPendingUpload([{ id: 'f1', name: 'a.png', contentType: 'image/png' }], true);

    expect(firstVisibleValidationError(nodes, { items: [{ hasPhoto: 'no', photo: marked }] }))
      .toBeNull();
    expect(firstVisibleValidationError(nodes, { items: [{ hasPhoto: 'yes', photo: marked }] }))
      .toBe('表格里仍有文件未完成上传');
  });

  /**
   * 检查项以前只被通用判空看"数组非空"——而数组里每个元素天生就存在（status 为空串），
   * 于是一个都没选也能提交。逐项必选/按结果要求描述/照片上限照移动端口径补上。
   */
  it('validates checklist items instead of only checking the array is non-empty', () => {
    const nodes: SchemaNode[] = [{
      id: 'checks',
      type: 'checklist',
      label: '检查项',
      props: {
        required: true,
        items: [{ id: 'i1', label: '检查项1', required: true }],
        descriptionRequiredByResult: { fail: true },
      },
    }];

    expect(firstVisibleValidationError(nodes, { checks: [{ id: 'i1', status: '' }] }))
      .toBe('请完成检查项「检查项1」');
    expect(firstVisibleValidationError(nodes, { checks: [{ id: 'i1', status: 'fail' }] }))
      .toBe('「检查项1」的描述必填');
    expect(firstVisibleValidationError(nodes, {
      checks: [{ id: 'i1', status: 'fail', description: '已处理' }],
    })).toBeNull();
    expect(firstVisibleValidationError(nodes, { checks: [{ id: 'i1', status: 'pass' }] })).toBeNull();
  });

  it('caps checklist photos per item', () => {
    const nodes: SchemaNode[] = [{
      id: 'checks',
      type: 'checklist',
      label: '检查项',
      props: {
        allowDescription: false,
        photoMaxCount: 1,
        items: [{ id: 'i1', label: '检查项1' }],
      },
    }];

    expect(firstVisibleValidationError(nodes, {
      checks: [{ id: 'i1', status: 'pass', images: [{ id: 'f1' }, { id: 'f2' }] }],
    })).toBe('「检查项1」最多上传 1 张照片');
    expect(firstVisibleValidationError(nodes, {
      checks: [{ id: 'i1', status: 'pass', images: [{ id: 'f1' }] }],
    })).toBeNull();
  });
});
