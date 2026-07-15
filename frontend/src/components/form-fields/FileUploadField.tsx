import { Upload, Button } from 'antd';
import type { FieldType } from '../../registry/types';

export const FileUploadField: FieldType = {
  type: 'file_upload',
  label: '文件上传',
  icon: 'upload',
  defaultProps: { required: false, multiple: false, accept: '' },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <label style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </label>
      <Upload
        disabled={mode !== 'runtime-fill'}
        multiple={!!node.props?.multiple}
        accept={node.props?.accept || undefined}
        fileList={Array.isArray(value) ? value : value ? [{ uid: '0', name: String(value), status: 'done' }] : []}
        beforeUpload={() => false}   // MVP: skip actual upload server, just keep filename in state
        onChange={(info) => {
          if (node.props?.multiple) {
            onChange?.(info.fileList as any);
          } else {
            const last = info.fileList[info.fileList.length - 1];
            onChange?.(last ? (last as any).name : undefined);
          }
        }}
      >
        <Button>选择文件</Button>
      </Upload>
    </div>
  ),
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>标签</label>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <label>accept 过滤（如 image/*,.pdf，留空=不限）</label>
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
