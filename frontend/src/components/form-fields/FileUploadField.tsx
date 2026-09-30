import { UploadOutlined } from '@ant-design/icons';
import { MediaUploadControl } from './MediaUploadControl';
import { ReadonlyMediaList, mediaSummary } from './MediaPreview';
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
    if (mode === 'readonly') {
      return <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
        <div>{mediaSummary(value, 'file')}</div>
        <ReadonlyMediaList value={value} />
      </div>;
    }
    return <div data-field-id={node.id}>
      <div style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </div>
      <MediaUploadControl
        node={node}
        mode={mode}
        value={value}
        onChange={onChange}
        kind="file"
        multiple={!!node.props?.multiple}
        maxCount={node.props?.maxCount ?? (node.props?.multiple ? undefined : 1)}
        maxSizeMB={node.props?.maxSizeMB}
        accept={node.props?.accept || undefined}
        buttonText={node.props?.buttonText}
      />
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
