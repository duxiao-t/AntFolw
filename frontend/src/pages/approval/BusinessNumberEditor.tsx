import { ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, InputNumber, Select, Switch, Typography } from 'antd';
import { createStyles } from 'antd-style';
import type { FormFieldOption } from '../designer/process/types';

const fieldTypes = new Set(['text', 'number', 'money', 'radio', 'select', 'search',
  'date', 'time', 'user_picker', 'dept_picker', 'scan_code']);

const useStyles = createStyles(({ token }) => ({
  settingRow: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 24,
    padding: '15px 16px',
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: token.borderRadiusLG,
    background: token.colorFillAlter,
    '@media (max-width: 680px)': { alignItems: 'flex-start', flexDirection: 'column', gap: 12 },
  },
  settingCopy: { minWidth: 0 },
  settingLabel: { display: 'block', color: token.colorText, fontSize: 14, fontWeight: 600 },
  settingHint: { display: 'block', marginTop: 3, color: token.colorTextSecondary, fontSize: 12 },
  settingControl: {
    display: 'flex',
    flex: '0 0 auto',
    alignItems: 'center',
    gap: 10,
    color: token.colorTextSecondary,
    fontSize: 12,
  },
  configGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
    gap: '18px 24px',
    marginTop: 20,
    '& > .ant-form-item': { minWidth: 0, marginBottom: 0 },
    '@media (max-width: 960px)': { gridTemplateColumns: 'minmax(0, 1fr)' },
  },
  parts: { display: 'grid', gridColumn: '1 / -1', gap: 12 },
  partRow: {
    display: 'flex',
    flexWrap: 'wrap',
    alignItems: 'flex-start',
    gap: 10,
    padding: 12,
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: token.borderRadiusLG,
    background: token.colorBgContainer,
    '& .ant-form-item': { marginBottom: 0 },
  },
  preview: {
    padding: '10px 12px',
    borderRadius: token.borderRadiusLG,
    background: token.colorFillAlter,
    overflowWrap: 'anywhere',
  },
}));

export default function BusinessNumberEditor({ fields }: { fields: FormFieldOption[] }) {
  const { styles } = useStyles();
  const form = Form.useFormInstance();
  const enabled = Form.useWatch(['businessNumber', 'enabled'], form);
  return (
    <div>
      <div className={styles.settingRow}>
        <div className={styles.settingCopy}>
          <span className={styles.settingLabel}>自定义流水号</span>
          <span className={styles.settingHint}>关闭时使用系统默认的 12 位业务单号。</span>
        </div>
        <div className={styles.settingControl}>
          <span>{enabled ? '已启用' : '使用默认编号'}</span>
          <Form.Item name={['businessNumber', 'enabled']} valuePropName="checked" noStyle>
            <Switch aria-label="自定义流水号" />
          </Form.Item>
        </div>
      </div>
      {enabled && <div className={styles.configGrid}>
        <Form.Item label="全系统唯一前缀" name={['businessNumber', 'namespace']}
          rules={[{ required: true }, { pattern: /^[A-Za-z][A-Za-z0-9_-]{0,31}$/, message: '字母开头，最多32位' }]}
          extra="例如 BX、LEAVE；不同表单不能重复。">
          <Input maxLength={32} />
        </Form.Item>
        <Form.Item label="序号重置" name={['businessNumber', 'reset']} initialValue="NONE">
          <Select options={[
            { value: 'NONE', label: '永不重置' }, { value: 'DAILY', label: '每日重置' },
            { value: 'MONTHLY', label: '每月重置' }, { value: 'YEARLY', label: '每年重置' },
          ]} />
        </Form.Item>
        <Form.List name={['businessNumber', 'parts']}>
          {(parts, { add, remove, move }) => <div className={styles.parts}>
            <Typography.Text strong>编号部件（唯一前缀会自动放在最前）</Typography.Text>
            {parts.map((part, index) => <div className={styles.partRow} key={part.key}>
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
            </div>)}
            <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ type: 'LITERAL', value: '-' })}>添加编号部件</Button>
            <Form.Item noStyle shouldUpdate>
              {({ getFieldValue }) => <div className={styles.preview}>
                <Typography.Text type="secondary">预览：{preview(getFieldValue('businessNumber'), fields)}</Typography.Text>
              </div>}
            </Form.Item>
          </div>}
        </Form.List>
      </div>}
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
