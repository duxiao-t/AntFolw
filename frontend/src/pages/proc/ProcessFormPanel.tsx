import { Empty } from 'antd';
import { FormRenderer } from '../../components/FormRenderer/FormRenderer';
import type { FieldMode, SchemaNode } from '../../registry/types';
import { SectionHeading } from './detailSections';

/** 表单内容：只读渲染 + 当前节点可编辑字段。 */
export function ProcessFormPanel({
  schema,
  fieldModes,
  value,
  onChange,
  instanceId,
  hasEditableFields,
}: {
  schema: SchemaNode[];
  fieldModes: Record<string, FieldMode>;
  value: Record<string, any>;
  onChange?: (values: Record<string, any>) => void;
  instanceId: number;
  hasEditableFields: boolean;
}) {
  return (
    <section className="process-detail-panel form-detail-card" aria-labelledby="form-content-title">
      <SectionHeading
        kicker="FORM CONTENT"
        title="表单内容"
        id="form-content-title"
        extra={hasEditableFields ? '当前审批节点可修改部分字段' : '提交时内容'}
      />
      <div className="form-detail-card__body">
        {schema.length > 0 ? (
          <FormRenderer
            schema={schema}
            mode="readonly"
            fieldModes={fieldModes}
            optionContext={{ instanceId }}
            value={value}
            onChange={onChange}
          />
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可显示的表单内容" />
        )}
      </div>
    </section>
  );
}
