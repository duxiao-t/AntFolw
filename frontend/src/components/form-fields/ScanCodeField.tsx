import { ScanOutlined } from '@ant-design/icons';
import { App, Button, Input, Space } from 'antd';
import { useState } from 'react';
import type { FieldComponentProps, FieldType } from '../../registry/types';
import { scanCodeWithCamera } from './nativeMedia';

function ScanCodeInput({ node, mode, value, onChange }: FieldComponentProps) {
  const { message } = App.useApp();
  const [scanning, setScanning] = useState(false);
  const editable = mode === 'runtime-fill';
  return <div data-field-id={node.id}>
    <div style={{ marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
    <Space.Compact style={{ width: '100%' }}>
      <Input value={value ?? ''} disabled={!editable} placeholder={node.props?.placeholder}
        onChange={(event) => onChange?.(event.target.value)} />
      {editable ? <Button icon={<ScanOutlined />} loading={scanning} onClick={() => {
        setScanning(true);
        void scanCodeWithCamera().then((result) => { if (result != null) onChange?.(result); })
          .catch((error) => message.error(error instanceof Error ? error.message : '扫码失败'))
          .finally(() => setScanning(false));
      }}>扫码</Button> : null}
    </Space.Compact>
  </div>;
}

export const ScanCodeField: FieldType = {
  type: 'scan_code', label: '扫码', icon: 'scan',
  defaultProps: { required: false, placeholder: '扫描或输入二维码/条码内容' },
  Component: ScanCodeInput,
  ConfigPanel: () => null,
};
