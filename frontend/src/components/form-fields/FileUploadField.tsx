import { Space, Checkbox, InputNumber, Input } from 'antd';
import { PanelField } from './panelField';
import { UploadOutlined } from '@ant-design/icons';
import { MediaUploadControl } from './MediaUploadControl';
import { ReadonlyMediaList, mediaSummary } from './MediaPreview';
import type { FieldType } from '../../registry/types';

export const FileUploadField: FieldType = {
  type: 'file_upload',
  label: '文件上传',
  icon: 'upload',
  defaultProps: { required: false, multiple: false, accept: '' },
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Checkbox
          checked={!!props.multiple}
          onChange={(event) => updateProps({ multiple: event.target.checked })}
        >
          允许多文件
        </Checkbox>
        <PanelField label="最大上传数量">
          <InputNumber
            min={1}
            style={{ width: '100%' }}
            value={props.maxCount ?? 1}
            onChange={(value) => updateProps({ maxCount: value ?? 1 })}
          />
        </PanelField>
        <PanelField label="单文件大小限制(MB)">
          <InputNumber
            min={1}
            style={{ width: '100%' }}
            value={props.maxSizeMB}
            onChange={(value) => updateProps({ maxSizeMB: value })}
          />
        </PanelField>
        <PanelField label="格式白名单">
          <Input
            value={props.accept ?? ''}
            placeholder="如 image/*,.pdf"
            onChange={(event) => updateProps({ accept: event.target.value })}
          />
        </PanelField>
        <PanelField label="上传按钮文案">
          <Input
            value={props.buttonText ?? ''}
            placeholder="选择文件"
            onChange={(event) => updateProps({ buttonText: event.target.value })}
          />
        </PanelField>
      </Space>
    );
  },
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
