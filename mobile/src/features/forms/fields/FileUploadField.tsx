import { useEffect, useMemo, useRef, useState } from 'react';
import type { MobileFileDto } from '../files.api';
import { uploadMobileFile } from '../files.api';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary } from './fieldShared';

export type UploadItem = {
  localId: string;
  file: File;
  status: 'queued' | 'uploading' | 'ready' | 'failed';
  progress: number;
  remote?: MobileFileDto;
  error?: string;
};

const uploadQueueBlockedSymbol = Symbol('antflowUploadQueueBlocked');

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
  const inputRef = useRef<HTMLInputElement>(null);
  const [items, setItems] = useState<UploadItem[]>([]);
  const readyValues = useMemo(() => asReadyFiles(props.value), [props.value]);

  useEffect(() => {
    setItems((current) => mergeReadyItems(current, readyValues));
  }, [readyValues]);

  return (
    <FieldShell
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
            onChange={(event) => {
              const files = Array.from(event.target.files ?? []);
              event.target.value = '';
              for (const file of files) {
                void queueFileUpload(file);
              }
            }}
          />
          <div style={{ display: 'grid', gap: 8, marginTop: 8 }}>
            {items.map((item) => (
              <div key={item.localId}>
                <div>{item.file.name}</div>
                <div>{statusLabel(item)}</div>
                {item.status === 'failed' ? (
                  <button type="button" onClick={() => void queueFileUpload(item.file, item.localId)}>
                    重试 {item.file.name}
                  </button>
                ) : null}
                <button type="button" aria-label={`删除 ${item.file.name}`} onClick={() => removeItem(item.localId)}>
                  删除
                </button>
              </div>
            ))}
          </div>
        </>
      )}
    </FieldShell>
  );

  async function queueFileUpload(file: File, localId = createLocalId()) {
    const uploadingItem: UploadItem = { localId, file, status: 'uploading', progress: 0 };
    setItems((current) => {
      const next = [
        ...current.filter((item) => item.localId !== localId),
        uploadingItem,
      ];
      emitUploadValue(next);
      return next;
    });
    await wait(20);
    try {
      const remote = await uploadMobileFile(endpoint, file);
      setItems((current) => {
        const next = current.map((item): UploadItem =>
          item.localId === localId
            ? { ...item, status: 'ready', progress: 100, remote, error: undefined }
            : item,
        );
        emitUploadValue(next);
        return next;
      });
    } catch (error) {
      setItems((current) => {
        const next = current.map((item): UploadItem =>
          item.localId === localId
            ? { ...item, status: 'failed', progress: 100, error: errorMessage(error) }
            : item,
        );
        emitUploadValue(next);
        return next;
      });
    }
  }

  function removeItem(localId: string) {
    setItems((current) => {
      const next = current.filter((item) => item.localId !== localId);
      emitUploadValue(next);
      return next;
    });
  }

  function emitUploadValue(nextItems: UploadItem[]) {
    const ready = readyFiles(nextItems);
    const blocked = nextItems.some((item) =>
      item.status === 'failed' || item.status === 'queued' || item.status === 'uploading');
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
      file: new File([], item.id),
      status: 'ready' as const,
      progress: 100,
      remote: item,
    }));
  return [...keep, ...missing];
}

function localBlocker(items: UploadItem[]) {
  if (items.some((item) => item.status === 'failed' || item.status === 'queued' || item.status === 'uploading')) {
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
  return `上传中 ${item.progress}%`;
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
