import type { MobileFieldProps } from '../schema/types';
import { FileUploadField } from './FileUploadField';

const DEFAULT_IMAGE_ACCEPT = 'image/jpeg,image/png';

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
        },
      }}
    />
  );
}
