import { UploadOutlined } from '@ant-design/icons';
import { Upload, Button, message } from 'antd';
import type { FieldType } from '../../registry/types';

export const FileUploadField: FieldType = {
  type: 'file_upload',
  label: '文件上传',
  icon: 'upload',
  defaultProps: { required: false, multiple: false, accept: '' },
  Component: ({ node, mode, value, onChange }) => {
    if (mode === 'designer-preview') {
      return <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
        <div className="form-fields-media-placeholder">
          <UploadOutlined />
          <span>文件上传{node.props?.multiple ? ' · 支持多文件' : ''}</span>
        </div>
      </div>;
    }
    return <div data-field-id={node.id}>
      <div style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </div>
      <Upload
        disabled={mode !== 'runtime-fill'}
        multiple={!!node.props?.multiple}
        accept={node.props?.accept || undefined}
        maxCount={node.props?.maxCount ?? (node.props?.multiple ? undefined : 1)}
        fileList={Array.isArray(value) ? value : value ? [{ uid: '0', name: String(value), status: 'done' }] : []}
        beforeUpload={(file) => {
          const maxSizeMB = node.props?.maxSizeMB;
          if (maxSizeMB && file.size / 1024 / 1024 > maxSizeMB) {
            message.error(`单文件不能超过 ${maxSizeMB}MB`);
            return Upload.LIST_IGNORE;
          }
          return false;
        }}
        onChange={(info) => {
          if (node.props?.multiple) {
            onChange?.(info.fileList as any);
          } else {
            const last = info.fileList[info.fileList.length - 1];
            onChange?.(last ? (last as any).name : undefined);
          }
        }}
      >
        <Button>{node.props?.buttonText || '选择文件'}</Button>
      </Upload>
    </div>;
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>标签</div>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <div>accept 过滤（如 image/*,.pdf，留空=不限）</div>
      <input value={node.props?.accept ?? ''} onChange={(e) => onChange({ ...node, props: { ...node.props, accept: e.target.value } })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <label>
        <input type="checkbox" checked={!!node.props?.multiple}
          onChange={(e) => onChange({ ...node, props: { ...node.props, multiple: e.target.checked } })} />
        {' '}允许多文件
      </label>
    </div>
  ),
};
