import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MobileFileDto, UploadProgressEvent } from '../files.api';
import { deleteMobileFile, fetchMobileFileBlob, uploadMobileFile } from '../files.api';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary } from './fieldShared';

export type UploadItem = {
  localId: string;
  file: File;
  status: 'queued' | 'uploading' | 'processing' | 'ready' | 'failed' | 'deleting' | 'delete_failed';
  progress: number;
  remote?: MobileFileDto;
  error?: string;
  createdInSession?: boolean;
};

const uploadQueueBlockedSymbol = Symbol('antflowUploadQueueBlocked');
const DEFAULT_FILE_ACCEPT = 'image/jpeg,image/png,application/pdf';

export type FileUploadValue = MobileFileDto[] & {
  [uploadQueueBlockedSymbol]?: boolean;
};

export function createFileUploadValue(files: MobileFileDto[], queueBlocked: boolean): FileUploadValue {
  const value = [...files] as FileUploadValue;
  if (queueBlocked) {
    Object.defineProperty(value, uploadQueueBlockedSymbol, {
      value: true,
      enumerable: false,
    });
  }
  return value;
}

export function hasBlockingUploadQueue(value: unknown) {
  return Array.isArray(value) && (value as FileUploadValue)[uploadQueueBlockedSymbol] === true;
}

export function FileUploadField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const endpoint = String(props.node.props?.uploadEndpoint ?? '/api/mobile/files');
  const accept = typeof props.node.props?.accept === 'string' ? props.node.props.accept : DEFAULT_FILE_ACCEPT;
  const multiple = props.node.props?.multiple !== false;
  const previewImages = props.node.props?.preview === true;
  const inputRef = useRef<HTMLInputElement>(null);
  const [items, setItems] = useState<UploadItem[]>([]);
  const [previewUrls, setPreviewUrls] = useState<Record<string, string>>({});
  const itemsRef = useRef<UploadItem[]>([]);
  const previewImagesRef = useRef(previewImages);
  const mountedRef = useRef(true);
  const previewUrlsRef = useRef(new Map<string, string>());
  const previewRemoteIdsRef = useRef(new Map<string, string>());
  const previewLoadsRef = useRef(new Map<string, string>());
  const readyValues = useMemo(() => asReadyFiles(props.value), [props.value]);
  previewImagesRef.current = previewImages;

  const publishPreviewUrls = useCallback(() => {
    setPreviewUrls(Object.fromEntries(previewUrlsRef.current));
  }, []);

  const clearPreviewUrls = useCallback((publish = true) => {
    if (previewUrlsRef.current.size === 0 && previewRemoteIdsRef.current.size === 0) {
      return;
    }
    for (const previewUrl of previewUrlsRef.current.values()) {
      revokePreviewUrl(previewUrl);
    }
    previewUrlsRef.current.clear();
    previewRemoteIdsRef.current.clear();
    if (publish) {
      setPreviewUrls({});
    }
  }, []);

  const isCurrentPreviewTarget = useCallback((localId: string, remoteId: string) => {
    return mountedRef.current && previewImagesRef.current && itemsRef.current.some((item) => {
      const remote = item.remote;
      return item.localId === localId
        && item.status === 'ready'
        && remote?.id === remoteId
        && isImageContentType(remote.contentType || item.file.type);
    });
  }, []);

  useEffect(() => {
    const next = mergeReadyItems(itemsRef.current, readyValues);
    if (sameUploadItems(itemsRef.current, next)) {
      return;
    }
    itemsRef.current = next;
    setItems(next);
  }, [readyValues]);

  useEffect(() => {
    if (!previewImages || !canUseObjectUrls()) {
      clearPreviewUrls();
      return;
    }
    const candidates = items.filter(isPreviewableImage);
    const activeRemoteIds = new Map(candidates.map((item) => [item.localId, item.remote.id]));
    let changed = false;
    for (const [localId, previewUrl] of previewUrlsRef.current) {
      if (previewRemoteIdsRef.current.get(localId) !== activeRemoteIds.get(localId)) {
        revokePreviewUrl(previewUrl);
        previewUrlsRef.current.delete(localId);
        previewRemoteIdsRef.current.delete(localId);
        changed = true;
      }
    }
    if (changed) {
      publishPreviewUrls();
    }
    for (const item of candidates) {
      const remoteId = item.remote.id;
      if (
        previewRemoteIdsRef.current.get(item.localId) === remoteId
        && previewUrlsRef.current.has(item.localId)
      ) {
        continue;
      }
      if (previewLoadsRef.current.get(item.localId) === remoteId) {
        continue;
      }
      previewLoadsRef.current.set(item.localId, remoteId);
      void fetchMobileFileBlob(fileContentUrl(item.remote))
        .then((blob) => {
          if (!isCurrentPreviewTarget(item.localId, remoteId)) {
            return;
          }
          const objectUrl = URL.createObjectURL(blob);
          const previous = previewUrlsRef.current.get(item.localId);
          if (previous) {
            revokePreviewUrl(previous);
          }
          previewUrlsRef.current.set(item.localId, objectUrl);
          previewRemoteIdsRef.current.set(item.localId, remoteId);
          publishPreviewUrls();
        })
        .catch(() => undefined)
        .finally(() => {
          if (previewLoadsRef.current.get(item.localId) === remoteId) {
            previewLoadsRef.current.delete(item.localId);
          }
        });
    }
  }, [items, previewImages, clearPreviewUrls, isCurrentPreviewTarget, publishPreviewUrls]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      clearPreviewUrls(false);
    };
  }, [clearPreviewUrls]);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props) || localBlocker(items)}
      summary={props.mode === 'readonly' ? readonlySummary(readyValues) : undefined}
    >
      {props.mode === 'readonly' ? null : (
        <>
          <input
            ref={inputRef}
            aria-label={label}
            type="file"
            accept={accept}
            multiple={multiple}
            onChange={(event) => {
              const files = Array.from(event.target.files ?? []);
              event.target.value = '';
              for (const file of files) {
                void queueFileUpload(file);
              }
            }}
          />
          <div className="af-upload-list">
            {items.map((item) => {
              const previewUrl = previewImages && item.remote ? previewUrls[item.localId] ?? '' : '';
              return (
                <div key={item.localId} className="af-upload-list__item">
                  {previewUrl ? (
                    <button
                      type="button"
                      className="af-upload-list__thumb"
                      aria-label={`预览 ${item.file.name}`}
                      onClick={() => window.open(previewUrl, '_blank', 'noopener,noreferrer')}
                    >
                      <img src={previewUrl} alt="" />
                    </button>
                  ) : null}
                  <div className="af-upload-list__main">
                    <div className="af-upload-list__name">{item.file.name}</div>
                    <div className={`af-upload-list__status af-upload-list__status--${item.status}`}>
                      {statusLabel(item)}
                    </div>
                    <div className={`af-upload-list__progress af-upload-list__progress--${progressTone(item)}`} aria-hidden="true">
                      <span style={{ width: `${item.progress}%` }} />
                    </div>
                    {item.error ? <div className="af-upload-list__error">{item.error}</div> : null}
                  </div>
                  {item.status === 'failed' ? (
                    <button type="button" className="af-link-button" onClick={() => void queueFileUpload(item.file, item.localId)}>
                      重试 {item.file.name}
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className="af-link-button"
                    aria-label={`删除 ${item.file.name}`}
                    disabled={item.status === 'deleting'}
                    onClick={() => void removeItem(item.localId)}
                  >
                    删除
                  </button>
                </div>
              );
            })}
          </div>
        </>
      )}
    </FieldShell>
  );

  async function queueFileUpload(file: File, localId = createLocalId()) {
    const uploadingItem: UploadItem = { localId, file, status: 'uploading', progress: 0 };
    commitItems([
      ...itemsRef.current.filter((item) => item.localId !== localId),
      uploadingItem,
    ]);
    await wait(20);
    try {
      const remote = await uploadMobileFile(endpoint, file, (event) => {
        updateUploadProgress(localId, event);
      });
      commitItems(
        itemsRef.current.map((item): UploadItem =>
          item.localId === localId
            ? { ...item, status: 'ready', progress: 100, remote, error: undefined, createdInSession: true }
            : item,
        ),
      );
    } catch (error) {
      commitItems(
        itemsRef.current.map((item): UploadItem =>
          item.localId === localId
            ? { ...item, status: 'failed', error: errorMessage(error) }
            : item,
        ),
      );
    }
  }

  function updateUploadProgress(localId: string, event: UploadProgressEvent) {
    const bounded = Math.min(100, Math.max(0, Math.round(event.progress)));
    const nextStatus = event.phase === 'uploading' ? 'uploading' : 'processing';
    let changed = false;
    const next = itemsRef.current.map((item): UploadItem => {
      if (item.localId !== localId || !isUploadActive(item)) {
        return item;
      }
      if (item.status === 'processing' && event.phase === 'uploading') {
        return item;
      }
      if (item.progress >= bounded && item.status === nextStatus) {
        return item;
      }
      changed = true;
      return { ...item, status: nextStatus, progress: Math.max(item.progress, bounded), error: undefined };
    });
    if (changed) {
      commitItems(next, false);
    }
  }

  async function removeItem(localId: string) {
    const target = itemsRef.current.find((item) => item.localId === localId);
    if (target?.status === 'deleting') {
      return;
    }
    if (target?.remote && target.createdInSession) {
      commitItems(
        itemsRef.current.map((item): UploadItem =>
          item.localId === localId ? { ...item, status: 'deleting', progress: 100 } : item,
        ),
      );
      try {
        await deleteMobileFile(target.remote.id);
      } catch (error) {
        commitItems(
          itemsRef.current.map((item): UploadItem =>
            item.localId === localId
              ? { ...item, status: 'delete_failed', progress: 100, error: errorMessage(error) }
              : item,
          ),
        );
        return;
      }
    }
    commitItems(itemsRef.current.filter((item) => item.localId !== localId));
  }

  function commitItems(nextItems: UploadItem[], emitValue = true) {
    itemsRef.current = nextItems;
    setItems(nextItems);
    if (emitValue) {
      emitUploadValue(nextItems);
    }
  }

  function emitUploadValue(nextItems: UploadItem[]) {
    const ready = readyFiles(nextItems);
    const blocked = nextItems.some((item) =>
      item.status === 'failed'
      || item.status === 'queued'
      || item.status === 'uploading'
      || item.status === 'processing'
      || item.status === 'deleting'
      || item.status === 'delete_failed');
    props.onValueChange(props.node.id, createFileUploadValue(ready, blocked));
  }

}

function readyFiles(items: UploadItem[]) {
  return items
    .filter((item): item is UploadItem & { remote: MobileFileDto; status: 'ready' } =>
      item.status === 'ready' && item.remote != null,
    )
    .map((item) => item.remote);
}

function asReadyFiles(value: unknown): MobileFileDto[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((item): item is MobileFileDto =>
    typeof item === 'object' && item != null && typeof (item as MobileFileDto).id === 'string');
}

function mergeReadyItems(items: UploadItem[], readyValues: MobileFileDto[]) {
  const readyById = new Map(readyValues.map((item) => [item.id, item]));
  const keep = items.filter((item) => item.status !== 'ready' || !item.remote || readyById.has(item.remote.id));
  const existingIds = new Set(
    keep
      .filter((item): item is UploadItem & { remote: MobileFileDto } => item.remote != null)
      .map((item) => item.remote.id),
  );
  const missing = readyValues
    .filter((item) => !existingIds.has(item.id))
    .map((item) => ({
      localId: item.id,
      file: new File([], item.name || item.id),
      status: 'ready' as const,
      progress: 100,
      remote: item,
      createdInSession: false,
    }));
  return [...keep, ...missing];
}

function localBlocker(items: UploadItem[]) {
  if (items.some((item) =>
    item.status === 'failed'
    || item.status === 'queued'
    || item.status === 'uploading'
    || item.status === 'processing'
    || item.status === 'deleting'
    || item.status === 'delete_failed')) {
    return '仍有文件未完成上传';
  }
  return null;
}

function isPreviewableImage(item: UploadItem): item is UploadItem & { remote: MobileFileDto } {
  return item.status === 'ready'
    && item.remote != null
    && fileContentUrl(item.remote).length > 0
    && isImageContentType(item.remote.contentType || item.file.type);
}

function isImageContentType(contentType: string | undefined) {
  return typeof contentType === 'string' && contentType.toLowerCase().startsWith('image/');
}

function canUseObjectUrls() {
  return typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function';
}

function revokePreviewUrl(previewUrl: string) {
  if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function') {
    URL.revokeObjectURL(previewUrl);
  }
}

function statusLabel(item: UploadItem) {
  if (item.status === 'failed') {
    return '上传失败';
  }
  if (item.status === 'ready') {
    return '已完成';
  }
  if (item.status === 'processing') {
    return '处理中';
  }
  if (item.status === 'deleting') {
    return '删除中';
  }
  if (item.status === 'delete_failed') {
    return '删除失败';
  }
  if (item.status === 'queued') {
    return '等待上传';
  }
  return item.progress > 0 ? `上传中 ${item.progress}%` : '上传中';
}

function progressTone(item: UploadItem) {
  return item.status === 'failed' || item.status === 'delete_failed' ? 'error' : 'success';
}

function isUploadActive(item: UploadItem) {
  return item.status === 'uploading' || item.status === 'processing';
}

function sameUploadItems(left: UploadItem[], right: UploadItem[]) {
  return left.length === right.length && left.every((item, index) => {
    const other = right[index];
    return other
      && item.localId === other.localId
      && item.status === other.status
      && item.progress === other.progress
      && item.error === other.error
      && item.remote?.id === other.remote?.id;
  });
}

function fileContentUrl(file: MobileFileDto) {
  return file.contentUrl || file.url || '';
}

function errorMessage(error: unknown) {
  if (error instanceof Error) {
    return error.message;
  }
  return '上传失败';
}

function wait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function createLocalId() {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `local-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
