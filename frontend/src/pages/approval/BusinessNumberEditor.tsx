import { ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, InputNumber, Select, Space, Switch, Typography } from 'antd';
import type { FormFieldOption } from '../designer/process/types';

const fieldTypes = new Set(['text', 'number', 'money', 'radio', 'select', 'search',
  'date', 'time', 'user_picker', 'dept_picker', 'scan_code']);

export default function BusinessNumberEditor({ fields }: { fields: FormFieldOption[] }) {
  const enabled = Form.useWatch(['businessNumber', 'enabled']);
  return (
    <div style={{ borderTop: '1px solid #edf0f2', marginTop: 20, paddingTop: 20 }}>
      <Form.Item label="自定义流水号" name={['businessNumber', 'enabled']} valuePropName="checked">
        <Switch checkedChildren="已启用" unCheckedChildren="使用默认12位单号" />
      </Form.Item>
      {enabled && <>
        <Form.Item label="全系统唯一前缀" name={['businessNumber', 'namespace']}
          rules={[{ required: true }, { pattern: /^[A-Za-z][A-Za-z0-9_-]{0,31}$/, message: '字母开头，最多32位' }]}
          extra="例如 BX、LEAVE；不同表单不能重复。">
          <Input maxLength={32} style={{ maxWidth: 320 }} />
        </Form.Item>
        <Form.Item label="序号重置" name={['businessNumber', 'reset']} initialValue="NONE">
          <Select style={{ width: 220 }} options={[
            { value: 'NONE', label: '永不重置' }, { value: 'DAILY', label: '每日重置' },
            { value: 'MONTHLY', label: '每月重置' }, { value: 'YEARLY', label: '每年重置' },
          ]} />
        </Form.Item>
        <Form.List name={['businessNumber', 'parts']}>
          {(parts, { add, remove, move }) => <Space orientation="vertical" style={{ width: '100%' }}>
            <Typography.Text strong>编号部件（唯一前缀会自动放在最前）</Typography.Text>
            {parts.map((part, index) => <Space key={part.key} align="start" wrap>
              <Form.Item name={[part.name, 'type']} rules={[{ required: true }]}>
                <Select style={{ width: 120 }} options={[
                  { value: 'LITERAL', label: '固定文本' }, { value: 'FIELD', label: '表单字段' },
                  { value: 'DATE', label: '日期' }, { value: 'SEQUENCE', label: '序号' },
                ]} />
              </Form.Item>
              <Form.Item noStyle shouldUpdate>
                {({ getFieldValue }) => {
                  const type = getFieldValue(['businessNumber', 'parts', index, 'type']);
                  if (type === 'FIELD') return <Form.Item name={[part.name, 'fieldId']} rules={[{ required: true }]}>
                    <Select showSearch={{ optionFilterProp: 'label' }} placeholder="选择单值字段" style={{ width: 220 }}
                      options={fields.filter((field) => fieldTypes.has(field.type)).map((field) => ({ value: field.id, label: field.label }))} />
                  </Form.Item>;
                  if (type === 'DATE') return <Form.Item name={[part.name, 'pattern']} rules={[{ required: true }]}>
                    <Select style={{ width: 160 }} options={['yyyy', 'yyyyMM', 'yyyyMMdd', 'yyMMdd'].map((value) => ({ value, label: value }))} />
                  </Form.Item>;
                  if (type === 'SEQUENCE') return <Form.Item name={[part.name, 'width']} rules={[{ required: true }]}>
                    <InputNumber min={1} max={12} placeholder="位数" style={{ width: 120 }} />
                  </Form.Item>;
                  return <Form.Item name={[part.name, 'value']} rules={[{ required: true }]}>
                    <Input maxLength={32} placeholder="如 - 或 BX-" style={{ width: 220 }} />
                  </Form.Item>;
                }}
              </Form.Item>
              <Button aria-label="上移" icon={<ArrowUpOutlined />} disabled={index === 0} onClick={() => move(index, index - 1)} />
              <Button aria-label="下移" icon={<ArrowDownOutlined />} disabled={index === parts.length - 1} onClick={() => move(index, index + 1)} />
              <Button danger aria-label="删除" icon={<DeleteOutlined />} onClick={() => remove(part.name)} />
            </Space>)}
            <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ type: 'LITERAL', value: '-' })}>添加编号部件</Button>
            <Form.Item noStyle shouldUpdate>
              {({ getFieldValue }) => <Typography.Text type="secondary">
                预览：{preview(getFieldValue('businessNumber'), fields)}
              </Typography.Text>}
            </Form.Item>
          </Space>}
        </Form.List>
      </>}
    </div>
  );
}

function preview(config: any, fields: FormFieldOption[]) {
  const sample: Record<string, string> = { yyyy: '2026', yyyyMM: '202609', yyyyMMdd: '20260901', yyMMdd: '260901' };
  return `${config?.namespace || 'PREFIX'}${(config?.parts ?? []).map((part: any) => {
    if (part.type === 'DATE') return sample[part.pattern] ?? '日期';
    if (part.type === 'SEQUENCE') return '1'.padStart(part.width || 4, '0');
    if (part.type === 'FIELD') return fields.find((field) => field.id === part.fieldId)?.label ?? '字段';
    return part.value ?? '';
  }).join('')}`;
}
