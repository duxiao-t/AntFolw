import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Input, Radio, Select, Space } from 'antd';
import type {
  ConditionOperator,
  ProcessCondition,
  ProcessConditionGroup,
  ProcessConditionProps,
} from '../types';

export type FieldDef = { id: string; label: string; type: string };
export type ConditionProps = ProcessConditionProps;

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

const listValue = (value: string | string[]): string[] =>
  Array.isArray(value) ? value : value.trim() ? [value.trim()] : [];

const scalarValue = (value: string | string[]): string =>
  Array.isArray(value) ? (value[0] ?? '') : value;

const normalizedList = (values: string[]): string[] => [
  ...new Set(values.map((value) => value.trim()).filter(Boolean)),
];

export function ConditionRulesEditor({
  props,
  formFields,
  onChange,
}: {
  props: ProcessConditionProps;
  formFields: FieldDef[];
  onChange: (next: ProcessConditionProps) => void;
}) {
  const sourceGroups: ProcessConditionGroup[] =
    props.groups && props.groups.length > 0
      ? props.groups
      : [{ id: 'draft_group', groupType: 'AND', conditions: [] }];
  const groups: ProcessConditionGroup[] = sourceGroups.map((group, index) => ({
    ...group,
    id: group.id ?? `legacy_group_${index}`,
  }));
  const groupsType = props.groupsType ?? 'OR';

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
              {group.conditions.map((condition) => (
                <Space.Compact key={condition.id} style={{ width: '100%' }}>
                  <Select
                    style={{ width: '42%' }}
                    value={condition.field || undefined}
                    placeholder="选择字段"
                    popupMatchSelectWidth={280}
                    showSearch={{ optionFilterProp: 'label' }}
                    onChange={(field: string) =>
                      updateGroup(groupId, (current) => ({
                        ...current,
                        id: groupId,
                        conditions: current.conditions.map((item) =>
                          item.id === condition.id ? { ...item, field } : item,
                        ),
                      }))
                    }
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
                    options={OPERATORS}
                  />
                  {condition.operator === 'in' ? (
                    <Select
                      mode="tags"
                      style={{ width: '26%' }}
                      value={listValue(condition.value)}
                      tokenSeparators={[',']}
                      placeholder="值"
                      onChange={(values: string[]) =>
                        updateGroup(groupId, (current) => ({
                          ...current,
                          id: groupId,
                          conditions: current.conditions.map((item) =>
                            item.id === condition.id
                              ? { ...item, value: normalizedList(values) }
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
              ))}
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
          {groups
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
