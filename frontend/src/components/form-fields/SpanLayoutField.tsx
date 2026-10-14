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
  Component: ({ node, mode, value, onChange, fieldModes, visibleIds, optionContext, scopeSchema }) => {
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
                  optionContext={optionContext}
                  scopeSchema={scopeSchema}
                />
              </Col>
            ))}
        </Row>
      </section>
    );
  },
};
