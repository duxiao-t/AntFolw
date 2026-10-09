import { Input, TimePicker } from 'antd';
import dayjs from 'dayjs';
import type { FieldType } from '../../registry/types';

/**
 * 时间：落库是 `"HH:mm"` 字符串（与移动端 `TimeField.tsx` 同义），不是区间。
 *
 * 不用 `dayjs(value, 'HH:mm')` 解析——那要 `customParseFormat` 插件，缺插件时会退化成 Date 解析。
 * 这里手工按 `:` 拆，`"00:00"` 也能正确回显（`Number('00') === 0`）。
 */
function toDayjs(value: unknown) {
  const text = typeof value === 'string' ? value : '';
  const [hourText, minuteText] = text.split(':');
  if (hourText === undefined || minuteText === undefined) return null;
  const hour = Number(hourText);
  const minute = Number(minuteText);
  if (!Number.isInteger(hour) || !Number.isInteger(minute)) return null;
  if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
  return dayjs().hour(hour).minute(minute).second(0).millisecond(0);
}

export const TimeField: FieldType = {
  type: 'time',
  label: '时间',
  icon: 'time',
  defaultProps: { required: false, placeholder: '请选择时间' },
  Component: ({ node, mode, value, onChange }) => {
    const text = typeof value === 'string' ? value : '';
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
        {mode === 'readonly' ? (
          <div style={{ color: 'rgba(0,0,0,0.65)' }}>{text || '未填写'}</div>
        ) : (
          <TimePicker
            format="HH:mm"
            minuteStep={1}
            allowClear
            disabled={mode !== 'runtime-fill'}
            value={toDayjs(value)}
            placeholder={node.props?.placeholder ?? '请选择时间'}
            onChange={(next) => onChange?.(next ? next.format('HH:mm') : undefined)}
          />
        )}
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>标签</div>
      <Input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })} />
      <div>占位</div>
      <Input
        value={node.props?.placeholder ?? ''}
        onChange={(e) => onChange({ ...node, props: { ...node.props, placeholder: e.target.value } })}
      />
    </div>
  ),
};
