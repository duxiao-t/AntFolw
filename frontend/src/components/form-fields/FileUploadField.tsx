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
};
