import { Col, Row } from 'antd';
import { FormRenderer } from '../FormRenderer/FormRenderer';
import type { FieldType } from '../../registry/types';

export const SpanLayoutField: FieldType = {
  type: 'span_layout',
  label: '分栏布局',
  icon: 'appstore',
  defaultProps: {
    columns: 2,
    gutter: 12,
    showBorder: true,
    dividerColor: '#d9d9d9',
    mobileSingleColumn: true,
  },
  Component: ({ node, mode, value, onChange, fieldModes, visibleIds }) => {
    const cols = node.props?.columns ?? 2;
    const span = Math.floor(24 / cols);
    return (
      <section
        data-field-id={node.id}
        style={{
          border: 0,
          borderBottom:
            mode === 'designer-preview' || node.props?.showBorder === false
              ? 0
              : `1px solid ${node.props?.dividerColor ?? '#d9d9d9'}`,
          paddingBottom:
            mode === 'designer-preview' || node.props?.showBorder === false ? 0 : 12,
          margin: '8px 0',
        }}
      >
        {mode !== 'designer-preview' && node.props?.showTitle !== false && (
          <div style={{ marginBottom: 8, fontWeight: 600 }}>{node.label ?? '分栏'}</div>
        )}
        <Row gutter={node.props?.gutter ?? 12}>
          {(node.children ?? [])
            .filter((child) => mode === 'designer-preview' || !visibleIds || visibleIds.has(child.id))
            .map((child) => (
              <Col
                xs={node.props?.mobileSingleColumn === false ? span : 24}
                sm={span}
                key={child.id}
              >
                <FormRenderer
                  schema={[child]}
                  value={value ?? {}}
                  onChange={(nextValue: any) => onChange?.(nextValue)}
                  mode={mode}
                  fieldModes={fieldModes}
                  visibleIds={visibleIds}
                />
              </Col>
            ))}
        </Row>
      </section>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>分组标题</div>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <div>列数（1–4）</div>
      <input type="number" min={1} max={4} value={node.props?.columns ?? 2}
        onChange={(e) => onChange({ ...node, props: { ...node.props, columns: Number(e.target.value) } })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
    </div>
  ),
};
