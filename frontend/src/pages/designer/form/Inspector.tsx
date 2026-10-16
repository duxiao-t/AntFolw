import { Button, Checkbox, Collapse, Empty, Input, InputNumber, Space, Typography } from 'antd';
import { AppstoreOutlined, CreditCardOutlined, DownCircleOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { PanelField } from '../../../components/form-fields/panelField';
import { findById, formRegistry } from '../../../registry/formRegistry';
import type { DisplayCondition, SchemaNode } from '../../../registry/types';
import { DisplayRulesEditor } from './DisplayRulesEditor';
import {
  normalizeSelectDisplayStyle,
  type SelectDisplayStyle,
} from '../../../registry/selectOptions';
import { useFormDesignerStore } from './useFormDesignerStore';
import { OptionSourceSettings } from './OptionSourceSettings';

const selectDisplayStyles: Array<{
  value: SelectDisplayStyle;
  label: string;
  icon: React.ReactNode;
}> = [
  { value: 'list', label: '列表', icon: <UnorderedListOutlined /> },
  { value: 'dropdown', label: '下拉选择', icon: <DownCircleOutlined /> },
  { value: 'block_single', label: '块状（单列）', icon: <CreditCardOutlined /> },
  { value: 'block_double', label: '块状（双列）', icon: <AppstoreOutlined /> },
];
const placeholderTypes = new Set([
  'text',
  'textarea',
  'number',
  'money',
  'date',
  'date_range',
  'select',
  'multi_select',
  'user_picker',
  'dept_picker',
  'scan_code',
]);

const requiredTypes = new Set([
  'text',
  'textarea',
  'number',
  'money',
  'date',
  'date_range',
  'select',
  'multi_select',
  'user_picker',
  'dept_picker',
  'file_upload',
  'image_upload',
  'video_upload',
  'scan_code',
  'audio_upload',
  'location',
  'matrix_fill',
]);

const defaultValueTypes = new Set(['text', 'textarea', 'number', 'money', 'date']);





/**
 * 「用户选择」字段的选择范围。抽成子组件是因为 Inspector 用 switch 渲染各字段类型，
 * 在里面直接调 useQuery 会破坏 hooks 规则。
 *
 * 一次只用一个维度，各自只管自己的取值；后端把它们当作相互独立的收窄条件。
 */


function InspectorHeader({
  label,
  type,
}: {
  label: string;
  type: string;
}) {
  return (
    <div style={{ padding: 16, borderBottom: '1px solid #f0f0f0' }}>
      <Typography.Text strong>{label}</Typography.Text>
      <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
        {type}
      </Typography.Text>
    </div>
  );
}

export function Inspector({ formId }: { formId?: number }) {
  const selectedId = useFormDesignerStore((s) => s.selectedId);
  const schema = useFormDesignerStore((s) => s.schema);
  const updateNode = useFormDesignerStore((s) => s.updateNode);
  const removeNode = useFormDesignerStore((s) => s.removeNode);

  if (!selectedId) {
    return (
      <div style={{ padding: 16 }}>
        <Empty description="在画布中选中一个字段以编辑" />
      </div>
    );
  }

  const node = findById(schema, selectedId);
  if (!node) return null;

  const fieldType = formRegistry[node.type];
  if (!fieldType) return null;

  const props = node.props ?? {};
  const condition: Partial<DisplayCondition> = props.displayCondition ?? {};
  const update = (patch: Partial<SchemaNode>) =>
    updateNode(node.id, { ...node, ...patch });
  const updateProps = (patch: Record<string, any>) =>
    update({
      props: {
        ...props,
        ...patch,
      },
    });

  const componentSettings = renderComponentSettings(node, updateProps);
  const isSelect = node.type === 'select' || node.type === 'multi_select';

  return (
    <div>
      <InspectorHeader label={fieldType.label} type={node.type} />
      <Collapse
        bordered={false}
        defaultActiveKey={[
          'basic',
          'component',
          'select-display',
          'logic-rules',
          'validation',
          'display',
        ]}
        items={[
          {
            key: 'basic',
            label: '基础设置',
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={12}>
                <PanelField label="字段标题">
                  <Input
                    value={node.label ?? ''}
                    placeholder={fieldType.label}
                    onChange={(event) => update({ label: event.target.value })}
                  />
                </PanelField>
                <PanelField label="题干说明">
                  <Input.TextArea
                    rows={3}
                    value={props.questionDescription ?? ''}
                    placeholder='请输入题干说明'
                    onChange={(event) =>
                      updateProps({ questionDescription: event.target.value })
                    }
                  />
                </PanelField>
                {placeholderTypes.has(node.type) && (
                  <PanelField label="输入内容提示">
                    <Input
                      value={props.placeholder ?? ''}
                      placeholder="请输入占位提示"
                      onChange={(event) =>
                        updateProps({ placeholder: event.target.value })
                      }
                    />
                  </PanelField>
                )}
                <PanelField label="字段标识">
                  <Input value={node.id} disabled />
                </PanelField>
                {defaultValueTypes.has(node.type) && (
                  <PanelField label="默认值">
                    {node.type === 'date' ? (
                      <Space direction="vertical" style={{ width: '100%' }} size={8}>
                        <Checkbox
                          checked={props.defaultNow === true}
                          onChange={(event) =>
                            updateProps({ defaultNow: event.target.checked })
                          }
                        >
                          默认当前时间
                        </Checkbox>
                        <Input
                          value={props.defaultValue ?? ''}
                          disabled={props.defaultNow === true}
                          placeholder={
                            String(props.format ?? 'YYYY-MM-DD').includes('HH:mm')
                              ? 'YYYY-MM-DD HH:mm'
                              : 'YYYY-MM-DD'
                          }
                          onChange={(event) =>
                            updateProps({ defaultValue: event.target.value })
                          }
                        />
                      </Space>
                    ) : (
                      <Input
                        value={props.defaultValue ?? ''}
                        placeholder="不填则无默认值"
                        onChange={(event) =>
                          updateProps({ defaultValue: event.target.value })
                        }
                      />
                    )}
                  </PanelField>
                )}
              </Space>
            ),
          },
          {
            key: 'component',
            label: '组件设置',
            children: componentSettings || (
              <Typography.Text type="secondary">
                当前组件暂无额外设置
              </Typography.Text>
            ),
          },
          ...(['select', 'multi_select', 'text', 'textarea', 'number'].includes(node.type) ? [{
            key: 'option-source', label: '外部数据与联动',
            children: <OptionSourceSettings key={node.id} formId={formId} node={node} schema={schema}
              update={updateProps} updateNode={updateNode} />,
          }] : []),
          ...(isSelect
            ? [
                {
                  key: 'select-display',
                  label: '展示样式',
                  children: renderSelectDisplaySettings(node, updateProps),
                },
              ]
            : []),
          ...(node.type === 'select'
            ? [
                {
                  key: 'logic-rules',
                  label: '逻辑规则',
                  children: (
                    <DisplayRulesEditor
                      key={node.id}
                      source={node}
                      schema={schema}
                    />
                  ),
                },
              ]
            : []),
          {
            key: 'validation',
            label: '校验规则',
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={12}>
                {requiredTypes.has(node.type) && (
                  <Checkbox
                    checked={!!props.required}
                    onChange={(event) =>
                      updateProps({ required: event.target.checked })
                    }
                  >
                    是否必填
                  </Checkbox>
                )}
                <PanelField label="错误提示文案">
                  <Input
                    value={props.validationMessage ?? ''}
                    placeholder="不填则使用默认提示"
                    onChange={(event) =>
                      updateProps({ validationMessage: event.target.value })
                    }
                  />
                </PanelField>
                {['text', 'textarea'].includes(node.type) && (
                  <>
                    <PanelField label="最小长度">
                      <InputNumber
                        min={0}
                        style={{ width: '100%' }}
                        value={props.minLength}
                        onChange={(value) => updateProps({ minLength: value })}
                      />
                    </PanelField>
                    <PanelField label="正则表达式">
                      <Input
                        value={props.pattern ?? ''}
                        placeholder="例如 ^1\\d{10}$"
                        onChange={(event) =>
                          updateProps({ pattern: event.target.value })
                        }
                      />
                    </PanelField>
                  </>
                )}
              </Space>
            ),
          },
          {
            key: 'display',
            label: '显示逻辑',
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={12}>
                <Checkbox
                  checked={props.showTitle !== false}
                  onChange={(event) =>
                    updateProps({ showTitle: event.target.checked })
                  }
                >
                  显示标题
                </Checkbox>
                <Checkbox
                  checked={props.showDescription !== false}
                  onChange={(event) =>
                    updateProps({ showDescription: event.target.checked })
                  }
                >
                  显示题干说明
                </Checkbox>
                <Checkbox
                  checked={!!props.hidden}
                  onChange={(event) => updateProps({ hidden: event.target.checked })}
                >
                  隐藏组件
                </Checkbox>
                {condition.fieldId && (
                  <Typography.Text type="secondary">
                    当前由「{findById(schema, condition.fieldId)?.label ?? condition.fieldId}」的字段显示规则控制。
                  </Typography.Text>
                )}
              </Space>
            ),
          },
        ]}
      />
      <div style={{ padding: 16 }}>
        <Button danger block onClick={() => removeNode(node.id)}>
          删除字段
        </Button>
      </div>
    </div>
  );
}

function renderComponentSettings(node: SchemaNode, updateProps: (patch: Record<string, any>) => void) {
  const ConfigPanel = formRegistry[node.type]?.ConfigPanel;
  return ConfigPanel ? <ConfigPanel node={node} updateProps={updateProps} /> : null;
}

function renderSelectDisplaySettings(
  node: SchemaNode,
  updateProps: (patch: Record<string, any>) => void,
) {
  const props = node.props ?? {};
  const displayStyle = normalizeSelectDisplayStyle(props.displayStyle);
  return (
    <div className="select-display-style" role="radiogroup" aria-label="展示样式">
      {selectDisplayStyles.map((item) => (
        // biome-ignore lint/a11y/useSemanticElements: Cards are buttons with explicit radio state.
        <button
          key={item.value}
          type="button"
          role="radio"
          aria-checked={displayStyle === item.value}
          className="select-display-style__item"
          onClick={() => updateProps({ displayStyle: item.value })}
        >
          <span className="select-display-style__icon" aria-hidden="true">
            {item.icon}
          </span>
          <span>{item.label}</span>
        </button>
      ))}
    </div>
  );
}
