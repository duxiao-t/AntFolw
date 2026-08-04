import { ImageUploader, type ImageUploadItem } from 'antd-mobile';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MobileFileDto } from '../files.api';
import { fetchMobileFileBlob, uploadMobileFile } from '../files.api';
import type { MobileFieldProps, MobileSchemaNode } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';

export type ChecklistResultOption = { id: string; label: string };
export type ChecklistItemDef = { id: string; label: string; required: boolean };
export type ChecklistEntry = {
  itemId: string;
  result: string;
  remark: string;
  photos: MobileFileDto[];
};

export type ChecklistValue = ChecklistEntry[];

const DEFAULT_RESULTS: ChecklistResultOption[] = [
  { id: 'normal', label: '正常' },
  { id: 'abnormal', label: '异常' },
  { id: 'na', label: '不适用' },
];

export function checklistItems(node: MobileSchemaNode): ChecklistItemDef[] {
  const items = node.props?.items;
  if (!Array.isArray(items)) return [];
  return items
    .map((item, index) => {
      if (typeof item !== 'object' || item == null) return null;
      const raw = item as Record<string, unknown>;
      const label = String(raw.label ?? raw.content ?? `检查项${index + 1}`);
      const id = typeof raw.id === 'string' && raw.id ? raw.id : `item-${index}`;
      return { id, label, required: raw.required === true };
    })
    .filter((item): item is ChecklistItemDef => Boolean(item));
}

export function checklistResults(node: MobileSchemaNode): ChecklistResultOption[] {
  const results = node.props?.results;
  if (!Array.isArray(results) || results.length === 0) {
    return DEFAULT_RESULTS;
  }
  const parsed = results
    .map((result, index) => {
      if (typeof result !== 'object' || result == null) return null;
      const raw = result as Record<string, unknown>;
      const label = String(raw.label ?? `结果${index + 1}`);
      const id = typeof raw.id === 'string' && raw.id ? raw.id : `result-${index}`;
      return { id, label };
    })
    .filter((result): result is ChecklistResultOption => Boolean(result));
  return parsed.length >= 2 ? parsed : DEFAULT_RESULTS;
}


export function ChecklistField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const items = useMemo(() => checklistItems(props.node), [props.node]);
  const results = useMemo(() => checklistResults(props.node), [props.node]);
  const allowDescription = props.node.props?.allowDescription !== false;
  const oneClick = props.node.props?.oneClick !== false;
  const photoMaxCount =
    typeof props.node.props?.photoMaxCount === 'number'
      ? props.node.props.photoMaxCount
      : 9;

  const [entries, setEntries] = useState<ChecklistValue>(() => entriesFromValue(props.value, items));

  useEffect(() => {
    setEntries(entriesFromValue(props.value, items));
  }, [props.value, items]);

  const updateItem = useCallback(
    (itemId: string, patch: Partial<Omit<ChecklistEntry, 'itemId'>>) => {
      setEntries((current) => {
        const next = current.map((entry) =>
          entry.itemId === itemId ? { ...entry, ...patch } : entry,
        );
        props.onValueChange(props.node.id, next);
        return next;
      });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [props.node.id, props.onValueChange],
  );

  const setAllResults = useCallback(
    (resultId: string) => {
      const next = entries.map((entry) => ({ ...entry, result: resultId }));
      setEntries(next);
      props.onValueChange(props.node.id, next);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [entries, props.node.id, props.onValueChange],
  );

  const error = fieldError(props);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={error}
      summary={
        props.mode === 'readonly' ? checklistSummary(props.node, props.value) : undefined
      }
    >
      {props.mode === 'readonly'
        ? null
        : (
          <div className="af-checklist">
            {oneClick && results.length > 0 ? (
              <div className="af-checklist__quick">
                <span>全部设为：</span>
                {results.map((result) => (
                  <button
                    key={result.id}
                    type="button"
                    className="af-checklist__quick-btn"
                    onClick={() => setAllResults(result.id)}
                  >
                    {result.label}
                  </button>
                ))}
              </div>
            ) : null}
            {items.length === 0 ? (
              <div className="af-checklist__empty">尚未配置检查项</div>
            ) : null}
            {items.map((item) => {
              const entry = entries.find((e) => e.itemId === item.id) ?? {
                itemId: item.id,
                result: '',
                remark: '',
                photos: [],
              };
              return (
                <div key={item.id} className="af-checklist__item">
                  <div className="af-checklist__item-head">
                    <strong>{item.label}</strong>
                    {item.required ? <span className="af-checklist__required">*</span> : null}
                  </div>
                  <div className="af-checklist__results" role="radiogroup" aria-label={`${item.label}结果`}>
                    {results.map((result) => (
                      <label key={result.id} className="af-checklist__result">
                        <input
                          type="radio"
                          name={`check-${item.id}`}
                          value={result.id}
                          checked={entry.result === result.id}
                          onChange={() => updateItem(item.id, { result: result.id })}
                        />
                        <span>{result.label}</span>
                      </label>
                    ))}
                  </div>
                  {allowDescription ? (
                    <div className="af-checklist__desc">
                      <textarea
                        className="af-control af-checklist__remark"
                        rows={2}
                        placeholder="填写描述（异常原因、现场情况等）"
                        value={entry.remark}
                        onChange={(event) => updateItem(item.id, { remark: event.target.value })}
                      />
                      <ChecklistPhotos
                        photos={entry.photos}
                        maxCount={photoMaxCount}
                        onChange={(photos) => updateItem(item.id, { photos })}
                      />
                    </div>
                  ) : null}
                </div>
              );
            })}
          </div>
        )}
    </FieldShell>
  );
}

function ChecklistPhotos({
  photos,
  maxCount,
  onChange,
}: {
  photos: MobileFileDto[];
  maxCount: number;
  onChange(photos: MobileFileDto[]): void;
}) {
  const [items, setItems] = useState<ImageUploadItem[]>([]);
  const dtoByKey = useRef<Map<string, MobileFileDto>>(new Map());
  const objectUrls = useRef<Map<string, string>>(new Map());
  const photosRef = useRef<MobileFileDto[]>(photos);
  photosRef.current = photos;

  useEffect(() => {
    let cancelled = false;
    const next: ImageUploadItem[] = [];
    const nextDto = new Map<string, MobileFileDto>();
    for (const photo of photos) {
      const key = photo.id;
      nextDto.set(key, photo);
      next.push({ url: '', key });
    }
    dtoByKey.current = nextDto;
    setItems(next);
    // load previews for any photo without one
    photos.forEach((photo) => {
      if (objectUrls.current.has(photo.id)) return;
      void fetchMobileFileBlob(photo.contentUrl || photo.url || '')
        .then((blob) => {
          if (cancelled) return;
          const objectUrl = URL.createObjectURL(blob);
          objectUrls.current.set(photo.id, objectUrl);
          setItems((current) =>
            current.map((item) => (item.key === photo.id ? { ...item, url: objectUrl } : item)),
          );
        })
        .catch(() => {
          /* preview unavailable */
        });
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [photos]);

  const handleUpload = async (file: File) => {
    const dto = await uploadMobileFile('/api/mobile/files', file);
    let url = dto.contentUrl || dto.url || '';
    try {
      const blob = await fetchMobileFileBlob(url);
      const objectUrl = URL.createObjectURL(blob);
      objectUrls.current.set(dto.id, objectUrl);
      url = objectUrl;
    } catch {
      /* keep content url */
    }
    dtoByKey.current.set(dto.id, dto);
    photosRef.current = [...photosRef.current, dto];
    onChange(photosRef.current);
    return { url, key: dto.id };
  };

  const handleRemove = (item: ImageUploadItem) => {
    const key = item.key ?? '';
    const url = objectUrls.current.get(key);
    if (url) {
      URL.revokeObjectURL(url);
      objectUrls.current.delete(key);
    }
    dtoByKey.current.delete(key);
    photosRef.current = photosRef.current.filter((photo) => photo.id !== key);
    onChange(photosRef.current);
  };

  return (
    <ImageUploader
      value={items}
      onChange={setItems}
      upload={handleUpload}
      onDelete={handleRemove}
      maxCount={maxCount}
      accept="image/*"
      multiple
    />
  );
}

export function entriesFromValue(value: unknown, items: ChecklistItemDef[]): ChecklistValue {
  if (!Array.isArray(value)) {
    return items.map((item) => ({ itemId: item.id, result: '', remark: '', photos: [] }));
  }
  const byItem = new Map<string, ChecklistEntry>();
  for (const entry of value) {
    if (typeof entry !== 'object' || entry == null) continue;
    const raw = entry as Record<string, unknown>;
    if (typeof raw.itemId !== 'string') continue;
    byItem.set(raw.itemId, {
      itemId: raw.itemId,
      result: typeof raw.result === 'string' ? raw.result : '',
      remark: typeof raw.remark === 'string' ? raw.remark : '',
      photos: Array.isArray(raw.photos) ? raw.photos : [],
    });
  }
  return items.map((item) => byItem.get(item.id) ?? { itemId: item.id, result: '', remark: '', photos: [] });
}

export function checklistSummary(node: MobileSchemaNode, value: unknown): string {
  const items = checklistItems(node);
  const entries = entriesFromValue(value, items);
  const counts = new Map<string, number>();
  const results = checklistResults(node);
  for (const entry of entries) {
    if (!entry.result) continue;
    counts.set(entry.result, (counts.get(entry.result) ?? 0) + 1);
  }
  if (counts.size === 0) {
    return '未完成';
  }
  const parts = results
    .filter((result) => counts.has(result.id))
    .map((result) => `${counts.get(result.id)} 项${result.label}`);
  return parts.length > 0 ? parts.join('、') : '未完成';
}
