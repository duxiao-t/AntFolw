import { Space, Select, InputNumber, Checkbox, Input, Typography } from 'antd';
import { PanelField } from './panelField';
import { VideoCameraOutlined } from '@ant-design/icons';
import { MediaUploadControl } from './MediaUploadControl';
import { ReadonlyMediaList, mediaSummary } from './MediaPreview';
import type { FieldType } from '../../registry/types';

const DEFAULT_ACCEPT = 'video/mp4,video/quicktime,video/3gpp,video/webm';

export const VideoUploadField: FieldType = {
  type: 'video_upload',
  label: '视频',
  icon: 'video-camera',
  defaultProps: {
    required: false,
    multiple: false,
    maxCount: 1,
    maxDuration: 60,
    source: 'both',
    watermark: false,
    watermarkText: 'AntFlow',
    maxSizeMB: 100,
    accept: DEFAULT_ACCEPT,
  },
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <PanelField label="视频来源">
          <Select
            value={props.source ?? 'both'}
            options={[
              { label: '仅拍摄', value: 'camera' },
              { label: '仅从相册选择', value: 'album' },
              { label: '拍摄或相册', value: 'both' },
            ]}
            onChange={(source) => updateProps({ source })}
          />
        </PanelField>
        <PanelField label="最长时长(秒)">
          <InputNumber
            min={1}
            style={{ width: '100%' }}
            value={props.maxDuration ?? 60}
            onChange={(value) => updateProps({ maxDuration: value ?? 60 })}
          />
        </PanelField>
        <PanelField label="单文件大小限制(MB)">
          <InputNumber
            min={1}
            style={{ width: '100%' }}
            value={props.maxSizeMB ?? 100}
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
          超过最长时长的视频将无法选择；水印由服务端处理后存储。
        </Typography.Text>
      </Space>
    );
  },
  Component: ({ node, mode, value, onChange }) => {
    const maxDuration = node.props?.maxDuration ?? 60;
    if (mode === 'designer-preview') {
      return (
        <div data-field-id={node.id}>
          <div style={{ display: 'block', marginBottom: 4 }}>
            {node.label}
            {node.props?.required ? ' *' : ''}
          </div>
          <div className="form-fields-media-placeholder">
            <VideoCameraOutlined />
            <span>
              视频上传 · 最长 {maxDuration} 秒
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
          <div>{mediaSummary(value, 'video')}</div>
          <ReadonlyMediaList value={value} size={160} />
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
          kind="video"
          multiple={!!node.props?.multiple}
          maxCount={node.props?.maxCount ?? 1}
          maxSizeMB={node.props?.maxSizeMB}
          maxDuration={maxDuration}
          accept={node.props?.accept ?? DEFAULT_ACCEPT}
          watermark={!!node.props?.watermark}
          watermarkText={node.props?.watermarkText}
          buttonText={node.props?.buttonText}
        />
      </div>
    );
  },
};
