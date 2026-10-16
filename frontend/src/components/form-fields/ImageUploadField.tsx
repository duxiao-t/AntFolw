import { Space, Select, InputNumber, Checkbox, Input, Typography } from 'antd';
import { PanelField } from './panelField';
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
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <PanelField label="图片来源">
          <Select
            value={props.source ?? 'both'}
            options={[
              { label: '仅拍照', value: 'camera' },
              { label: '仅从相册选择', value: 'album' },
              { label: '拍照或相册', value: 'both' },
            ]}
            onChange={(source) => updateProps({ source })}
          />
        </PanelField>
        <PanelField label="最多上传数量">
          <InputNumber
            min={1}
            max={20}
            style={{ width: '100%' }}
            value={props.maxCount ?? 20}
            onChange={(value) => updateProps({ maxCount: value ?? 20 })}
          />
        </PanelField>
        <PanelField label="单张大小限制(MB)">
          <InputNumber
            min={1}
            style={{ width: '100%' }}
            value={props.maxSizeMB}
            onChange={(value) => updateProps({ maxSizeMB: value })}
          />
        </PanelField>
        <Checkbox
          checked={!!props.watermark}
          onChange={(event) => updateProps({ watermark: event.target.checked })}
        >
          上传时添加水印
        </Checkbox>
        <PanelField label="水印文字">
          <Input
            value={props.watermarkText ?? 'AntFlow'}
            disabled={!props.watermark}
            onChange={(event) => updateProps({ watermarkText: event.target.value })}
          />
        </PanelField>
        <Typography.Text type="secondary">
          水印将叠加拍摄/上传时间，由服务端处理后存储。
        </Typography.Text>
      </Space>
    );
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
};
