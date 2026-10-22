import { useCallback, useEffect, useRef, useState } from 'react';
import type { MobileFileDto } from '../files.api';
import { fetchMobileFileBlob } from '../files.api';
import type { UploadItem } from './fileUploadValue';
import { fileContentUrl, isImageContentType } from './fileUploadValue';

/**
 * ready 图片的本地预览地址。三张表分工：
 * - previewUrlsRef：localId → object URL（唯一需要 revoke 的东西）
 * - previewRemoteIdsRef：localId → 当前 URL 对应的远端 id（换文件时要回收旧 URL）
 * - previewLoadsRef：正在拉的 localId → 远端 id（防同一目标并发重复请求）
 */
export function useUploadPreviewUrls(
  items: UploadItem[],
  itemsRef: { current: UploadItem[] },
  previewImages: boolean,
): Record<string, string> {
  const [previewUrls, setPreviewUrls] = useState<Record<string, string>>({});
  const previewImagesRef = useRef(previewImages);
  const mountedRef = useRef(true);
  const previewUrlsRef = useRef(new Map<string, string>());
  const previewRemoteIdsRef = useRef(new Map<string, string>());
  const previewLoadsRef = useRef(new Map<string, string>());
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
  }, [itemsRef]);

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

  return previewUrls;
}

function isPreviewableImage(item: UploadItem): item is UploadItem & { remote: MobileFileDto } {
  return item.status === 'ready'
    && item.remote != null
    && fileContentUrl(item.remote).length > 0
    && isImageContentType(item.remote.contentType || item.file.type);
}

function canUseObjectUrls() {
  return typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function';
}

function revokePreviewUrl(previewUrl: string) {
  if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function') {
    URL.revokeObjectURL(previewUrl);
  }
}
