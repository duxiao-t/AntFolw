import { Space, InputNumber, Select, Checkbox, ColorPicker, Typography } from 'antd';
import { PanelField } from './panelField';
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
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
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
          显示分割线
        </Checkbox>
        <PanelField label="分割线颜色">
          <ColorPicker
            disabled={props.showBorder === false}
            disabledAlpha
            value={props.dividerColor ?? '#d9d9d9'}
            onChangeComplete={(color) => updateProps({ dividerColor: color.toHexString() })}
          />
        </PanelField>
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
