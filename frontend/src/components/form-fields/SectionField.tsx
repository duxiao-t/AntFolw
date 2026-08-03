import { useDroppable } from '@dnd-kit/core';
import { Empty } from 'antd';
import { FormRenderer } from '../FormRenderer/FormRenderer';
import type { FieldType } from '../../registry/types';

export const SECTION_DROP_PREFIX = 'section-drop:';

export const SectionField: FieldType = {
  type: 'section',
  label: '业务分区',
  icon: 'container',
  defaultProps: {
    description: '',
  },
  Component: ({ node, mode, value, onChange }) => {
    const isDesigner = mode === 'designer-preview';
    const isPreviewOnly = node.id.startsWith('preview_');
    const { isOver, setNodeRef } = useDroppable({
      id: `${SECTION_DROP_PREFIX}${node.id}`,
      disabled: !isDesigner || isPreviewOnly,
      data: { sectionId: node.id },
    });
    const children = node.children ?? [];
    const description = node.props?.description;

    return (
      <section
        ref={setNodeRef}
        data-designer-section-dropzone={isDesigner && !isPreviewOnly ? node.id : undefined}
        className={[
          'form-renderer__business-section',
          isDesigner ? 'form-renderer__business-section--designer' : '',
          isOver ? 'form-renderer__business-section--over' : '',
        ]
          .filter(Boolean)
          .join(' ')}
      >
        <header className="form-renderer__business-section-head">
          <strong>{node.label || '业务分区'}</strong>
          {typeof description === 'string' && description.trim() ? (
            <small>{description}</small>
          ) : null}
        </header>
        <div className="form-renderer__business-section-body">
          {children.length > 0 ? (
            <FormRenderer
              schema={children}
              mode={mode}
              value={value ?? {}}
              onChange={onChange}
            />
          ) : (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={isDesigner ? '拖入字段到本业务分区' : '暂无字段'}
            />
          )}
        </div>
      </section>
    );
  },
  ConfigPanel: () => null,
};

export default SectionField;
