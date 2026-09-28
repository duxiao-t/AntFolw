import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { request } from '@umijs/max';
import { Button, Input, Radio, Select, Space } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { isBoundOptionSource } from '../../../../components/form-fields/dynamicOptions';
import type {
  ConditionOperator,
  FormFieldOption,
  ProcessCondition,
  ProcessConditionGroup,
  ProcessConditionProps,
} from '../types';

export type FieldDef = {
  id: string;
  label: string;
  type: string;
  options?: Array<{ label: string; value: string | number; isOther?: boolean }>;
  optionSource?: FormFieldOption['optionSource'];
};
export type ConditionProps = ProcessConditionProps;

/** 设计器里的 schema 还没落库，查外部候选要走 preview 端点（带当前 schema 一起发）。 */
export type OptionPreviewContext = { formId?: number; schema?: unknown };

export const displayFieldLabel = (field: FieldDef): string => {
  const id = String(field.id ?? '').trim();
  const title = String(field.label ?? '').trim();
  return title && title !== id ? `${title} · ${id}` : id;
};

const OPERATORS: { value: ConditionOperator; label: string }[] = [
  { value: '==', label: '等于' },
  { value: '!=', label: '不等于' },
  { value: '>', label: '大于' },
  { value: '>=', label: '大于等于' },
  { value: '<', label: '小于' },
  { value: '<=', label: '小于等于' },
  { value: 'in', label: '包含于' },
  { value: 'contains', label: '包含' },
];

const rid = (prefix: string): string =>
  `${prefix}_${Math.random().toString(36).slice(2, 10)}`;

const emptyCondition = (): ProcessCondition => ({
  id: rid('c'),
  field: '',
  operator: '==',
  value: '',
});

const listValue = (
  value: string | number | Array<string | number>,
): Array<string | number> =>
  Array.isArray(value)
    ? value
    : typeof value === 'number'
      ? [value]
      : value.trim()
        ? [value.trim()]
        : [];

const scalarValue = (
  value: string | number | Array<string | number>,
): string | number =>
  Array.isArray(value) ? (value[0] ?? '') : value;

/** Select 的空值只认 `''`：数字 0 是合法选项值，写成 `scalarValue(v) || undefined` 会把它吞掉。 */
const scalarOptionValue = (
  value: string | number | Array<string | number>,
): string | number | undefined => {
  const scalar = scalarValue(value);
  return scalar === '' ? undefined : scalar;
};

const normalizedList = (values: string[]): string[] => [
  ...new Set(values.map((value) => value.trim()).filter(Boolean)),
];

const SINGLE_OPTION_TYPES = new Set(['select', 'radio']);
const MULTI_OPTION_TYPES = new Set(['multi_select', 'checkbox']);

function operatorsFor(field?: FieldDef) {
  if (field && SINGLE_OPTION_TYPES.has(field.type)) {
    return OPERATORS.filter(({ value }) => ['==', '!=', 'in'].includes(value));
  }
  if (field && MULTI_OPTION_TYPES.has(field.type)) {
    return OPERATORS.filter(({ value }) => value === 'contains');
  }
  return OPERATORS;
}

/** 绑了外部数据源的字段在条件里的"值"候选（按字段 id 缓存，重复引用只查一次）。 */
function useExternalOptions(
  formFields: FieldDef[],
  groups: ProcessConditionGroup[],
  preview?: OptionPreviewContext,
) {
  const [options, setOptions] = useState<Record<string, FieldDef['options']>>({});
  const requested = useRef(new Set<string>());
  const formId = preview?.formId;
  const schema = preview?.schema;
  const load = (field: FieldDef | undefined) => {
    if (!field || !formId || !schema) return;
    // 分步（cascade）字段要在填写时逐级 drill 才有最终候选，这里列不出来——保持内置选项。
    if (!isBoundOptionSource({ optionSource: field.optionSource }) || field.optionSource?.cascade) {
      return;
    }
    if (requested.current.has(field.id)) return;
    requested.current.add(field.id);
    request<any>(`/api/runtime/form-options/preview/${formId}`, {
      method: 'POST',
      // values 传空：设计期没有"当前填报值"，依赖型（dependency）字段因此拿不到候选，
      // 用户仍可手输值（下拉退化成 tags）。
      data: { schema, query: { fieldId: field.id, values: {}, page: 1, size: 100 } },
    })
      .then((page) => {
        // LEVEL（分步）响应的 items 是"某一级的取值"而不是选项，不能当候选。
        if (page?.stage !== 'OPTIONS') return;
        setOptions((current) => ({ ...current, [field.id]: page.items }));
      })
      .catch(() => requested.current.delete(field.id));
  };
  // 打开已有流程时，已配好的条件也要补上外部候选。
  const referencedKey = groups
    .flatMap((group) => group.conditions.map((condition) => condition.field))
    .join(',');
  useEffect(() => {
    for (const fieldId of referencedKey ? referencedKey.split(',') : []) {
      load(formFields.find((item) => item.id === fieldId));
    }
    // load 每次渲染都会重建，但内部只按 requested（ref）+ formId/schema 去重，不放进依赖。
  }, [referencedKey, formFields, formId, schema]);
  return { options, load };
}

export function ConditionRulesEditor({
  props,
  formFields,
  optionPreview,
  onChange,
}: {
  props: ProcessConditionProps;
  formFields: FieldDef[];
  optionPreview?: OptionPreviewContext;
  onChange: (next: ProcessConditionProps) => void;
}) {
  // 只有**没配过**（undefined）才给一个草稿组：删到零组是真的删掉了（原来删完立刻重建，
  // 看起来像"删不掉"）。零组的分支发布校验会拦（请完整配置分支条件）。
  const sourceGroups: ProcessConditionGroup[] =
    props.groups ?? [{ id: 'draft_group', groupType: 'AND', conditions: [] }];
  const groups: ProcessConditionGroup[] = sourceGroups.map((group, index) => ({
    ...group,
    id: group.id ?? `legacy_group_${index}`,
  }));
  const groupsType = props.groupsType ?? 'OR';
  const { options: externalOptions, load: loadExternalOptions } = useExternalOptions(
    formFields,
    groups,
    optionPreview,
  );

  const updateGroup = (
    groupId: string,
    mutate: (group: ProcessConditionGroup) => ProcessConditionGroup,
  ) => {
    onChange({
      ...props,
      groups: groups.map((group) =>
        group.id === groupId ? mutate(group) : group,
      ),
    });
  };

  return (
    <>
      <div className="pt-config-section">
        <div className="pt-config-section__head pt-config-section__head--stacked">
          <div>
            <strong>条件组之间</strong>
            <div className="pt-condition-logic__hint">
              {groupsType === 'OR'
                ? '任意一个条件组成立，分支就会执行'
                : '所有条件组都成立，分支才会执行'}
            </div>
          </div>
          <Radio.Group
            size="small"
            value={groupsType}
            onChange={(event) =>
              onChange({
                ...props,
                groupsType: event.target.value as 'OR' | 'AND',
              })
            }
            options={[
              { value: 'OR', label: '任一组（或）' },
              { value: 'AND', label: '所有组（且）' },
            ]}
          />
        </div>
      </div>

      {groups.map((group, groupIndex) => {
        const groupId = group.id as string;
        return (
          <div className="pt-config-section pt-condition-group" key={groupId}>
            <div className="pt-config-section__head">
              <strong>条件组 {groupIndex + 1}</strong>
              <Button
                type="text"
                danger
                size="small"
                icon={<MinusCircleOutlined />}
                onClick={() =>
                  onChange({
                    ...props,
                    groups: groups.filter((item) => item.id !== groupId),
                  })
                }
              >
                删除组
              </Button>
            </div>

            <div className="pt-condition-group__logic">
              <span>组内条件</span>
              <Radio.Group
                size="small"
                value={group.groupType}
                onChange={(event) =>
                  updateGroup(groupId, (current) => ({
                    ...current,
                    id: groupId,
                    groupType: event.target.value as 'OR' | 'AND',
                  }))
                }
                options={[
                  { value: 'AND', label: '全部满足（且）' },
                  { value: 'OR', label: '任一满足（或）' },
                ]}
              />
            </div>

            <Space vertical style={{ width: '100%' }} size={8}>
              {group.conditions.map((condition) => {
                const field = formFields.find(
                  (item) => item.id === condition.field,
                );
                // 绑了外部数据源就用服务端候选（内置 options 是绑定前的残留，不能再用）；
                // 服务端还没答上来时留空，用户仍可手输。
                const usesExternalOptions = Boolean(
                  field &&
                    isBoundOptionSource({ optionSource: field.optionSource }) &&
                    !field.optionSource?.cascade,
                );
                const optionChoices = usesExternalOptions
                  ? externalOptions[condition.field]
                  : field?.options?.filter((option) => !option.isOther);
                return (
                <Space.Compact key={condition.id} style={{ width: '100%' }}>
                  <Select
                    style={{ width: '42%' }}
                    value={condition.field || undefined}
                    placeholder="选择字段"
                    popupMatchSelectWidth={280}
                    showSearch={{ optionFilterProp: 'label' }}
                    onChange={(fieldId: string) => {
                      const next = formFields.find(({ id }) => id === fieldId);
                      loadExternalOptions(next);
                      updateGroup(groupId, (current) => ({
                        ...current,
                        id: groupId,
                        conditions: current.conditions.map((item) =>
                          item.id === condition.id
                            ? {
                                ...item,
                                field: fieldId,
                                operator: MULTI_OPTION_TYPES.has(next?.type ?? '')
                                  ? 'contains'
                                  : '==',
                                value: '',
                              }
                            : item,
                        ),
                      }));
                    }}
                    options={formFields.map((field) => ({
                      value: field.id,
                      label: displayFieldLabel(field),
                    }))}
                  />
                  <Select
                    style={{ width: '22%' }}
                    value={condition.operator}
                    onChange={(operator: ConditionOperator) =>
                      updateGroup(groupId, (current) => ({
                        ...current,
                        id: groupId,
                        conditions: current.conditions.map((item) =>
                          item.id === condition.id
                            ? {
                                ...item,
                                operator,
                                value:
                                  operator === 'in'
                                    ? listValue(item.value)
                                    : scalarValue(item.value),
                              }
                            : item,
                        ),
                      }))
                    }
                    options={operatorsFor(field)}
                  />
                  {condition.operator === 'in' ? (
                    <Select
                      mode={optionChoices ? 'multiple' : 'tags'}
                      style={{ width: '26%' }}
                      value={listValue(condition.value)}
                      tokenSeparators={[',']}
                      placeholder="值"
                      options={optionChoices}
                      onChange={(values: Array<string | number>) =>
                        updateGroup(groupId, (current) => ({
                          ...current,
                          id: groupId,
                          conditions: current.conditions.map((item) =>
                            item.id === condition.id
                              ? {
                                  ...item,
                                  value: optionChoices
                                    ? values
                                    : normalizedList(values as string[]),
                                }
                              : item,
                          ),
                        }))
                      }
                    />
                  ) : optionChoices ? (
                    <Select
                      style={{ width: '26%' }}
                      value={scalarOptionValue(condition.value)}
                      placeholder="选择选项"
                      options={optionChoices}
                      onChange={(value: string | number) =>
                        updateGroup(groupId, (current) => ({
                          ...current,
                          id: groupId,
                          conditions: current.conditions.map((item) =>
                            item.id === condition.id
                              ? { ...item, value }
                              : item,
                          ),
                        }))
                      }
                    />
                  ) : (
                    <Input
                      style={{ width: '26%' }}
                      value={scalarValue(condition.value)}
                      placeholder="值"
                      onChange={(event) =>
                        updateGroup(groupId, (current) => ({
                          ...current,
                          id: groupId,
                          conditions: current.conditions.map((item) =>
                            item.id === condition.id
                              ? { ...item, value: event.target.value }
                              : item,
                          ),
                        }))
                      }
                    />
                  )}
                  <Button
                    type="text"
                    danger
                    aria-label="删除条件"
                    icon={<MinusCircleOutlined />}
                    onClick={() =>
                      updateGroup(groupId, (current) => ({
                        ...current,
                        id: groupId,
                        conditions: current.conditions.filter(
                          (item) => item.id !== condition.id,
                        ),
                      }))
                    }
                  />
                </Space.Compact>
                );
              })}
              <Button
                type="dashed"
                size="small"
                icon={<PlusOutlined />}
                onClick={() =>
                  updateGroup(groupId, (current) => ({
                    ...current,
                    id: groupId,
                    conditions: [...current.conditions, emptyCondition()],
                  }))
                }
              >
                添加条件
              </Button>
            </Space>
          </div>
        );
      })}

      <div className="pt-condition-summary" aria-live="polite">
        <span>当前判断</span>
        <strong>
          {groups.length === 0
            ? '未配置条件组（发布前至少留一组）'
            : groups
                .map(
                  (group, index) =>
                    `条件组 ${index + 1}（${
                      group.groupType === 'AND' ? '全部条件' : '任一条件'
                    }）`,
                )
                .join(groupsType === 'AND' ? ' 且 ' : ' 或 ')}
        </strong>
      </div>

      <Button
        type="dashed"
        block
        icon={<PlusOutlined />}
        onClick={() =>
          onChange({
            ...props,
            groups: [
              ...groups,
              { id: rid('g'), groupType: 'AND', conditions: [] },
            ],
          })
        }
      >
        添加条件组
      </Button>
    </>
  );
}
