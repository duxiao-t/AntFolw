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
          accept={node.props?.accept ?? DEFAULT_ACCEPT}
          watermark={!!node.props?.watermark}
          watermarkText={node.props?.watermarkText}
          buttonText={node.props?.buttonText}
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
