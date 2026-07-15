import { Input, Select } from 'antd';
import type { FieldType } from '../../registry/types';

export const SelectField: FieldType = {
  type: 'select',
  label: '下拉单选',
  icon: 'unordered-list',
  defaultProps: { required: false, options: [] as { label: string; value: string }[] },
  Component: ({ node, mode, value, onChange }) => {
    const opts: any[] = node.props?.options ?? [];
    return (
      <div data-field-id={node.id}>
        <label style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </label>
        <Select
          disabled={mode !== 'runtime-fill'}
          value={value}
          onChange={(v) => onChange?.(v)}
          options={opts}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => {
    const opts: { label: string; value: string }[] = node.props?.options ?? [];
    const updateOpts = (next: any[]) => onChange({ ...node, props: { ...node.props, options: next } });
    return (
      <div style={{ padding: 16, display: 'grid', gap: 8 }}>
        <label>标签</label>
        <Input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })} />
        <label>选项（每行一条：value|label，多行）</label>
        <textarea
          rows={5}
          value={opts.map((o) => `${o.value}|${o.label}`).join('\n')}
          style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }}
          onChange={(e) => {
            const next = e.target.value
              .split('\n')
              .map((line) => line.trim())
              .filter(Boolean)
              .map((line) => {
                const [v, l] = line.split('|');
                return { value: v ?? '', label: l ?? v ?? '' };
              });
            updateOpts(next);
          }}
        />
      </div>
    );
  },
};
