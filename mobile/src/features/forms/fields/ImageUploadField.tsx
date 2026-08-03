import type { MobileFieldProps } from '../schema/types';
import { FileUploadField } from './FileUploadField';

const DEFAULT_IMAGE_ACCEPT = 'image/*';

export function ImageUploadField(props: MobileFieldProps) {
  return (
    <FileUploadField
      {...props}
      node={{
        ...props.node,
        props: {
          ...props.node.props,
          accept: props.node.props?.accept ?? DEFAULT_IMAGE_ACCEPT,
          preview: props.node.props?.preview ?? true,
          multiple: true,
          maxCount: props.node.props?.maxCount ?? 20,
          source: props.node.props?.source ?? 'both',
          addLabel: '添加图片',
          unitLabel: '张图片',
          convertHeic: true,
        },
      }}
    />
  );
}
