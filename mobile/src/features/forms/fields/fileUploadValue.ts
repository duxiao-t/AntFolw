import type { MobileFileDto } from '../files.api';

export type UploadStatus =
  | 'queued'
  | 'uploading'
  | 'processing'
  | 'ready'
  | 'failed'
  | 'deleting'
  | 'delete_failed';

export type UploadItem = {
  localId: string;
  file: File;
  status: UploadStatus;
  progress: number;
  remote?: MobileFileDto;
  error?: string;
  createdInSession?: boolean;
};

const uploadQueueBlockedSymbol = Symbol('antflowUploadQueueBlocked');

/** 表单值仍是文件数组，额外用一个隐藏属性告诉校验层"队列里还有没传完的"。 */
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

export function readyFiles(items: UploadItem[]) {
  return items
    .filter((item): item is UploadItem & { remote: MobileFileDto; status: 'ready' } =>
      item.status === 'ready' && item.remote != null,
    )
    .map((item) => item.remote);
}

export function asReadyFiles(value: unknown): MobileFileDto[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((item): item is MobileFileDto =>
    typeof item === 'object' && item != null && typeof (item as MobileFileDto).id === 'string');
}

/**
 * 合并父表单回填的 ready 值：会话内新建但还没传完的条目要留着，
 * 否则草稿回填会把它从列表里"抹掉"，用户看不到进度也没法重试。
 */
export function mergeReadyItems(items: UploadItem[], readyValues: MobileFileDto[]) {
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

export function hasIncompleteUploads(items: UploadItem[]) {
  return items.some((item) =>
    item.status === 'failed'
    || item.status === 'queued'
    || item.status === 'uploading'
    || item.status === 'processing'
    || item.status === 'deleting'
    || item.status === 'delete_failed');
}

export function localBlocker(items: UploadItem[]) {
  return hasIncompleteUploads(items) ? '仍有文件未完成上传' : null;
}

export function isUploadActive(item: UploadItem) {
  return item.status === 'uploading' || item.status === 'processing';
}

export function sameUploadItems(left: UploadItem[], right: UploadItem[]) {
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

export function fileContentUrl(file: MobileFileDto) {
  return file.contentUrl || file.url || '';
}

export function isImageContentType(contentType: string | undefined) {
  return typeof contentType === 'string' && contentType.toLowerCase().startsWith('image/');
}
