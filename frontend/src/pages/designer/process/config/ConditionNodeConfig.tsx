import {
  Alert,
  Button,
  Form,
  Input,
  Radio,
  Select,
  Space,
} from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

type FieldDef = { id: string; label: string; type: string };
type Operator =
  | '=='
  | '!='
  | '>'
  | '>='
  | '<'
  | '<='
  | 'in'
  | 'contains';
type Condition = {
  id: string;
  field: string;
  operator: Operator;
  value: string;
};
type Group = { id: string; groupType: 'OR' | 'AND'; conditions: Condition[] };
type ConditionProps = {
  isDefault?: boolean;
  groupsType?: 'OR' | 'AND';
  groups?: Group[];
};

const OPERATORS: { value: Operator; label: string }[] = [
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

const emptyCondition = (): Condition => ({
  id: rid('c'),
  field: '',
  operator: '==',
  value: '',
});

export function ConditionNodeConfig({
  node,
  formFields,
}: {
  node: TreeNode;
  formFields: FieldDef[];
}) {
  const updateProps = useProcessDesignerStore((s) => s.updateProps);
  const updateName = useProcessDesignerStore((s) => s.updateName);
  const p = (node.props ?? {}) as ConditionProps;
  const isDefault = p.isDefault === true;

  if (isDefault) {
    return (
      <Alert
        type="info"
        showIcon
        style={{ margin: 16 }}
        message="默认分支：其它条件都不满足时进入。"
      />
    );
  }

  const groups: Group[] =
    p.groups && p.groups.length > 0
      ? p.groups
      : [{ id: rid('g'), groupType: 'AND', conditions: [] }];

  const setProps = (next: ConditionProps): void => {
    updateProps(node.id, next);
  };

  const onGroupsTypeChange = (v: 'OR' | 'AND'): void => {
    setProps({ ...p, groupsType: v });
  };

  const updateGroup = (groupId: string, mut: (g: Group) => Group): void => {
    const nextGroups = groups.map((g) => (g.id === groupId ? mut(g) : g));
    setProps({ ...p, groups: nextGroups });
  };

  const onGroupTypeChange = (groupId: string, v: 'OR' | 'AND'): void => {
    updateGroup(groupId, (g) => ({ ...g, groupType: v }));
  };

  const onConditionChange = (
    groupId: string,
    conditionId: string,
    patch: Partial<Condition>,
  ): void => {
    updateGroup(groupId, (g) => ({
      ...g,
      conditions: g.conditions.map((c) =>
        c.id === conditionId ? { ...c, ...patch } : c,
      ),
    }));
  };

  const onAddCondition = (groupId: string): void => {
    updateGroup(groupId, (g) => ({
      ...g,
      conditions: [...g.conditions, emptyCondition()],
    }));
  };

  const onRemoveCondition = (
    groupId: string,
    conditionId: string,
  ): void => {
    updateGroup(groupId, (g) => ({
      ...g,
      conditions: g.conditions.filter((c) => c.id !== conditionId),
    }));
  };

  const onAddGroup = (): void => {
    setProps({
      ...p,
      groups: [...groups, { id: rid('g'), groupType: 'AND', conditions: [] }],
    });
  };

  const onRemoveGroup = (groupId: string): void => {
    setProps({ ...p, groups: groups.filter((g) => g.id !== groupId) });
  };

  return (
    <Form layout="vertical" style={{ padding: 16 }}>
      <Form.Item label="分支名称">
        <Input
          value={node.name ?? ''}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
            updateName(node.id, e.target.value)
          }
        />
      </Form.Item>
      <Form.Item label="条件组之间">
        <Radio.Group
          value={p.groupsType ?? 'OR'}
          onChange={(e) => onGroupsTypeChange(e.target.value as 'OR' | 'AND')}
          options={[
            { value: 'OR', label: '满足任一组' },
            { value: 'AND', label: '满足所有组' },
          ]}
        />
      </Form.Item>

      {groups.map((g) => (
        <div
          key={g.id}
          style={{
            border: '1px solid #d9d9d9',
            borderRadius: 6,
            padding: 12,
            marginBottom: 12,
          }}
        >
          <Space style={{ marginBottom: 8 }} wrap>
            <span>组内关系</span>
            <Radio.Group
              value={g.groupType}
              onChange={(e) =>
                onGroupTypeChange(g.id, e.target.value as 'OR' | 'AND')
              }
              options={[
                { value: 'AND', label: '且' },
                { value: 'OR', label: '或' },
              ]}
            />
            <Button
              type="link"
              danger
              icon={<MinusCircleOutlined />}
              onClick={() => onRemoveGroup(g.id)}
            >
              删除组
            </Button>
          </Space>

          <Space direction="vertical" style={{ width: '100%' }} size={8}>
            {g.conditions.map((c) => (
              <Space.Compact key={c.id} style={{ width: '100%' }}>
                <Select
                  style={{ width: '35%' }}
                  value={c.field || undefined}
                  placeholder="选择字段"
                  showSearch
                  optionFilterProp="label"
                  onChange={(v: string) =>
                    onConditionChange(g.id, c.id, { field: v })
                  }
                  options={formFields.map((f) => ({
                    value: f.id,
                    label: f.label || f.id,
                  }))}
                />
                <Select
                  style={{ width: '25%' }}
                  value={c.operator}
                  onChange={(v: Operator) =>
                    onConditionChange(g.id, c.id, { operator: v })
                  }
                  options={OPERATORS}
                />
                <Input
                  style={{ width: '30%' }}
                  value={c.value}
                  placeholder="值"
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    onConditionChange(g.id, c.id, { value: e.target.value })
                  }
                />
                <Button
                  type="text"
                  danger
                  icon={<MinusCircleOutlined />}
                  onClick={() => onRemoveCondition(g.id, c.id)}
                />
              </Space.Compact>
            ))}
            <Button
              type="dashed"
              size="small"
              icon={<PlusOutlined />}
              onClick={() => onAddCondition(g.id)}
            >
              添加条件
            </Button>
          </Space>
        </div>
      ))}

      <Button
        type="dashed"
        block
        icon={<PlusOutlined />}
        onClick={onAddGroup}
      >
        添加条件组
      </Button>
    </Form>
  );
}