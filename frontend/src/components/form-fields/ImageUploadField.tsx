import { Upload, Button, message } from 'antd';
import { PictureOutlined } from '@ant-design/icons';
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
          <div>
            {Array.isArray(value) && value.length > 0
              ? `已上传 ${value.length} 张图片`
              : '未上传图片'}
          </div>
        </div>
      );
    }
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}
          {node.props?.required ? ' *' : ''}
        </div>
        <Upload
          disabled={mode !== 'runtime-fill'}
          multiple
          accept={node.props?.accept ?? DEFAULT_ACCEPT}
          maxCount={maxCount}
          listType="picture"
          fileList={
            Array.isArray(value)
              ? (value as any[]).map((item, index) => ({
                  uid: item.id ?? String(index),
                  name: item.name ?? item.fileName ?? '图片',
                  status: 'done',
                  url: item.contentUrl ?? item.url,
                }))
              : []
          }
          beforeUpload={(file) => {
            const maxSizeMB = node.props?.maxSizeMB;
            if (maxSizeMB && file.size / 1024 / 1024 > maxSizeMB) {
              message.error(`单张图片不能超过 ${maxSizeMB}MB`);
              return Upload.LIST_IGNORE;
            }
            return false;
          }}
          onChange={(info) => {
            onChange?.(info.fileList as any);
          }}
        >
          <Button>
            {node.props?.buttonText || '添加图片'}
          </Button>
        </Upload>
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
