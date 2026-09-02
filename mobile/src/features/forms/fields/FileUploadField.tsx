import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { DeleteOutline, FileOutline, PictureOutline, UploadOutline } from 'antd-mobile-icons';
import type { MobileFileDto, UploadProgressEvent } from '../files.api';
import { deleteMobileFile, fetchMobileFileBlob, uploadMobileFile } from '../files.api';
import type { MobileFieldProps, MobileSchemaNode } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired } from './fieldShared';
import { ReadonlyMediaList } from '../components/MediaPreview';
import { usePlatformAdapter } from '../../../shared/platform/PlatformProvider';
import { createClientId } from '../../../shared/clientId';
import { NativeActionContent } from './NativeActionContent';

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
const DEFAULT_FILE_ACCEPT = '';

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
  const platform = usePlatformAdapter();
  const label = fieldLabel(props.node);
  const endpoint = String(props.node.props?.uploadEndpoint ?? '/api/mobile/files');
  const accept = typeof props.node.props?.accept === 'string' ? props.node.props.accept : DEFAULT_FILE_ACCEPT;
  const multiple = props.node.props?.multiple !== false;
  const previewImages = props.node.props?.preview === true;
  const maxCount = typeof props.node.props?.maxCount === 'number' ? props.node.props.maxCount : undefined;
  const maxDuration = typeof props.node.props?.maxDuration === 'number' ? props.node.props.maxDuration : undefined;
  const capture = props.node.props?.source === 'camera' ? 'environment' : undefined;
  const addLabel = typeof props.node.props?.addLabel === 'string' && props.node.props.addLabel
    ? props.node.props.addLabel
    : previewImages ? '添加图片' : '添加附件';
  const unitLabel = typeof props.node.props?.unitLabel === 'string' && props.node.props.unitLabel
    ? props.node.props.unitLabel
    : '个文件';
  const inputRef = useRef<HTMLInputElement>(null);
  const [items, setItems] = useState<UploadItem[]>([]);
  const [previewUrls, setPreviewUrls] = useState<Record<string, string>>({});
  const [localError, setLocalError] = useState<string | null>(null);
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
      error={fieldError(props) || localBlocker(items) || localError}
    >
      {props.mode === 'readonly' ? (
        <ReadonlyMediaList files={readyValues} />
      ) : (
        <div className="upload-control af-upload-control">
          <div className="af-upload-list">
            {items.map((item) => {
              const previewUrl = previewImages && item.remote ? previewUrls[item.localId] ?? '' : '';
              return (
                <div key={item.localId} className={`upload-file-row af-upload-list__item af-upload-list__item--${item.status}`}>
                  {previewUrl ? (
                    <button
                      type="button"
                      className="af-upload-list__thumb"
                      aria-label={`预览 ${item.file.name}`}
                      onClick={() => window.open(previewUrl, '_blank', 'noopener,noreferrer')}
                    >
                      <img src={previewUrl} alt="" />
                    </button>
                  ) : (
                    <span className={`upload-file-row__type ${fileTypeClass(item)}`} aria-hidden="true">
                      {isImageUploadItem(item) ? <PictureOutline /> : <FileOutline />}
                      <span>{fileTypeLabel(item)}</span>
                    </span>
                  )}
                  <div className="upload-file-row__main af-upload-list__main">
                    <strong className="af-upload-list__name">{item.file.name}</strong>
                    <small className={`af-upload-list__status af-upload-list__status--${item.status}`}>
                      <span>{statusLabel(item)}</span>
                      <span className="af-upload-list__meta"> · {fileSizeLabel(item)}</span>
                    </small>
                    <div className="af-upload-list__progress-row">
                      <div
                        className={`af-upload-list__progress af-upload-list__progress--${progressTone(item)}`}
                        aria-hidden="true"
                      >
                        <span style={{ width: `${progressPercent(item)}%` }} />
                      </div>
                      <span className={`af-upload-list__progress-value af-upload-list__progress-value--${progressTone(item)}`}>
                        {progressPercent(item)}%
                      </span>
                    </div>
                    {item.error ? <div className="af-upload-list__error">{item.error}</div> : null}
                  </div>
                  <div className="upload-file-row__actions">
                    {item.status === 'failed' ? (
                      <button
                        type="button"
                        className="af-link-button af-upload-list__retry"
                        aria-label={`重试 ${item.file.name}`}
                        onClick={() => void queueFileUpload(item.file, item.localId)}
                      >
                        重试
                      </button>
                    ) : null}
                    <button
                      type="button"
                      className="af-link-button af-upload-list__delete"
                      aria-label={`删除 ${item.file.name}`}
                      disabled={item.status === 'deleting'}
                      onClick={() => void removeItem(item.localId)}
                    >
                      <DeleteOutline aria-hidden="true" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
          {previewImages && platform.chooseImages ? (
            <button type="button" className="upload-trigger upload-trigger--platform" onClick={() => {
              setLocalError(null);
              const remaining = Math.max(1, (maxCount ?? 20) - readyFiles(itemsRef.current).length);
              const configuredSource = props.node.props?.source;
              const source = configuredSource === 'camera' || configuredSource === 'album'
                ? configuredSource : 'both';
              void platform.chooseImages?.(remaining, source).then((selected) => {
                const nextItems = [...itemsRef.current, ...selected.map((remote) => ({
                  localId: remote.id,
                  file: new File([], remote.name || remote.id, { type: remote.contentType }),
                  status: 'ready' as const,
                  progress: 100,
                  remote,
                  createdInSession: true,
                }))].slice(0, maxCount ?? Number.POSITIVE_INFINITY);
                commitItems(nextItems);
              }).catch((error) => setLocalError(error instanceof Error ? error.message : '选择图片失败'));
            }}>
              <NativeActionContent
                icon={<UploadOutline aria-hidden="true" />}
                title={addLabel}
                hint="从相册选择或拍照"
              />
            </button>
          ) : <label className="upload-trigger">
            <input
              ref={inputRef}
              aria-label={label}
              type="file"
              accept={accept || undefined}
              multiple={multiple}
              capture={capture}
              onChange={(event) => {
                const files = Array.from(event.target.files ?? []);
                event.target.value = '';
                for (const file of files) {
                  void addFile(file);
                }
              }}
            />
            <NativeActionContent
              icon={<UploadOutline aria-hidden="true" />}
              title={addLabel}
              hint={uploadHint(accept, multiple)}
            />
          </label>}
        </div>
      )}
    </FieldShell>
  );

  async function addFile(file: File) {
    let nextFile = file;
    if (props.node.props?.convertHeic === true && isHeicFile(file)) {
      try {
        const converted = await heicToJpeg(file);
        if (!converted) {
          throw new Error('无法处理 HEIC 图片，请在手机设置中把相机格式改为"兼容性最好"后重试');
        }
        nextFile = converted;
      } catch (error) {
        setLocalError(errorMessage(error));
        return;
      }
    }
    const error = await beforeAddError(nextFile);
    if (error) {
      setLocalError(error);
      return;
    }
    setLocalError(null);
    await queueFileUpload(nextFile);
  }

  async function beforeAddError(file: File) {
    if (typeof maxCount === 'number') {
      const currentCount = itemsRef.current.filter(
        (item) => item.status !== 'deleting' && item.status !== 'delete_failed',
      ).length;
      if (currentCount >= maxCount) {
        return maxCountError(props.node, currentCount) ?? `最多上传 ${maxCount} ${unitLabel}`;
      }
    }
    if (typeof maxDuration === 'number' && isVideoFile(file)) {
      const duration = await readVideoDuration(file);
      if (duration > maxDuration) {
        return videoDurationError(props.node, duration) ?? `视频不能超过 ${maxDuration} 秒`;
      }
    }
    return null;
  }

  async function queueFileUpload(file: File, localId = createLocalId()) {
    const uploadingItem: UploadItem = { localId, file, status: 'uploading', progress: 0 };
    commitItems([
      ...itemsRef.current.filter((item) => item.localId !== localId),
      uploadingItem,
    ]);
    await wait(20);
    try {
      const remote = await uploadMobileFile(
        endpoint,
        file,
        (event) => {
          updateUploadProgress(localId, event);
        },
        uploadExtraFields(props.node),
      );
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
    if (event.phase === 'done') {
      return;
    }
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
  const hasBlockingLocalItems = items.some((item) =>
    item.status !== 'ready' && item.status !== 'delete_failed');
  const keep = items.filter((item) =>
    item.status !== 'ready'
    || !item.remote
    || readyById.has(item.remote.id)
    || (item.createdInSession === true && hasBlockingLocalItems));
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
    return `处理中 ${progressPercent(item)}%`;
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
  return `上传中 ${progressPercent(item)}%`;
}

function progressPercent(item: UploadItem) {
  return Math.min(100, Math.max(0, Math.round(item.status === 'ready' ? 100 : item.progress)));
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

function isImageUploadItem(item: UploadItem) {
  return isImageContentType(item.remote?.contentType || item.file.type);
}

function fileTypeLabel(item: UploadItem) {
  if (isImageUploadItem(item)) {
    return 'IMG';
  }
  const extension = item.file.name.split('.').pop();
  if (extension && extension !== item.file.name && extension.length <= 5) {
    return extension.toUpperCase();
  }
  return 'FILE';
}

function fileTypeClass(item: UploadItem) {
  if (isImageUploadItem(item)) {
    return 'upload-file-row__type--image';
  }
  return fileTypeLabel(item) === 'PDF' ? 'upload-file-row__type--pdf' : '';
}

function fileSizeLabel(item: UploadItem) {
  const size = item.remote?.sizeBytes ?? item.remote?.size ?? item.file.size;
  return formatBytes(size);
}

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  const digits = value >= 10 || unitIndex === 0 ? 0 : 1;
  return `${value.toFixed(digits)} ${units[unitIndex]}`;
}

function uploadHint(accept: string, multiple: boolean) {
  const prefix = multiple ? '支持多文件' : '支持单文件';
  if (!accept || accept.trim() === '') {
    return prefix + ' · 任意格式';
  }
  const trimmed = accept.trim();
  if (trimmed === 'image/*') {
    return prefix + ' · 图片格式';
  }
  if (trimmed === 'video/*') {
    return prefix + ' · 视频格式';
  }
  const formats = accept
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
    .map((item) => {
      if (item.startsWith('image/')) {
        return item.slice('image/'.length).toUpperCase();
      }
      if (item.startsWith('application/')) {
        return item.slice('application/'.length).toUpperCase();
      }
      return item.replace(/^\./, '').toUpperCase();
    });
  const uniqueFormats = Array.from(new Set(formats)).slice(0, 4);
  const formatText = uniqueFormats.length > 0 ? uniqueFormats.join('、') : '常用文件';
  return prefix + ' · ' + formatText;
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
  return createClientId('local');
}

function defaultMaxCount(node: MobileSchemaNode): number | undefined {
  if (node.type === 'image_upload') {
    return 20;
  }
  if (node.type === 'video_upload') {
    return 1;
  }
  return undefined;
}

function defaultUnitLabel(node: MobileSchemaNode): string {
  if (node.type === 'image_upload') {
    return '张图片';
  }
  if (node.type === 'video_upload') {
    return '个视频';
  }
  return '个文件';
}

export function maxCountError(node: MobileSchemaNode, currentCount: number): string | null {
  const maxCount = typeof node.props?.maxCount === 'number' ? node.props.maxCount : defaultMaxCount(node);
  if (typeof maxCount !== 'number' || currentCount < maxCount) {
    return null;
  }
  const unit = typeof node.props?.unitLabel === 'string' && node.props.unitLabel
    ? node.props.unitLabel
    : defaultUnitLabel(node);
  return `最多上传 ${maxCount} ${unit}`;
}

export function isHeicFile(file: File) {
  const type = typeof file.type === 'string' ? file.type.toLowerCase() : '';
  return type === 'image/heic' || type === 'image/heif' || /\.(heic|heif)$/i.test(file.name);
}

async function heicToJpeg(file: File): Promise<File | null> {
  if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
    return null;
  }
  const objectUrl = URL.createObjectURL(file);
  try {
    const image = await loadImage(objectUrl);
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    if (canvas.width <= 0 || canvas.height <= 0) {
      return null;
    }
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return null;
    }
    ctx.drawImage(image, 0, 0);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92));
    if (!blob) {
      return null;
    }
    const name = file.name.replace(/\.(heic|heif)$/i, '.jpg');
    return new File([blob], name || 'image.jpg', { type: 'image/jpeg' });
  } catch {
    return null;
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('image decode failed'));
    image.src = src;
  });
}

export function videoDurationError(node: MobileSchemaNode, durationSeconds: number): string | null {
  const maxDuration = typeof node.props?.maxDuration === 'number' ? node.props.maxDuration : 60;
  if (durationSeconds <= maxDuration) {
    return null;
  }
  return `视频不能超过 ${maxDuration} 秒`;
}

export function uploadExtraFields(node: MobileSchemaNode): Record<string, string> | undefined {
  if (node.props?.watermark !== true) {
    return undefined;
  }
  const text = typeof node.props?.watermarkText === 'string' ? node.props.watermarkText.trim() : '';
  return { watermark: 'true', watermarkText: text || 'AntFlow' };
}

function isVideoFile(file: File) {
  return typeof file.type === 'string' && file.type.toLowerCase().startsWith('video/');
}

function readVideoDuration(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
      reject(new Error('video duration is not supported'));
      return;
    }
    const objectUrl = URL.createObjectURL(file);
    const video = document.createElement('video');
    video.preload = 'metadata';
    const cleanup = () => {
      URL.revokeObjectURL(objectUrl);
      video.removeAttribute('src');
    };
    const timer = window.setTimeout(() => {
      cleanup();
      reject(new Error('video metadata timeout'));
    }, 15000);
    video.onloadedmetadata = () => {
      window.clearTimeout(timer);
      const duration = Number.isFinite(video.duration) ? video.duration : NaN;
      cleanup();
      if (Number.isFinite(duration)) {
        resolve(duration);
      } else {
        reject(new Error('video duration is unavailable'));
      }
    };
    video.onerror = () => {
      window.clearTimeout(timer);
      cleanup();
      reject(new Error('video metadata load failed'));
    };
    video.src = objectUrl;
  });
}
