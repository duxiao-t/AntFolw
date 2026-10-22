import type { MobileSchemaNode } from '../schema/types';

export const DEFAULT_FILE_ACCEPT = '';

export type FileUploadConfig = {
  endpoint: string;
  accept: string;
  multiple: boolean;
  previewImages: boolean;
  maxCount?: number;
  maxDuration?: number;
  capture?: 'environment';
  addLabel: string;
  unitLabel: string;
};

export function resolveFileUploadConfig(node: MobileSchemaNode): FileUploadConfig {
  const previewImages = node.props?.preview === true;
  return {
    endpoint: String(node.props?.uploadEndpoint ?? '/api/mobile/files'),
    accept: typeof node.props?.accept === 'string' ? node.props.accept : DEFAULT_FILE_ACCEPT,
    multiple: node.props?.multiple !== false,
    previewImages,
    maxCount: typeof node.props?.maxCount === 'number' ? node.props.maxCount : undefined,
    maxDuration: typeof node.props?.maxDuration === 'number' ? node.props.maxDuration : undefined,
    capture: node.props?.source === 'camera' ? 'environment' : undefined,
    addLabel: typeof node.props?.addLabel === 'string' && node.props.addLabel
      ? node.props.addLabel
      : previewImages ? '添加图片' : '添加附件',
    unitLabel: typeof node.props?.unitLabel === 'string' && node.props.unitLabel
      ? node.props.unitLabel
      : '个文件',
  };
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

export function isHeicFile(file: File) {
  const type = typeof file.type === 'string' ? file.type.toLowerCase() : '';
  return type === 'image/heic' || type === 'image/heif' || /\.(heic|heif)$/i.test(file.name);
}

export function isVideoFile(file: File) {
  return typeof file.type === 'string' && file.type.toLowerCase().startsWith('video/');
}

export function errorMessage(error: unknown) {
  if (error instanceof Error) {
    return error.message;
  }
  return '上传失败';
}

/** 浏览器解不了 HEIC：借 canvas 转成 JPEG 再上传，失败返回 null 由调用方给可操作提示。 */
export async function heicToJpeg(file: File): Promise<File | null> {
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

export function readVideoDuration(file: File): Promise<number> {
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
