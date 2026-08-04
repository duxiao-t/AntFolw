import { CheckCircleOutlined } from '@ant-design/icons';
import { Button, Checkbox, Input, Radio, Upload } from 'antd';
import type { FieldType } from '../../registry/types';

function itemsOf(node: any) {
  const items = node.props?.items;
  if (!Array.isArray(items) || items.length === 0) return [];
  return items.map((item: any, index: number) => ({
    id: typeof item?.id === 'string' ? item.id : `item-${index}`,
    label: String(item?.label ?? `检查项${index + 1}`),
    required: item?.required === true,
  }));
}

function resultsOf(node: any) {
  const results = node.props?.results;
  if (!Array.isArray(results) || results.length === 0) {
    return [
      { id: 'normal', label: '正常' },
      { id: 'abnormal', label: '异常' },
      { id: 'na', label: '不适用' },
    ];
  }
  return results.map((result: any, index: number) => ({
    id: typeof result?.id === 'string' ? result.id : `result-${index}`,
    label: String(result?.label ?? `结果${index + 1}`),
  }));
}

export const ChecklistField: FieldType = {
  type: 'checklist',
  label: '检查项',
  icon: 'checklist',
  defaultProps: {
    required: false,
    items: [
      { id: 'item-1', label: '检查项1', required: true },
      { id: 'item-2', label: '检查项2', required: true },
    ],
    results: [
      { id: 'normal', label: '正常' },
      { id: 'abnormal', label: '异常' },
      { id: 'na', label: '不适用' },
    ],
    allowDescription: true,
    descriptionRequiredByResult: { abnormal: true },
    oneClick: true,
    photoMaxCount: 9,
  },
  Component: ({ node, mode, value, onChange }) => {
    const items = itemsOf(node);
    const results = resultsOf(node);
    const allowDescription = node.props?.allowDescription !== false;
    const entries = Array.isArray(value) ? value : [];

    if (mode === 'designer-preview') {
      return (
        <div data-field-id={node.id}>
          <div style={{ display: 'block', marginBottom: 4 }}>
            {node.label}
            {node.props?.required ? ' *' : ''}
          </div>
          <div className="form-fields-media-placeholder">
            <CheckCircleOutlined />
            <span>
              检查项 · 共 {items.length} 项 / {results.length} 个结果
            </span>
          </div>
        </div>
      );
    }

    if (mode === 'readonly') {
      const done = entries.filter((entry: any) => entry?.result).length;
      return (
        <div data-field-id={node.id}>
          <div style={{ display: 'block', marginBottom: 4 }}>
            {node.label}
            {node.props?.required ? ' *' : ''}
          </div>
          <div>
            {done > 0 ? `已完成 ${done}/${items.length} 项` : '未完成'}
          </div>
        </div>
      );
    }

    const updateEntry = (itemId: string, patch: Record<string, any>) => {
      const next = entries.map((entry: any) =>
        entry?.itemId === itemId ? { ...entry, ...patch } : entry,
      );
      const hasEntry = entries.some((entry: any) => entry?.itemId === itemId);
      const final = hasEntry
        ? next
        : [...next, { itemId, result: '', remark: '', photos: [], ...patch }];
      onChange?.(final);
    };

    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}
          {node.props?.required ? ' *' : ''}
        </div>
        <div style={{ display: 'grid', gap: 12 }}>
          {node.props?.oneClick !== false && results.length > 0 ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <span style={{ color: 'rgba(0,0,0,0.45)' }}>全部设为：</span>
              {results.map((result) => (
                <Button
                  key={result.id}
                  size="small"
                  onClick={() => {
                    const next = items.map((item) => ({
                      itemId: item.id,
                      result: result.id,
                      remark: '',
                      photos: [],
                    }));
                    onChange?.(next);
                  }}
                >
                  {result.label}
                </Button>
              ))}
            </div>
          ) : null}
          {items.map((item) => {
            const entry = entries.find((e: any) => e?.itemId === item.id);
            return (
              <div key={item.id} style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: 12, display: 'grid', gap: 8 }}>
                <strong>
                  {item.label}
                  {item.required ? <span style={{ color: '#ff4d4f', marginLeft: 6 }}>*</span> : null}
                </strong>
                <Radio.Group
                  value={entry?.result}
                  onChange={(event) => updateEntry(item.id, { result: event.target.value })}
                >
                  {results.map((result) => (
                    <Radio key={result.id} value={result.id}>
                      {result.label}
                    </Radio>
                  ))}
                </Radio.Group>
                {allowDescription ? (
                  <>
                    <Input.TextArea
                      rows={2}
                      placeholder="填写描述（异常原因、现场情况等）"
                      value={entry?.remark}
                      onChange={(event) => updateEntry(item.id, { remark: event.target.value })}
                    />
                    <Upload
                      multiple
                      accept="image/*"
                      listType="picture"
                      fileList={(entry?.photos ?? []).map((photo: any, index: number) => ({
                        uid: photo.id ?? String(index),
                        name: photo.name ?? '照片',
                        status: 'done',
                        url: photo.contentUrl ?? photo.url,
                      }))}
                      beforeUpload={() => false}
                    >
                      <Button size="small">添加照片</Button>
                    </Upload>
                  </>
                ) : null}
              </div>
            );
          })}
          {items.length === 0 ? <div style={{ color: 'rgba(0,0,0,0.45)' }}>尚未配置检查项</div> : null}
        </div>
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>标签</div>
      <input
        value={node.label ?? ''}
        onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }}
      />
      <Checkbox
        checked={node.props?.allowDescription !== false}
        onChange={(e) => onChange({ ...node, props: { ...node.props, allowDescription: e.target.checked } })}
      >
        允许填写图文描述
      </Checkbox>
    </div>
  ),
};
