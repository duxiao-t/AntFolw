import {
  Button,
  Checkbox,
  Collapse,
  Divider,
  Empty,
  Input,
  InputNumber,
  Modal,
  Segmented,
  Select,
  Space,
  Typography,
} from 'antd';
import { DeleteOutlined, MinusOutlined, PlusOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { findById, formRegistry } from '../../../registry/formRegistry';
import type { SchemaNode } from '../../../registry/types';
import { SelectOptionsEditor } from './SelectOptionsEditor';
import { MatrixAxisEditor } from './MatrixAxisEditor';
import { normalizeMatrixProps } from '../../../components/form-fields/matrixFill';
import { useFormDesignerStore } from './useFormDesignerStore';

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
  'matrix_fill',
]);

const defaultValueTypes = new Set(['text', 'textarea', 'number', 'money', 'date']);

type ChecklistItemInput = {
  id?: string;
  label?: string;
  required?: boolean;
};

function ChecklistItemsEditor({
  value,
  onChange,
}: {
  value?: ChecklistItemInput[];
  onChange(items: { id: string; label: string; required: boolean }[]): void;
}) {
  const items = value ?? [];
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkText, setBulkText] = useState('');
  const normalized = items.map((item, index) => ({
    id: item.id ?? `item-${index}`,
    label: String(item.label ?? `检查项${index + 1}`),
    required: item.required !== false,
  }));
  const commit = (next: { id: string; label: string; required: boolean }[]) =>
    onChange(next);
  const update = (index: number, patch: Partial<{ label: string; required: boolean }>) =>
    commit(normalized.map((item, itemIndex) =>
      itemIndex === index ? { ...item, ...patch } : item,
    ));
  const add = () =>
    commit([
      ...normalized,
      { id: `item-${Date.now()}-${normalized.length}`, label: `检查项${normalized.length + 1}`, required: true },
    ]);
  const remove = (index: number) =>
    commit(normalized.filter((_, itemIndex) => itemIndex !== index));
  const applyBulk = () => {
    const lines = bulkText
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);
    if (lines.length === 0) return;
    const additions = lines.map((line, index) => ({
      id: `item-${Date.now()}-${normalized.length + index}`,
      label: line,
      required: true,
    }));
    commit([...normalized, ...additions]);
    setBulkOpen(false);
    setBulkText('');
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={8}>
      {normalized.map((item, index) => (
        <div key={item.id} className="checklist-config__row">
          <Input
            placeholder="检查内容"
            value={item.label}
            onChange={(event) => update(index, { label: event.target.value })}
          />
          <Checkbox
            checked={item.required}
            onChange={(event) => update(index, { required: event.target.checked })}
          >
            必检
          </Checkbox>
          <Button
            icon={<DeleteOutlined />}
            size="small"
            aria-label="删除检查项"
            onClick={() => remove(index)}
          />
        </div>
      ))}
      <Space>
        <Button size="small" icon={<PlusOutlined />} onClick={add}>
          新增检查项
        </Button>
        <Button size="small" onClick={() => setBulkOpen(true)}>
          批量添加
        </Button>
      </Space>
      <Modal
        title="批量添加检查项"
        open={bulkOpen}
        onOk={applyBulk}
        onCancel={() => setBulkOpen(false)}
        okText="添加"
        cancelText="取消"
      >
        <Typography.Text type="secondary">
          每行一条，按行拆分，自动生成检查项。
        </Typography.Text>
        <Input.TextArea
          rows={6}
          value={bulkText}
          placeholder={'例如：\n设备外观完好\n接地线连接牢固\n指示灯状态正常'}
          onChange={(event) => setBulkText(event.target.value)}
          style={{ marginTop: 12 }}
        />
      </Modal>
    </Space>
  );
}

function PanelField({
  children,
  label,
}: {
  children: React.ReactNode;
  label: string;
}) {
  return (
    <div style={{ display: 'grid', gap: 6 }}>
      <Typography.Text type="secondary">{label}</Typography.Text>
      {children}
    </div>
  );
}

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

function collectFieldOptions(nodes: SchemaNode[], currentId: string) {
  return nodes
    .flatMap((node): Array<{ label: string; value: string }> => [
      ...(node.id !== currentId
        ? [
            {
              label: node.label || formRegistry[node.type]?.label || node.type,
              value: node.id,
            },
          ]
        : []),
      ...collectFieldOptions(node.children ?? [], currentId),
    ]);
}

export function Inspector() {
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
  const condition = props.displayCondition ?? {};
  const fieldOptions = collectFieldOptions(schema, node.id);
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

  return (
    <div>
      <InspectorHeader label={fieldType.label} type={node.type} />
      <Collapse
        bordered={false}
        defaultActiveKey={['basic', 'component', 'validation', 'display']}
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
                <PanelField label="条件字段">
                  <Select
                    allowClear
                    value={condition.fieldId}
                    placeholder="选择字段后启用条件显示"
                    options={fieldOptions}
                    onChange={(fieldId) =>
                      updateProps({
                        displayCondition: {
                          ...condition,
                          fieldId,
                        },
                      })
                    }
                  />
                </PanelField>
                <PanelField label="条件关系">
                  <Select
                    value={condition.operator ?? 'eq'}
                    disabled={!condition.fieldId}
                    options={[
                      { label: '等于', value: 'eq' },
                      { label: '不等于', value: 'ne' },
                      { label: '包含', value: 'contains' },
                      { label: '为空', value: 'empty' },
                      { label: '不为空', value: 'notEmpty' },
                    ]}
                    onChange={(operator) =>
                      updateProps({
                        displayCondition: {
                          ...condition,
                          operator,
                        },
                      })
                    }
                  />
                </PanelField>
                {!['empty', 'notEmpty'].includes(condition.operator) && (
                  <PanelField label="条件值">
                    <Input
                      value={condition.value ?? ''}
                      disabled={!condition.fieldId}
                      onChange={(event) =>
                        updateProps({
                          displayCondition: {
                            ...condition,
                            value: event.target.value,
                          },
                        })
                      }
                    />
                  </PanelField>
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

function renderComponentSettings(
  node: SchemaNode,
  updateProps: (patch: Record<string, any>) => void,
) {
  const props = node.props ?? {};
  switch (node.type) {

    case 'text':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <PanelField label="输入类型">
            <Select
              value={props.inputType ?? 'text'}
              options={[
                { label: '文本', value: 'text' },
                { label: '数字', value: 'number' },
                { label: '邮箱', value: 'email' },
                { label: '手机号', value: 'tel' },
              ]}
              onChange={(value) => updateProps({ inputType: value })}
            />
          </PanelField>
          <PanelField label="最大长度">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.maxLength ?? 255}
              onChange={(value) => updateProps({ maxLength: value ?? 255 })}
            />
          </PanelField>
          <Checkbox
            checked={!!props.trim}
            onChange={(event) => updateProps({ trim: event.target.checked })}
          >
            自动去除首尾空格
          </Checkbox>
        </Space>
      );
    case 'textarea':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <PanelField label="行数">
            <InputNumber
              min={2}
              max={12}
              style={{ width: '100%' }}
              value={props.rows ?? 4}
              onChange={(value) => updateProps({ rows: value ?? 4 })}
            />
          </PanelField>
          <PanelField label="最大长度">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.maxLength ?? 2000}
              onChange={(value) => updateProps({ maxLength: value ?? 2000 })}
            />
          </PanelField>
          <Checkbox
            checked={!!props.showCount}
            onChange={(event) => updateProps({ showCount: event.target.checked })}
          >
            显示字数统计
          </Checkbox>
        </Space>
      );
    case 'number':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <PanelField label="最小值">
            <InputNumber
              style={{ width: '100%' }}
              value={props.min ?? 0}
              onChange={(value) => updateProps({ min: value ?? 0 })}
            />
          </PanelField>
          <PanelField label="最大值">
            <InputNumber
              style={{ width: '100%' }}
              value={props.max ?? 1000000}
              onChange={(value) => updateProps({ max: value ?? 1000000 })}
            />
          </PanelField>
          <PanelField label="小数位">
            <InputNumber
              min={0}
              max={8}
              style={{ width: '100%' }}
              value={props.precision ?? 0}
              onChange={(value) => updateProps({ precision: value ?? 0 })}
            />
          </PanelField>
          <PanelField label="步长">
            <InputNumber
              min={0}
              style={{ width: '100%' }}
              value={props.step ?? 1}
              onChange={(value) => updateProps({ step: value ?? 1 })}
            />
          </PanelField>
        </Space>
      );
    case 'money':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <PanelField label="前缀">
            <Input
              value={props.prefix ?? '¥'}
              onChange={(event) => updateProps({ prefix: event.target.value })}
            />
          </PanelField>
          <PanelField label="最小金额">
            <InputNumber
              style={{ width: '100%' }}
              value={props.min ?? 0}
              onChange={(value) => updateProps({ min: value ?? 0 })}
            />
          </PanelField>
          <PanelField label="最大金额">
            <InputNumber
              style={{ width: '100%' }}
              value={props.max}
              onChange={(value) => updateProps({ max: value })}
            />
          </PanelField>
          <PanelField label="小数位">
            <InputNumber
              min={0}
              max={8}
              style={{ width: '100%' }}
              value={props.precision ?? 2}
              onChange={(value) => updateProps({ precision: value ?? 2 })}
            />
          </PanelField>
        </Space>
      );
    case 'date':
    case 'date_range':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <PanelField label="日期格式">
            <Select
              value={props.format ?? 'YYYY-MM-DD'}
              options={[
                { label: 'YYYY-MM-DD', value: 'YYYY-MM-DD' },
                { label: 'YYYY/MM/DD', value: 'YYYY/MM/DD' },
                { label: 'YYYY-MM-DD HH:mm', value: 'YYYY-MM-DD HH:mm' },
              ]}
              onChange={(value) => updateProps({ format: value })}
            />
          </PanelField>
          <PanelField label="最早可选日期">
            <Input
              value={props.minDate ?? ''}
              placeholder="YYYY-MM-DD"
              onChange={(event) => updateProps({ minDate: event.target.value })}
            />
          </PanelField>
          <PanelField label="最晚可选日期">
            <Input
              value={props.maxDate ?? ''}
              placeholder="YYYY-MM-DD"
              onChange={(event) => updateProps({ maxDate: event.target.value })}
            />
          </PanelField>
        </Space>
      );
    case 'select':
    case 'multi_select':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <SelectOptionsEditor
            value={props.options}
            multiple={node.type === 'multi_select'}
            defaultValue={props.defaultValue}
            enableColors={props.enableOptionColor === true}
            onChange={(options) => updateProps({ options })}
            onDefaultChange={(defaultValue) => updateProps({ defaultValue })}
            onEnableColorsChange={(enableOptionColor, options) =>
              updateProps({ enableOptionColor, options })
            }
          />
          <Space direction="vertical" style={{ width: '100%' }} size={8}>
            <Checkbox
              checked={props.allowClear !== false}
              onChange={(event) => updateProps({ allowClear: event.target.checked })}
            >
              允许清空
            </Checkbox>
            <Checkbox
              checked={!!props.showSearch}
              onChange={(event) => updateProps({ showSearch: event.target.checked })}
            >
              支持搜索
            </Checkbox>
          </Space>
          {node.type === 'multi_select' && (
            <PanelField label="最多选择数">
              <InputNumber
                min={1}
                style={{ width: '100%' }}
                value={props.maxCount}
                onChange={(value) => updateProps({ maxCount: value })}
              />
            </PanelField>
          )}
        </Space>
      );
    case 'user_picker':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Checkbox
            checked={!!props.multiple}
            onChange={(event) => updateProps({ multiple: event.target.checked })}
          >
            允许多选
          </Checkbox>
          <PanelField label="最多选择人数">
            <InputNumber
              min={1}
              disabled={!props.multiple}
              style={{ width: '100%' }}
              value={props.maxCount}
              onChange={(value) => updateProps({ maxCount: value })}
            />
          </PanelField>
          <PanelField label="选择范围">
            <Select
              value={props.scopeType ?? 'all'}
              options={[
                { label: '全部用户', value: 'all' },
                { label: '指定部门', value: 'department' },
              ]}
              onChange={(scopeType) =>
                updateProps({
                  scopeType,
                  scopeDeptId:
                    scopeType === 'department' ? props.scopeDeptId : undefined,
                })
              }
            />
          </PanelField>
          {props.scopeType === 'department' && (
            <PanelField label="部门 ID">
              <InputNumber
                min={1}
                style={{ width: '100%' }}
                value={props.scopeDeptId}
                onChange={(value) => updateProps({ scopeDeptId: value })}
              />
            </PanelField>
          )}
          <Typography.Text type="secondary">
            当前用户接口支持按部门 ID 限定范围；可视化部门选择器后续接入。
          </Typography.Text>
        </Space>
      );
    case 'dept_picker':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Checkbox
            checked={!!props.multiple}
            onChange={(event) => updateProps({ multiple: event.target.checked })}
          >
            允许多选
          </Checkbox>
          <PanelField label="最多选择数">
            <InputNumber
              min={1}
              disabled={!props.multiple}
              style={{ width: '100%' }}
              value={props.maxCount}
              onChange={(value) => updateProps({ maxCount: value })}
            />
          </PanelField>
          <PanelField label="根部门 ID">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.rootDeptId}
              onChange={(value) => updateProps({ rootDeptId: value })}
            />
          </PanelField>
          <Checkbox
            checked={props.selectableParent !== false}
            onChange={(event) =>
              updateProps({ selectableParent: event.target.checked })
            }
          >
            允许选择父级部门
          </Checkbox>
          <Checkbox
            checked={!!props.leafOnly}
            onChange={(event) => updateProps({ leafOnly: event.target.checked })}
          >
            只允许选择末级部门
          </Checkbox>
          <Typography.Text type="secondary">
            根部门 ID 为空时展示全部部门树。
          </Typography.Text>
        </Space>
      );
    case 'file_upload':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Checkbox
            checked={!!props.multiple}
            onChange={(event) => updateProps({ multiple: event.target.checked })}
          >
            允许多文件
          </Checkbox>
          <PanelField label="最大上传数量">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.maxCount ?? 1}
              onChange={(value) => updateProps({ maxCount: value ?? 1 })}
            />
          </PanelField>
          <PanelField label="单文件大小限制(MB)">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.maxSizeMB}
              onChange={(value) => updateProps({ maxSizeMB: value })}
            />
          </PanelField>
          <PanelField label="格式白名单">
            <Input
              value={props.accept ?? ''}
              placeholder="如 image/*,.pdf"
              onChange={(event) => updateProps({ accept: event.target.value })}
            />
          </PanelField>
          <PanelField label="上传按钮文案">
            <Input
              value={props.buttonText ?? ''}
              placeholder="选择文件"
              onChange={(event) => updateProps({ buttonText: event.target.value })}
            />
          </PanelField>
        </Space>
      );
    case 'image_upload':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <PanelField label="图片来源">
            <Select
              value={props.source ?? 'both'}
              options={[
                { label: '仅拍照', value: 'camera' },
                { label: '仅从相册选择', value: 'album' },
                { label: '拍照或相册', value: 'both' },
              ]}
              onChange={(source) => updateProps({ source })}
            />
          </PanelField>
          <PanelField label="最多上传数量">
            <InputNumber
              min={1}
              max={20}
              style={{ width: '100%' }}
              value={props.maxCount ?? 20}
              onChange={(value) => updateProps({ maxCount: value ?? 20 })}
            />
          </PanelField>
          <PanelField label="单张大小限制(MB)">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.maxSizeMB}
              onChange={(value) => updateProps({ maxSizeMB: value })}
            />
          </PanelField>
          <Checkbox
            checked={!!props.watermark}
            onChange={(event) => updateProps({ watermark: event.target.checked })}
          >
            上传时添加水印
          </Checkbox>
          <PanelField label="水印文字">
            <Input
              value={props.watermarkText ?? 'AntFlow'}
              disabled={!props.watermark}
              onChange={(event) => updateProps({ watermarkText: event.target.value })}
            />
          </PanelField>
          <Typography.Text type="secondary">
            水印将叠加拍摄/上传时间，由服务端处理后存储。
          </Typography.Text>
        </Space>
      );
    case 'video_upload':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <PanelField label="视频来源">
            <Select
              value={props.source ?? 'both'}
              options={[
                { label: '仅拍摄', value: 'camera' },
                { label: '仅从相册选择', value: 'album' },
                { label: '拍摄或相册', value: 'both' },
              ]}
              onChange={(source) => updateProps({ source })}
            />
          </PanelField>
          <PanelField label="最长时长(秒)">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.maxDuration ?? 60}
              onChange={(value) => updateProps({ maxDuration: value ?? 60 })}
            />
          </PanelField>
          <PanelField label="单文件大小限制(MB)">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.maxSizeMB ?? 100}
              onChange={(value) => updateProps({ maxSizeMB: value })}
            />
          </PanelField>
          <Checkbox
            checked={!!props.watermark}
            onChange={(event) => updateProps({ watermark: event.target.checked })}
          >
            上传时添加水印
          </Checkbox>
          <PanelField label="水印文字">
            <Input
              value={props.watermarkText ?? 'AntFlow'}
              disabled={!props.watermark}
              onChange={(event) => updateProps({ watermarkText: event.target.value })}
            />
          </PanelField>
          <Typography.Text type="secondary">
            超过最长时长的视频将无法选择；水印由服务端处理后存储。
          </Typography.Text>
        </Space>
      );
    case 'checklist': {
      const checklistResults = Array.isArray(props.results) && props.results.length >= 2
        ? props.results
        : [
            { id: 'pass', label: '通过' },
            { id: 'fail', label: '不通过' },
            { id: 'na', label: '不适用' },
          ];
      const descMap = props.descriptionRequiredByResult ?? {};
      return (
        <Space
          direction="vertical"
          style={{ width: '100%' }}
          size={16}
          split={<Divider style={{ margin: 0 }} />}
        >
          <div className="checklist-config__section">
            <div className="checklist-config__section-title">检查项</div>
            <ChecklistItemsEditor
              value={props.items}
              onChange={(items) => updateProps({ items })}
            />
          </div>

          <div className="checklist-config__section">
            <div className="checklist-config__section-title">结果选项</div>
            <Typography.Text type="secondary" className="checklist-config__hint">
              2~4 个，文字可自定义；勾选「描述必填」的结果，填报时该项描述必须填写。
            </Typography.Text>
            <div className="checklist-config__rows">
              {checklistResults.map((result, index) => (
                <div key={result.id ?? index} className="checklist-config__row">
                  <Input
                    value={result.label ?? ''}
                    placeholder={`结果${index + 1}`}
                    onChange={(event) => {
                      const next = [...checklistResults];
                      next[index] = { ...result, label: event.target.value };
                      updateProps({ results: next });
                    }}
                  />
                  <Checkbox
                    checked={descMap[result.id] === true}
                    onChange={(event) =>
                      updateProps({
                        descriptionRequiredByResult: {
                          ...descMap,
                          [result.id]: event.target.checked,
                        },
                      })
                    }
                  >
                    描述必填
                  </Checkbox>
                  <Button
                    icon={<MinusOutlined />}
                    size="small"
                    disabled={checklistResults.length <= 2}
                    aria-label={`删除结果${index + 1}`}
                    onClick={() =>
                      updateProps({
                        results: checklistResults.filter((_, itemIndex) => itemIndex !== index),
                      })
                    }
                  />
                </div>
              ))}
            </div>
            <Button
              size="small"
              icon={<PlusOutlined />}
              disabled={checklistResults.length >= 4}
              onClick={() =>
                updateProps({
                  results: [
                    ...checklistResults,
                    {
                      id: `result-${Date.now()}`,
                      label: `结果${checklistResults.length + 1}`,
                    },
                  ],
                })
              }
            >
              新增结果
            </Button>
          </div>

          <div className="checklist-config__section">
            <div className="checklist-config__section-title">通用设置</div>
            <div className="checklist-config__rows">
              <Checkbox
                checked={props.allowDescription !== false}
                onChange={(event) =>
                  updateProps({ allowDescription: event.target.checked })
                }
              >
                允许填写图文描述（文字 + 现场照片）
              </Checkbox>
              <Checkbox
                checked={props.oneClick !== false}
                onChange={(event) => updateProps({ oneClick: event.target.checked })}
              >
                支持一键全部设为某结果
              </Checkbox>
            </div>
            <div className="checklist-config__inline-field">
              <span>每条现场照片最多</span>
              <InputNumber
                min={1}
                max={20}
                size="small"
                style={{ width: 96 }}
                value={props.photoMaxCount ?? 9}
                onChange={(value) => updateProps({ photoMaxCount: value ?? 9 })}
              />
            </div>
          </div>
        </Space>
      );
    }
    case 'span_layout':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <PanelField label="列数">
            <InputNumber
              min={1}
              max={4}
              style={{ width: '100%' }}
              value={props.columns ?? 2}
              onChange={(value) => updateProps({ columns: value ?? 2 })}
            />
          </PanelField>
          <PanelField label="栏间距">
            <Select
              value={props.gutter ?? 12}
              options={[
                { label: '紧凑', value: 8 },
                { label: '默认', value: 12 },
                { label: '宽松', value: 16 },
              ]}
              onChange={(gutter) => updateProps({ gutter })}
            />
          </PanelField>
          <Checkbox
            checked={props.showBorder !== false}
            onChange={(event) => updateProps({ showBorder: event.target.checked })}
          >
            显示边框
          </Checkbox>
          <Checkbox
            checked={props.mobileSingleColumn !== false}
            onChange={(event) =>
              updateProps({ mobileSingleColumn: event.target.checked })
            }
          >
            移动端自动单列
          </Checkbox>
          <Typography.Text type="secondary">
            子组件拖入分栏将在容器拖拽能力完成后开放。
          </Typography.Text>
        </Space>
      );
    case 'matrix_fill': {
      const matrix = normalizeMatrixProps(props);
      return (
        <Space
          direction="vertical"
          style={{ width: '100%' }}
          size={16}
          split={<Divider style={{ margin: 0 }} />}
        >
          <div style={{ display: 'grid', gap: 10 }}>
            <Typography.Text strong>矩阵行</Typography.Text>
            <MatrixAxisEditor
              axis="row"
              items={matrix.rows}
              max={matrix.maxRows}
              onChange={(rows) => updateProps({ rows })}
            />
            <PanelField label="最大行数（含填报时新增）">
              <InputNumber
                min={matrix.rows.length}
                precision={0}
                style={{ width: '100%' }}
                value={matrix.maxRows}
                onChange={(value) => updateProps({ maxRows: Math.max(value ?? 20, matrix.rows.length) })}
              />
            </PanelField>
          </div>

          <div style={{ display: 'grid', gap: 10 }}>
            <Typography.Text strong>矩阵列</Typography.Text>
            <MatrixAxisEditor
              axis="column"
              items={matrix.columns}
              max={matrix.maxColumns}
              onChange={(columns) => updateProps({ columns })}
            />
            <PanelField label="最大列数（含填报时新增）">
              <InputNumber
                min={matrix.columns.length}
                precision={0}
                style={{ width: '100%' }}
                value={matrix.maxColumns}
                onChange={(value) => updateProps({ maxColumns: Math.max(value ?? 10, matrix.columns.length) })}
              />
            </PanelField>
          </div>

          <div style={{ display: 'grid', gap: 12 }}>
            <PanelField label="单元格类型">
              <Segmented
                block
                value={matrix.cellType}
                options={[
                  { label: '多行文本', value: 'textarea' },
                  { label: '数字', value: 'number' },
                ]}
                onChange={(cellType) => updateProps({ cellType })}
              />
            </PanelField>
            {matrix.cellType === 'textarea' ? (
              <PanelField label="最大字符数">
                <InputNumber
                  min={1}
                  precision={0}
                  style={{ width: '100%' }}
                  value={matrix.maxLength}
                  onChange={(value) => updateProps({ maxLength: value ?? 2000 })}
                />
              </PanelField>
            ) : (
              <>
                <PanelField label="最小值">
                  <InputNumber
                    style={{ width: '100%' }}
                    value={matrix.min}
                    max={matrix.max}
                    onChange={(value) => updateProps({ min: value ?? undefined })}
                  />
                </PanelField>
                <PanelField label="最大值">
                  <InputNumber
                    style={{ width: '100%' }}
                    value={matrix.max}
                    min={matrix.min}
                    onChange={(value) => updateProps({ max: value ?? undefined })}
                  />
                </PanelField>
                <PanelField label="小数位数">
                  <InputNumber
                    min={0}
                    precision={0}
                    style={{ width: '100%' }}
                    value={matrix.precision}
                    onChange={(value) => updateProps({ precision: value ?? 0 })}
                  />
                </PanelField>
              </>
            )}
          </div>
        </Space>
      );
    }
    case 'table_list':
      return (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <PanelField label="默认行数">
            <InputNumber
              min={0}
              style={{ width: '100%' }}
              value={props.defaultRows ?? props.minRows ?? 1}
              onChange={(value) => updateProps({ defaultRows: value ?? 0 })}
            />
          </PanelField>
          <PanelField label="最小行数">
            <InputNumber
              min={0}
              style={{ width: '100%' }}
              value={props.minRows ?? 1}
              onChange={(value) => updateProps({ minRows: value ?? 1 })}
            />
          </PanelField>
          <PanelField label="最大行数">
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              value={props.maxRows ?? 50}
              onChange={(value) => updateProps({ maxRows: value ?? 50 })}
            />
          </PanelField>
          <Checkbox
            checked={props.allowAdd !== false}
            onChange={(event) => updateProps({ allowAdd: event.target.checked })}
          >
            允许新增行
          </Checkbox>
          <Checkbox
            checked={props.allowDelete !== false}
            onChange={(event) =>
              updateProps({ allowDelete: event.target.checked })
            }
          >
            允许删除行
          </Checkbox>
          <PanelField label="新增按钮文案">
            <Input
              value={props.addButtonText ?? ''}
              placeholder="新增一行"
              onChange={(event) =>
                updateProps({ addButtonText: event.target.value })
              }
            />
          </PanelField>
          <PanelField label="移动端展示">
            <Select
              value={props.mobileMode ?? 'card'}
              options={[
                { label: '卡片式', value: 'card' },
                { label: '表格式', value: 'table' },
              ]}
              onChange={(mobileMode) => updateProps({ mobileMode })}
            />
          </PanelField>
          <Typography.Text type="secondary">
            明细表子字段设计将在容器字段设计能力完成后开放。
          </Typography.Text>
        </Space>
      );
    default:
      return null;
  }
}
