import { CheckCircleOutlined } from '@ant-design/icons';
import { request } from '@umijs/max';
import { Button, Checkbox, Image, Input, Radio, Typography, Upload } from 'antd';
import { useEffect, useState } from 'react';
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

function entriesOf(value: unknown, items: ReturnType<typeof itemsOf>) {
  const source = Array.isArray(value) ? value : [];
  return items.map((item) => {
    const raw = source.find((entry: any) => (entry?.id ?? entry?.itemId) === item.id) as any;
    return {
      id: item.id,
      name: raw?.name || item.label,
      status: raw?.status ?? raw?.result ?? '',
      description: raw?.description ?? raw?.remark ?? '',
      images: Array.isArray(raw?.images) ? raw.images : Array.isArray(raw?.photos) ? raw.photos : [],
    };
  });
}

function AuthenticatedChecklistMedia({ file }: { file: any }) {
  const contentUrl = file?.contentUrl ?? file?.url;
  const [url, setUrl] = useState('');
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!contentUrl) return;
    let alive = true;
    let objectUrl = '';
    request<Blob>(contentUrl, { responseType: 'blob' }).then((blob) => {
      if (!alive) return;
      objectUrl = URL.createObjectURL(blob);
      setUrl(objectUrl);
    }).catch(() => { if (alive) setFailed(true); });
    return () => {
      alive = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [contentUrl]);
  const name = file?.name ?? file?.fileName ?? '媒体';
  const video = String(file?.contentType ?? '').startsWith('video/')
    || /\.(mp4|mov|3gp|3gpp|webm|m4v)$/i.test(name);
  return (
    <div style={{ display: 'grid', gap: 4 }}>
      {url && video ? (
        // biome-ignore lint/a11y/useMediaCaption: uploaded inspection videos do not provide caption tracks.
        <video controls preload="metadata" src={url} style={{ width: 220, maxWidth: '100%', borderRadius: 8 }} />
      ) : url ? <Image width={96} height={96} src={url} alt={name} style={{ objectFit: 'cover', borderRadius: 8 }} />
        : <Typography.Text type="secondary">{failed ? '媒体加载失败' : '媒体加载中…'}</Typography.Text>}
      <Typography.Text type="secondary">{name}</Typography.Text>
    </div>
  );
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
    const entries = mode === 'readonly'
      ? entriesOf(value, items)
      : Array.isArray(value) ? value : [];

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
                        <AuthenticatedChecklistMedia key={file?.id ?? index} file={file} />
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
      const next = entries.map((entry: any) =>
        entry?.id === itemId ? { ...entry, ...patch } : entry,
      );
      const hasEntry = entries.some((entry: any) => entry?.id === itemId);
      const final = hasEntry
        ? next
        : [...next, { id: itemId, name: '', status: '', description: '', images: [], ...patch }];
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
                      id: item.id,
                      name: item.label,
                      status: result.id,
                      description: '',
                      images: [],
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
                    <Upload
                      multiple
                      accept="image/*"
                      listType="picture"
                      fileList={(entry?.images ?? []).map((photo: any, index: number) => ({
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
