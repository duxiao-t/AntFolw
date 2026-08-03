import type { MobileFieldProps } from '../schema/types';
import { FileUploadField } from './FileUploadField';

const DEFAULT_VIDEO_ACCEPT = 'video/mp4,video/quicktime,video/3gpp,video/webm';

export function VideoUploadField(props: MobileFieldProps) {
  return (
    <FileUploadField
      {...props}
      node={{
        ...props.node,
        props: {
          ...props.node.props,
          accept: props.node.props?.accept ?? DEFAULT_VIDEO_ACCEPT,
          preview: false,
          multiple: false,
          maxCount: props.node.props?.maxCount ?? 1,
          maxDuration: props.node.props?.maxDuration ?? 60,
          source: props.node.props?.source ?? 'both',
          addLabel: '添加视频',
          unitLabel: '个视频',
        },
      }}
    />
  );
}
