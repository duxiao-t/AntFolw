import { useState } from 'react';
import { usePlatformAdapter } from '../../../shared/platform/PlatformProvider';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary, stringValue } from './fieldShared';
import { NativeActionContent } from './NativeActionContent';
import { ScanCodeOutline } from 'antd-mobile-icons';

export function ScanCodeField(props: MobileFieldProps) {
  const platform = usePlatformAdapter();
  const [scanning, setScanning] = useState(false);
  const [localError, setLocalError] = useState('');
  const label = fieldLabel(props.node);
  if (props.mode === 'readonly') {
    return <FieldShell node={props.node} label={label} required={isRequired(props.node)} summary={readonlySummary(props.value)} />;
  }
  return (
    <FieldShell node={props.node} label={label} required={isRequired(props.node)} error={localError || fieldError(props)}>
      <div className="af-scan-field">
        <input
          id={props.node.id}
          className="af-control"
          aria-label={label}
          value={stringValue(props.value)}
          placeholder={String(props.node.props?.placeholder ?? '扫描或输入二维码/条码内容')}
          onChange={(event) => props.onValueChange(props.node.id, event.currentTarget.value)}
        />
        {platform.scanCode ? (
          <div className="upload-control af-upload-control">
            <button type="button" className="upload-trigger upload-trigger--platform" aria-label="扫码" disabled={scanning} onClick={() => {
              setLocalError(''); setScanning(true);
              void platform.scanCode?.().then((value) => { if (value != null) props.onValueChange(props.node.id, value); })
                .catch((error) => setLocalError(error instanceof Error ? error.message : '扫码失败'))
                .finally(() => setScanning(false));
            }}>
              <NativeActionContent
                icon={<ScanCodeOutline aria-hidden="true" />}
                title={scanning ? '扫码中…' : '扫码'}
                hint="支持二维码和条码，也可在上方手动输入"
              />
            </button>
          </div>
        ) : null}
      </div>
    </FieldShell>
  );
}
