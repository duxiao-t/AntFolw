import { useEffect, useMemo, useRef, useState } from 'react';
import type { MobileFileDto } from '../files.api';
import { deleteMobileFile, uploadMobileFile } from '../files.api';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary } from './fieldShared';

export type UploadItem = {
  localId: string;
  file: File;
  status: 'queued' | 'uploading' | 'ready' | 'failed' | 'deleting' | 'delete_failed';
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
  const itemsRef = useRef<UploadItem[]>([]);
  const readyValues = useMemo(() => asReadyFiles(props.value), [props.value]);

  useEffect(() => {
    const next = mergeReadyItems(itemsRef.current, readyValues);
    itemsRef.current = next;
    setItems(next);
  }, [readyValues]);

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
              const previewUrl = previewImages && item.remote ? fileContentUrl(item.remote) : '';
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
    const uploadingItem: UploadItem = { localId, file, status: 'uploading', progress: 8 };
    commitItems([
      ...itemsRef.current.filter((item) => item.localId !== localId),
      uploadingItem,
    ]);
    await wait(20);
    try {
      const remote = await uploadMobileFile(endpoint, file, (progress) => {
        updateUploadProgress(localId, progress);
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
            ? { ...item, status: 'failed', progress: 100, error: errorMessage(error) }
            : item,
        ),
      );
    }
  }

  function updateUploadProgress(localId: string, progress: number) {
    const bounded = Math.min(100, Math.max(8, Math.round(progress)));
    let changed = false;
    const next = itemsRef.current.map((item): UploadItem => {
      if (item.localId !== localId || item.status !== 'uploading' || item.progress >= bounded) {
        return item;
      }
      changed = true;
      return { ...item, progress: bounded };
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
      .filter((item): item is UploadItem & { remote: MobileFileDto; status: 'ready' } =>
        item.status === 'ready' && item.remote != null,
      )
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
    || item.status === 'deleting'
    || item.status === 'delete_failed')) {
    return '仍有文件未完成上传';
  }
  return null;
}

function statusLabel(item: UploadItem) {
  if (item.status === 'failed') {
    return '上传失败';
  }
  if (item.status === 'ready') {
    return '100%';
  }
  if (item.status === 'deleting') {
    return '删除中';
  }
  if (item.status === 'delete_failed') {
    return '删除失败';
  }
  return `上传中 ${item.progress}%`;
}

function progressTone(item: UploadItem) {
  return item.status === 'failed' || item.status === 'delete_failed' ? 'error' : 'success';
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
