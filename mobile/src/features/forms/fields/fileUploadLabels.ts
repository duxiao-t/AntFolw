import type { UploadItem } from './fileUploadValue';
import { isImageContentType } from './fileUploadValue';

export function statusLabel(item: UploadItem) {
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

export function progressPercent(item: UploadItem) {
  return Math.min(100, Math.max(0, Math.round(item.status === 'ready' ? 100 : item.progress)));
}

export function progressTone(item: UploadItem) {
  return item.status === 'failed' || item.status === 'delete_failed' ? 'error' : 'success';
}

export function isImageUploadItem(item: UploadItem) {
  return isImageContentType(item.remote?.contentType || item.file.type);
}

export function fileTypeLabel(item: UploadItem) {
  if (isImageUploadItem(item)) {
    return 'IMG';
  }
  const extension = item.file.name.split('.').pop();
  if (extension && extension !== item.file.name && extension.length <= 5) {
    return extension.toUpperCase();
  }
  return 'FILE';
}

export function fileTypeClass(item: UploadItem) {
  if (isImageUploadItem(item)) {
    return 'upload-file-row__type--image';
  }
  return fileTypeLabel(item) === 'PDF' ? 'upload-file-row__type--pdf' : '';
}

export function fileSizeLabel(item: UploadItem) {
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

export function uploadHint(accept: string, multiple: boolean) {
  const prefix = multiple ? '支持多文件' : '支持单文件';
  if (!accept || accept.trim() === '') {
    return `${prefix} · 任意格式`;
  }
  const trimmed = accept.trim();
  if (trimmed === 'image/*') {
    return `${prefix} · 图片格式`;
  }
  if (trimmed === 'video/*') {
    return `${prefix} · 视频格式`;
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
  return `${prefix} · ${formatText}`;
}
