import { PictureOutlined } from '@ant-design/icons';
import { MediaUploadControl } from './MediaUploadControl';
import { ReadonlyMediaList, mediaSummary } from './MediaPreview';
import type { FieldType } from '../../registry/types';

const DEFAULT_ACCEPT = 'image/*';

export const ImageUploadField: FieldType = {
  type: 'image_upload',
  label: '图片',
  icon: 'picture',
  defaultProps: {
    required: false,
    multiple: true,
    maxCount: 20,
    source: 'both',
    watermark: false,
    watermarkText: 'AntFlow',
    maxSizeMB: 10,
    accept: DEFAULT_ACCEPT,
  },
  Component: ({ node, mode, value, onChange }) => {
    const maxCount = node.props?.maxCount ?? 20;
    if (mode === 'designer-preview') {
      return (
        <div data-field-id={node.id}>
          <div style={{ display: 'block', marginBottom: 4 }}>
            {node.label}
            {node.props?.required ? ' *' : ''}
          </div>
          <div className="form-fields-media-placeholder">
            <PictureOutlined />
            <span>
              图片上传 · 最多 {maxCount} 张
              {node.props?.watermark ? ' · 带水印' : ''}
            </span>
          </div>
        </div>
      );
    }
    if (mode === 'readonly') {
      return (
        <div data-field-id={node.id}>
          <div style={{ display: 'block', marginBottom: 4 }}>
            {node.label}
            {node.props?.required ? ' *' : ''}
          </div>
          <div>{mediaSummary(value, 'image')}</div>
          <ReadonlyMediaList value={value} />
        </div>
      );
    }
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}
          {node.props?.required ? ' *' : ''}
        </div>
        <MediaUploadControl
          node={node}
          mode={mode}
          value={value}
          onChange={onChange}
          kind="image"
          multiple={node.props?.multiple !== false}
          maxCount={maxCount}
          maxSizeMB={node.props?.maxSizeMB}
          accept={node.props?.accept ?? DEFAULT_ACCEPT}
          watermark={!!node.props?.watermark}
          watermarkText={node.props?.watermarkText}
          buttonText={node.props?.buttonText}
          hint={node.props?.watermark ? '上传时会自动加水印' : undefined}
        />
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>标签</div>
      <input
        value={node.label ?? ''}
        onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }}
      />
      <label>
        <input
          type="checkbox"
          checked={!!node.props?.watermark}
          onChange={(e) =>
            onChange({
              ...node,
              props: { ...node.props, watermark: e.target.checked },
            })
          }
        />
        {' '}上传时添加水印
      </label>
    </div>
  ),
};
