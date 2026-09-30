import { CheckCircleOutlined } from '@ant-design/icons';
import { Button, Checkbox, Input, Radio, Typography } from 'antd';
import { useEffect, useRef } from 'react';
import { AuthenticatedMedia } from './MediaPreview';
import { MediaUploadControl } from './MediaUploadControl';
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
      { id: 'pass', label: '通过', color: '#22A052' },
      { id: 'fail', label: '不通过', color: '#D93025' },
      { id: 'na', label: '不适用', color: '#8F8F8F' },
    ];
  }
  return results.map((result: any, index: number) => ({
    id: typeof result?.id === 'string' ? result.id : `result-${index}`,
    label: String(result?.label ?? `结果${index + 1}`),
    color: result?.color,
  }));
}

/**
 * 把表单值规范成检查项数组。**编辑态与只读态共用一份**：以前编辑态直接拿原始数组，只认 `id`/`images`，
 * 于是老数据（移动端或更早版本写的 `itemId`/`photos`/`remark`）一存回去就被抹掉。
 * 配置里已经没有的检查项也原样留着——不静默丢用户填过的东西。
 */
function entriesOf(value: unknown, items: ReturnType<typeof itemsOf>) {
  const source = Array.isArray(value) ? value : [];
  const normalize = (raw: any, fallbackId: string, fallbackName: string) => ({
    id: String(raw?.id ?? raw?.itemId ?? fallbackId),
    name: raw?.name || fallbackName,
    status: raw?.status ?? raw?.result ?? '',
    description: raw?.description ?? raw?.remark ?? '',
    images: Array.isArray(raw?.images) ? raw.images : Array.isArray(raw?.photos) ? raw.photos : [],
  });
  const configured = items.map((item) => normalize(
    source.find((entry: any) => (entry?.id ?? entry?.itemId) === item.id),
    item.id,
    item.label,
  ));
  const known = new Set(items.map((item) => item.id));
  const extras = source
    .filter((entry: any) => entry && !known.has(String(entry.id ?? entry.itemId)))
    .map((entry: any) => normalize(entry, String(entry.id ?? entry.itemId ?? ''), '检查项'));
  return [...configured, ...extras];
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
      { id: 'pass', label: '通过', color: '#22A052' },
      { id: 'fail', label: '不通过', color: '#D93025' },
      { id: 'na', label: '不适用', color: '#8F8F8F' },
    ],
    allowDescription: true,
    descriptionRequiredByResult: { fail: true },
    oneClick: true,
    photoMaxCount: 9,
  },
  Component: ({ node, mode, value, onChange }) => {
    const items = itemsOf(node);
    const results = resultsOf(node);
    const allowDescription = node.props?.allowDescription !== false;
    const entries = entriesOf(value, items);
    // 异步上传完成时用的可能是较早那次渲染的 `value`：以 ref 为准，否则并发上传会互相覆盖
    // （先回的那张照片被后回的写没了）。
    const valueRef = useRef<unknown>(value);
    useEffect(() => { valueRef.current = value; }, [value]);
    const setEntries = (next: unknown[]) => {
      valueRef.current = next;
      onChange?.(next);
    };

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
      return (
        <div data-field-id={node.id}>
          <div style={{ display: 'block', marginBottom: 4 }}>
            {node.label}
            {node.props?.required ? ' *' : ''}
          </div>
          <div style={{ display: 'grid', gap: 10 }}>
            {entries.map((entry) => {
              const result = results.find((option: any) => option.id === entry.status);
              return (
                <div key={entry.id} style={{ display: 'grid', gap: 8, padding: 12, border: '1px solid #f0f0f0', borderRadius: 8 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                    <strong>{entry.name}</strong>
                    <span style={{ color: result?.color }}>{(result?.label ?? entry.status) || '未完成'}</span>
                  </div>
                  {String(entry.description).trim() ? (
                    <Typography.Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                      {entry.description}
                    </Typography.Paragraph>
                  ) : null}
                  {entry.images.length > 0 ? (
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
                      {entry.images.map((file: any, index: number) => (
                        <AuthenticatedMedia key={file?.id ?? index} file={file} />
                      ))}
                    </div>
                  ) : null}
                </div>
              );
            })}
            {entries.length === 0 ? <Typography.Text type="secondary">尚未配置检查项</Typography.Text> : null}
          </div>
        </div>
      );
    }

    const updateEntry = (itemId: string, patch: Record<string, any>) => {
      const current = entriesOf(valueRef.current, items);
      const hasEntry = current.some((entry: any) => entry?.id === itemId);
      const next = hasEntry
        ? current.map((entry: any) => (entry?.id === itemId ? { ...entry, ...patch } : entry))
        : [...current, { id: itemId, name: '', status: '', description: '', images: [], ...patch }];
      setEntries(next);
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
                    // 只覆盖状态：以前连 description/images 一起清空——接上真上传之后那等于静默丢附件
                    // （服务端文件还会变成孤儿）。
                    const current = entriesOf(valueRef.current, items);
                    setEntries(current.map((entry: any) => ({ ...entry, status: result.id })));
                  }}
                >
                  {result.label}
                </Button>
              ))}
            </div>
          ) : null}
          {items.map((item) => {
            const entry = entries.find((e: any) => e?.id === item.id);
            return (
              <div key={item.id} style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: 12, display: 'grid', gap: 8 }}>
                <strong>
                  {item.label}
                  {item.required ? <span style={{ color: '#ff4d4f', marginLeft: 6 }}>*</span> : null}
                </strong>
                <Radio.Group
                  value={entry?.status}
                  onChange={(event) => updateEntry(item.id, { status: event.target.value })}
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
                      placeholder="请输入描述"
                      value={entry?.description}
                      onChange={(event) => updateEntry(item.id, { description: event.target.value })}
                    />
                    <MediaUploadControl
                      node={node}
                      mode={mode}
                      value={entry?.images ?? []}
                      onChange={(files) => updateEntry(item.id, { images: files })}
                      kind="image"
                      multiple
                      maxCount={node.props?.photoMaxCount ?? 9}
                      accept="image/*"
                      buttonText="添加照片"
                    />
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
