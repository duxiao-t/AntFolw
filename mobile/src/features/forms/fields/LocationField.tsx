import { useState } from 'react';
import { EnvironmentOutline } from 'antd-mobile-icons';
import { usePlatformAdapter } from '../../../shared/platform/PlatformProvider';
import type { PlatformLocation } from '../../../shared/platform/PlatformAdapter';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';
import { NativeActionContent } from './NativeActionContent';

export function LocationField(props: MobileFieldProps) {
  const platform = usePlatformAdapter();
  const location = asLocation(props.value);
  const label = fieldLabel(props.node);
  const [locating, setLocating] = useState(false);
  const [localError, setLocalError] = useState('');
  const summary = location ? `${location.latitude.toFixed(6)}, ${location.longitude.toFixed(6)}` : '未填写';
  if (props.mode === 'readonly') {
    return <FieldShell node={props.node} label={label} required={isRequired(props.node)} summary={<button type="button" className="af-field__summary af-location-summary" onClick={() => { if (location) void platform.openLocation?.(location); }}>{summary}</button>} />;
  }
  return (
    <FieldShell node={props.node} label={label} required={isRequired(props.node)} error={localError || fieldError(props)}>
      {location ? <div className="af-location-value"><EnvironmentOutline /><span><strong>{summary}</strong><small>{location.coordinateSystem ?? '坐标系未知'}{location.accuracy ? ` · 精度约 ${Math.round(location.accuracy)} 米` : ''}</small></span></div> : null}
      <div className="upload-control af-upload-control">
        <button type="button" className="upload-trigger upload-trigger--platform" disabled={locating} onClick={() => {
          setLocalError(''); setLocating(true);
          void currentLocation(platform.getLocation).then((value) => props.onValueChange(props.node.id, value))
            .catch((error) => setLocalError(error instanceof Error ? error.message : '定位失败'))
            .finally(() => setLocating(false));
        }}>
          <NativeActionContent
            icon={<EnvironmentOutline aria-hidden="true" />}
            title={locating ? '定位中…' : location ? '重新定位' : '获取当前位置'}
            hint={location ? '更新当前经纬度和定位精度' : '获取当前经纬度并保存到表单'}
          />
        </button>
      </div>
      {location ? <div className="af-location-actions">
        <button type="button" className="af-link-button" onClick={() => void platform.openLocation?.(location)}>在地图中打开</button>
        <button type="button" className="af-link-button" onClick={() => props.onValueChange(props.node.id, undefined)}>清除位置</button>
      </div> : null}
    </FieldShell>
  );
}

function currentLocation(platformGetter?: () => Promise<PlatformLocation>): Promise<PlatformLocation> {
  if (platformGetter) return platformGetter();
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) { reject(new Error('当前浏览器不支持定位')); return; }
    navigator.geolocation.getCurrentPosition((position) => resolve({
      latitude: position.coords.latitude, longitude: position.coords.longitude,
      accuracy: position.coords.accuracy,
    }), () => reject(new Error('无法获取位置，请检查定位权限')), { enableHighAccuracy: true, timeout: 15_000 });
  });
}

function asLocation(value: unknown): PlatformLocation | null {
  if (typeof value !== 'object' || value == null) return null;
  const item = value as Partial<PlatformLocation>;
  return typeof item.latitude === 'number' && typeof item.longitude === 'number'
    ? item as PlatformLocation : null;
}
