import { Col, Row } from 'antd';
import { FormRenderer } from '../FormRenderer/FormRenderer';
import type { FieldType } from '../../registry/types';

export const SpanLayoutField: FieldType = {
  type: 'span_layout',
  label: '分栏布局',
  icon: 'appstore',
  defaultProps: { columns: 2 },
  Component: ({ node, mode, value, onChange }) => {
    const cols = node.props?.columns ?? 2;
    return (
      <fieldset
        data-field-id={node.id}
        style={{ border: '1px dashed #bbb', padding: 12, margin: '8px 0' }}
      >
        <legend>{node.label ?? '分栏'}</legend>
        <Row gutter={12}>
          {(node.children ?? []).map((child) => (
            <Col span={24 / cols} key={child.id}>
              <FormRenderer
                schema={[child]}
                value={value?.[child.id]}
                onChange={(v: any) => onChange?.({ ...(value ?? {}), [child.id]: v })}
                mode={mode}
              />
            </Col>
          ))}
        </Row>
      </fieldset>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>分组标题</label>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <label>列数（1–4）</label>
      <input type="number" min={1} max={4} value={node.props?.columns ?? 2}
        onChange={(e) => onChange({ ...node, props: { ...node.props, columns: Number(e.target.value) } })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
    </div>
  ),
};
