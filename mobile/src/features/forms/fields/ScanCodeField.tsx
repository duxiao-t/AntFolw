import { useState } from 'react';
import { usePlatformAdapter } from '../../../shared/platform/PlatformProvider';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary, stringValue } from './fieldShared';

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
      <div className="af-platform-field-row">
        <input
          id={props.node.id}
          className="af-input"
          aria-label={label}
          value={stringValue(props.value)}
          placeholder={String(props.node.props?.placeholder ?? '扫描或输入二维码/条码内容')}
          onChange={(event) => props.onValueChange(props.node.id, event.currentTarget.value)}
        />
        {platform.scanCode ? (
          <button type="button" className="btn btn--secondary" disabled={scanning} onClick={() => {
            setLocalError(''); setScanning(true);
            void platform.scanCode?.().then((value) => { if (value != null) props.onValueChange(props.node.id, value); })
              .catch((error) => setLocalError(error instanceof Error ? error.message : '扫码失败'))
              .finally(() => setScanning(false));
          }}>{scanning ? '扫码中' : '扫码'}</button>
        ) : null}
      </div>
    </FieldShell>
  );
}
