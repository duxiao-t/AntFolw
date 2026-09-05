import { ImageViewer, TextArea, Toast } from 'antd-mobile';
import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { isHexColor } from '../../branding/brandTokens';
import { fetchMobileFileBlob, uploadMobileFile } from '../files.api';
import type { MobileFileDto } from '../files.api';
import type { MobileFieldProps, MobileSchemaNode } from '../schema/types';
import { fieldDescription } from '../schema/validators';
import { fieldError, fieldLabel, isRequired } from './fieldShared';

export type ChecklistResultOption = { id: string; label: string; color: string };
export type ChecklistItemDef = { id: string; label: string; required: boolean };

export type ChecklistEntry = {
  id: string;
  name: string;
  status: string | null;
  description: string;
  images: MobileFileDto[];
};

export type ChecklistValue = ChecklistEntry[];

const DEFAULT_RESULT_COLORS = ['#22A052', '#D93025', '#8F8F8F', '#5A6FA8'] as const;

const DEFAULT_RESULTS: ChecklistResultOption[] = [
  { id: 'pass', label: '通过', color: DEFAULT_RESULT_COLORS[0] },
  { id: 'fail', label: '不通过', color: DEFAULT_RESULT_COLORS[1] },
  { id: 'na', label: '不适用', color: DEFAULT_RESULT_COLORS[2] },
];

const iconProps = {
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
} as const;

function IconCamera() {
  return (
    <svg {...iconProps} role="img" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <title>拍照</title>
      <path d="M3.5 8.2a1.7 1.7 0 0 1 1.7-1.7h2.2l1.6-2.2a1 1 0 0 1 .8-.4h4.4a1 1 0 0 1 .8.4l1.6 2.2h2.2a1.7 1.7 0 0 1 1.7 1.7v9.1a1.7 1.7 0 0 1-1.7 1.7H5.2a1.7 1.7 0 0 1-1.7-1.7z" />
      <circle cx="12" cy="12.6" r="3.4" />
    </svg>
  );
}
function IconImage() {
  return (
    <svg {...iconProps} role="img" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <title>相册上传</title>
      <rect x="3.5" y="5" width="17" height="14" rx="2" />
      <circle cx="9" cy="10" r="1.6" />
      <path d="M20.5 15.5l-4.8-4.8-8.2 8.2" />
    </svg>
  );
}

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
      const color = typeof raw.color === 'string' && isHexColor(raw.color)
        ? raw.color
        : (DEFAULT_RESULT_COLORS[index] ?? DEFAULT_RESULT_COLORS[3]);
      return { id, label, color };
    })
    .filter((result): result is ChecklistResultOption => Boolean(result));
  return parsed.length >= 2 ? parsed : DEFAULT_RESULTS;
}

export function ChecklistField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const items = useMemo(() => checklistItems(props.node), [props.node]);
  const results = useMemo(() => checklistResults(props.node), [props.node]);
  const oneClick = props.node.props?.oneClick !== false;
  const [expandedIds, setExpandedIds] = useState<ReadonlySet<string>>(new Set());
  const [entries, setEntries] = useState<ChecklistValue>(() => entriesFromValue(props.value, items));
  const allChecked =
    entries.length > 0 &&
    entries.every((entry) => entry.status === (results[0]?.id ?? ''));

  const expandItem = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      next.add(id);
      // 展开新项时，只自动收回"没有内容"的空展开项；有内容的一直保持展开
      for (const entry of entries) {
        if (entry.id !== id && next.has(entry.id) && !entryHasContent(entry)) {
          next.delete(entry.id);
        }
      }
      return next;
    });
  };

  const collapseItem = (id: string) => {
    setExpandedIds((prev) => {
      if (!prev.has(id)) return prev;
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
  };

  useEffect(() => {
    setEntries(entriesFromValue(props.value, items));
  }, [props.value, items]);

  const updateEntry = (id: string, patch: Partial<Omit<ChecklistEntry, 'id' | 'name'>>) => {
    const next = entries.map((entry) =>
      entry.id === id ? { ...entry, ...patch } : entry,
    );
    setEntries(next);
    props.onValueChange(props.node.id, next);
  };

  const setStatus = (id: string, status: string | null) => {
    const next = entries.map((entry) =>
      entry.id === id
        ? { ...entry, status, description: status ? entry.description : '' }
        : entry,
    );
    setEntries(next);
    props.onValueChange(props.node.id, next);
  };

  const setAll = (resultId: string) => {
    const next = entries.map((entry) => ({ ...entry, status: resultId }));
    setEntries(next);
    props.onValueChange(props.node.id, next);
  };

  const error = fieldError(props);
  const required = isRequired(props.node);
  const description = fieldDescription(props.node);

  if (props.mode === 'readonly') {
    return (
      <div className="af-checklist">
        <div className="af-checklist__header">
          <strong className="af-checklist__title">{label}</strong>
        </div>
        {description ? <p className="af-checklist__description">{description}</p> : null}
        <div className="af-checklist__list">{checklistSummary(props.node, props.value)}</div>
      </div>
    );
  }

  return (
    <div className="af-checklist">
      <div className="af-checklist__header">
        <strong className="af-checklist__title">
          {label}
          {required ? <span className="af-checklist__required">*</span> : null}
        </strong>
        {oneClick && results.length > 0 ? (
          <button
            type="button"
            className="af-checklist__check-all"
            onClick={() => {
              if (allChecked) {
                const next = entries.map((entry) => ({ ...entry, status: null }));
                setEntries(next);
                props.onValueChange(props.node.id, next);
              } else {
                const first = results[0];
                if (first) setAll(first.id);
              }
            }}
          >
            {allChecked ? '一键取消' : '一键勾选'}
          </button>
        ) : null}
      </div>
      {description ? <p className="af-checklist__description">{description}</p> : null}
      {error ? <div className="af-checklist__error">{error}</div> : null}
      <div className="af-checklist__list">
        {items.length === 0 ? (
          <div className="af-checklist__empty">尚未配置检查项</div>
        ) : null}
        {items.map((item) => {
          const entry = entries.find((e) => e.id === item.id) ?? {
            id: item.id,
            name: item.label,
            status: null,
            description: '',
            images: [],
          };
          const selectedResult = results.find((result) => result.id === entry.status);
          const expanded = expandedIds.has(item.id) && selectedResult != null;
          return (
            <CheckCard
              key={item.id}
              item={item}
              entry={entry}
              results={results}
              selectedResult={selectedResult}
              expanded={expanded}
              allowDescription={props.node.props?.allowDescription !== false}
              photoMaxCount={
                typeof props.node.props?.photoMaxCount === 'number'
                  ? props.node.props.photoMaxCount
                  : 9
              }
              onStatus={(status) => setStatus(item.id, status)}
              onExpand={(next) => {
                if (next) expandItem(item.id);
                else collapseItem(item.id);
              }}
              onEntry={(patch) => updateEntry(item.id, patch)}
            />
          );
        })}
      </div>
    </div>
  );
}

function CheckCard({
  item,
  entry,
  results,
  selectedResult,
  expanded,
  allowDescription,
  photoMaxCount,
  onStatus,
  onExpand,
  onEntry,
}: {
  item: ChecklistItemDef;
  entry: ChecklistEntry;
  results: ChecklistResultOption[];
  selectedResult?: ChecklistResultOption;
  expanded: boolean;
  allowDescription: boolean;
  photoMaxCount: number;
  onStatus(status: string | null): void;
  onExpand(expanded: boolean): void;
  onEntry(patch: Partial<Omit<ChecklistEntry, 'id' | 'name'>>): void;
}) {
  if (!selectedResult) {
    return (
      <div className="af-check__card af-check__card--none">
        <div className="af-check__head">
          <span className="af-check__name">{item.label}</span>
          <span className="af-check__actions">
            {results.map((result, index) => (
              <span key={result.id} className="af-check__actions-inner">
                {index > 0 ? <span className="af-check__divider" aria-hidden="true" /> : null}
                <button
                  type="button"
                  className="af-check__result-btn"
                  aria-label={result.label}
                  style={{ '--af-check-color': result.color } as CSSProperties}
                  onClick={() => onStatus(result.id)}
                >
                  {result.label}
                </button>
              </span>
            ))}
          </span>
        </div>
      </div>
    );
  }

  const statusLabel = selectedResult.label;
  return (
    <div
      className="af-check__card af-check__card--selected"
      style={{ '--af-check-color': selectedResult.color } as CSSProperties}
    >
      <div className="af-check__head">
        <button
          type="button"
          className="af-check__name"
          onClick={() => onExpand(false)}
        >
          {item.label}
        </button>
        <span className="af-check__status-area">
          <button
            type="button"
            className="af-check__status-button"
            aria-label={`${statusLabel}，点击取消选择`}
            onClick={(event) => {
              event.stopPropagation();
              onStatus(null);
              onExpand(false);
            }}
          >
            {statusLabel}
          </button>
          {allowDescription && !expanded ? (
            <button
              type="button"
              className="af-check__add-desc"
              onClick={(event) => {
                event.stopPropagation();
                onExpand(true);
              }}
            >
              添加描述
            </button>
          ) : null}
        </span>
      </div>
      {expanded ? (
        <div className="af-check__detail">
          <TextArea
            className="af-check__desc-input"
            placeholder="请输入描述"
            autoSize={{ minRows: 3 }}
            value={entry.description}
            onChange={(value) => onEntry({ description: value })}
          />
          <ChecklistPhotoUpload
            photos={entry.images}
            maxCount={photoMaxCount}
            onChange={(images) => onEntry({ images })}
          />
        </div>
      ) : null}
    </div>
  );
}

function ChecklistPhotoUpload({
  photos,
  maxCount,
  onChange,
}: {
  photos: MobileFileDto[];
  maxCount: number;
  onChange(photos: MobileFileDto[]): void;
}) {
  const cameraRef = useRef<HTMLInputElement>(null);
  const albumRef = useRef<HTMLInputElement>(null);
  const [viewerIndex, setViewerIndex] = useState(-1);
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const previewUrlsRef = useRef(new Map<string, string>());
  const photosRef = useRef<MobileFileDto[]>(photos);
  const [uploading, setUploading] = useState(false);
  photosRef.current = photos;

  useEffect(() => {
    let cancelled = false;
    for (const photo of photos) {
      if (previewUrlsRef.current.has(photo.id)) continue;
      const url = photo.contentUrl || photo.url || '';
      if (!url) continue;
      void fetchMobileFileBlob(url)
        .then((blob) => {
          if (cancelled) return;
          const objectUrl = URL.createObjectURL(blob);
          previewUrlsRef.current.set(photo.id, objectUrl);
          setPreviews(Object.fromEntries(previewUrlsRef.current));
        })
        .catch(() => {
          /* preview unavailable */
        });
    }
    return () => {
      cancelled = true;
    };
  }, [photos]);

  useEffect(
    () => () => {
      for (const url of previewUrlsRef.current.values()) {
        URL.revokeObjectURL(url);
      }
      previewUrlsRef.current.clear();
    },
    [],
  );

  const uploadFiles = async (files: File[]) => {
    if (files.length === 0 || uploading) return;
    const remaining = maxCount - photosRef.current.length;
    if (remaining <= 0) return;
    const batch = files.slice(0, remaining);
    setUploading(true);
    for (const file of batch) {
      try {
        const dto = await uploadMobileFile('/api/mobile/files', file);
        let previewUrl = dto.contentUrl || dto.url || '';
        try {
          const blob = await fetchMobileFileBlob(previewUrl);
          const objectUrl = URL.createObjectURL(blob);
          previewUrlsRef.current.set(dto.id, objectUrl);
          previewUrl = objectUrl;
        } catch {
          /* keep content url */
        }
        setPreviews(Object.fromEntries(previewUrlsRef.current));
        photosRef.current = [...photosRef.current, dto];
        onChange(photosRef.current);
      } catch {
        Toast.show({ icon: 'fail', content: '图片上传失败' });
      }
    }
    setUploading(false);
  };

  const removePhoto = (id: string) => {
    const url = previewUrlsRef.current.get(id);
    if (url) {
      URL.revokeObjectURL(url);
      previewUrlsRef.current.delete(id);
    }
    setPreviews(Object.fromEntries(previewUrlsRef.current));
    photosRef.current = photosRef.current.filter((photo) => photo.id !== id);
    onChange(photosRef.current);
  };

  const canAdd = photos.length < maxCount;
  const remaining = Math.max(0, maxCount - photos.length);
  const previewUrls = photos
    .map((photo) => previews[photo.id])
    .filter((url): url is string => Boolean(url));

  return (
    <div className="af-check__photos">
      <div className="af-check__upload-row">
        <button
          type="button"
          className="af-check__upload-btn"
          disabled={!canAdd || uploading}
          onClick={() => cameraRef.current?.click()}
        >
          <IconCamera />
          <span>拍照</span>
        </button>
        <button
          type="button"
          className="af-check__upload-btn"
          disabled={!canAdd || uploading}
          onClick={() => albumRef.current?.click()}
        >
          <IconImage />
          <span>相册上传</span>
        </button>
        {remaining > 0 ? <span className="af-check__photo-count">{photos.length}/{maxCount}</span> : null}
        <input
          ref={cameraRef}
          type="file"
          accept="image/*"
          capture="environment"
          hidden
          onChange={(event) => {
            const files = Array.from(event.target.files ?? []);
            event.target.value = '';
            void uploadFiles(files);
          }}
        />
        <input
          ref={albumRef}
          type="file"
          accept="image/*"
          multiple
          hidden
          onChange={(event) => {
            const files = Array.from(event.target.files ?? []);
            event.target.value = '';
            void uploadFiles(files);
          }}
        />
      </div>
      {photos.length > 0 ? (
        <div className="af-check__thumbs">
          {photos.map((photo) => {
            const previewUrl = previews[photo.id];
            const loadedIndex = previewUrl ? previewUrls.indexOf(previewUrl) : -1;
            return (
              <div key={photo.id} className="af-check__thumb">
                {previewUrl ? (
                  <button
                    type="button"
                    className="af-check__thumb-open"
                    aria-label={`查看${photo.name ?? '照片'}`}
                    onClick={() => setViewerIndex(loadedIndex)}
                  >
                    <img src={previewUrl} alt={photo.name ?? '照片'} />
                  </button>
                ) : (
                  <span className="af-check__thumb-loading" />
                )}
                <button
                  type="button"
                  className="af-check__thumb-del"
                  aria-label={`删除${photo.name ?? '照片'}`}
                  onClick={() => removePhoto(photo.id)}
                >
                  ×
                </button>
              </div>
            );
          })}
        </div>
      ) : null}
      {viewerIndex >= 0 && previewUrls.length > 0 ? (
        <ImageViewer.Multi
          images={previewUrls}
          defaultIndex={viewerIndex}
          visible
          onClose={() => setViewerIndex(-1)}
        />
      ) : null}
    </div>
  );
}

function entryHasContent(entry: ChecklistEntry) {
  return (
    String(entry.description ?? '').trim() !== '' ||
    (Array.isArray(entry.images) && entry.images.length > 0)
  );
}

export function entriesFromValue(value: unknown, items: ChecklistItemDef[]): ChecklistValue {
  if (!Array.isArray(value)) {
    return items.map((item) => ({
      id: item.id,
      name: item.label,
      status: null,
      description: '',
      images: [],
    }));
  }
  const byId = new Map<string, ChecklistEntry>();
  for (const entry of value) {
    if (typeof entry !== 'object' || entry == null) continue;
    const raw = entry as Record<string, unknown>;
    const id = typeof raw.id === 'string' ? raw.id : typeof raw.itemId === 'string' ? raw.itemId : '';
    if (!id) continue;
    byId.set(id, {
      id,
      name: typeof raw.name === 'string' ? raw.name : '',
      status: typeof raw.status === 'string' ? raw.status : typeof raw.result === 'string' ? raw.result : null,
      description: typeof raw.description === 'string' ? raw.description : typeof raw.remark === 'string' ? raw.remark : '',
      images: Array.isArray(raw.images) ? raw.images : Array.isArray(raw.photos) ? raw.photos : [],
    });
  }
  return items.map((item) => byId.get(item.id) ?? {
    id: item.id,
    name: item.label,
    status: null,
    description: '',
    images: [],
  });
}

export function checklistSummary(node: MobileSchemaNode, value: unknown): string {
  const items = checklistItems(node);
  const entries = entriesFromValue(value, items);
  const results = checklistResults(node);
  const counts = new Map<string, number>();
  for (const entry of entries) {
    if (!entry.status) continue;
    counts.set(entry.status, (counts.get(entry.status) ?? 0) + 1);
  }
  if (counts.size === 0) {
    return '未完成';
  }
  const parts = results
    .filter((result) => counts.has(result.id))
    .map((result) => `${counts.get(result.id)} 项${result.label}`);
  return parts.length > 0 ? parts.join('、') : '未完成';
}
