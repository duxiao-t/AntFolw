import { DeleteOutlined, EnvironmentOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Input, Space, Typography } from 'antd';
import { useState } from 'react';
import type { FieldComponentProps, FieldType } from '../../registry/types';
import { getBrowserLocation, type NativeLocation, openNativeLocation } from './nativeMedia';

function asLocation(value: unknown): NativeLocation | null {
  if (typeof value !== 'object' || value == null) return null;
  const location = value as Partial<NativeLocation>;
  return typeof location.latitude === 'number' && typeof location.longitude === 'number'
    ? location as NativeLocation : null;
}

function LocationInput({ node, mode, value, onChange }: FieldComponentProps) {
  const { message } = App.useApp();
  const [locating, setLocating] = useState(false);
  const location = asLocation(value);
  const display = location ? `${location.latitude.toFixed(6)}, ${location.longitude.toFixed(6)}` : '';
  if (mode === 'designer-preview') return <div data-field-id={node.id}>
    <div style={{ marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
    <div className="form-fields-media-placeholder">
      <EnvironmentOutlined />
      <span>浏览器/企业微信原生定位</span>
    </div>
  </div>;
  return <div data-field-id={node.id}>
    <div style={{ marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
    <Input value={display} readOnly placeholder="尚未获取位置" />
    {location ? <Typography.Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
      {location.coordinateSystem ?? '坐标系未知'}{location.accuracy ? ` · 精度约 ${Math.round(location.accuracy)} 米` : ''}
    </Typography.Text> : null}
    <Space style={{ marginTop: 8 }} wrap>
      {mode === 'runtime-fill' ? <Button icon={location ? <ReloadOutlined /> : <EnvironmentOutlined />} loading={locating} onClick={() => {
        setLocating(true);
        void getBrowserLocation().then((result) => onChange?.(result))
          .catch((error) => message.error(error instanceof Error ? error.message : '定位失败'))
          .finally(() => setLocating(false));
      }}>{location ? '重新定位' : '获取当前位置'}</Button> : null}
      {location ? <Button onClick={() => openNativeLocation(location)}>在地图中打开</Button> : null}
      {location && mode === 'runtime-fill' ? <Button danger type="text" icon={<DeleteOutlined />} onClick={() => onChange?.(undefined)}>清除</Button> : null}
    </Space>
  </div>;
}

export const LocationField: FieldType = {
  type: 'location', label: '定位', icon: 'environment', defaultProps: { required: false },
  Component: LocationInput,
  ConfigPanel: () => null,
};
