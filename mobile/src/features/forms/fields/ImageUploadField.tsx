import type { MobileFieldProps } from '../schema/types';
import { FileUploadField } from './FileUploadField';

export function ImageUploadField(props: MobileFieldProps) {
  return (
    <FileUploadField
      {...props}
      node={{
        ...props.node,
        props: {
          ...props.node.props,
          accept: props.node.props?.accept ?? 'image/*',
          preview: props.node.props?.preview ?? true,
        },
      }}
    />
  );
}
