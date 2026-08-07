import {
  Form,
  Input,
  InputNumber,
  Segmented,
  Select,
  Space,
  TimePicker,
} from 'antd';
import dayjs from 'dayjs';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function DelayNodeConfig({ node }: { node: TreeNode }) {
  const updateProps = useProcessDesignerStore((state) => state.updateProps);
  const updateName = useProcessDesignerStore((state) => state.updateName);
  const props = node.props ?? {};
  const set = (patch: Record<string, any>) =>
    updateProps(node.id, { ...props, ...patch });
  const timeValue = /^\d{2}:\d{2}$/.test(props.time ?? '')
    ? dayjs(`2000-01-01T${props.time}:00`)
    : null;

  return (
    <Form layout="vertical" className="pt-config-form">
      <Form.Item label="节点名称">
        <Input
          value={node.name ?? ''}
          onChange={(event) => updateName(node.id, event.target.value)}
        />
      </Form.Item>
      <Form.Item label="等待方式">
        <Segmented
          block
          value={props.mode ?? 'DURATION'}
          onChange={(value) => set({ mode: value })}
          options={[
            { value: 'DURATION', label: '固定时长' },
            { value: 'UNTIL_TIME', label: '当天时刻' },
          ]}
        />
      </Form.Item>
      {props.mode === 'UNTIL_TIME' ? (
        <Form.Item
          label="继续执行时刻"
          extra="若当天该时刻已过，流程立即继续。"
        >
          <TimePicker
            value={timeValue}
            format="HH:mm"
            minuteStep={5}
            style={{ width: '100%' }}
            onChange={(_, value) =>
              set({ time: Array.isArray(value) ? value[0] : value })
            }
          />
        </Form.Item>
      ) : (
        <Form.Item label="等待时长" extra="必须大于 0，最长 365 天。">
          <Space.Compact block>
            <InputNumber
              min={1}
              max={
                props.unit === 'DAYS'
                  ? 365
                  : props.unit === 'HOURS'
                    ? 8760
                    : 525600
              }
              precision={0}
              value={props.amount ?? 1}
              style={{ width: '60%' }}
              onChange={(value) => set({ amount: value ?? 1 })}
            />
            <Select
              value={props.unit ?? 'HOURS'}
              style={{ width: '40%' }}
              onChange={(value) => set({ unit: value })}
              options={[
                { value: 'MINUTES', label: '分钟' },
                { value: 'HOURS', label: '小时' },
                { value: 'DAYS', label: '天' },
              ]}
            />
          </Space.Compact>
        </Form.Item>
      )}
    </Form>
  );
}
