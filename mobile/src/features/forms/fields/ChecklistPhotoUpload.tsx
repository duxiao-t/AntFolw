import { ImageViewer, Toast } from 'antd-mobile';
import { useEffect, useRef, useState } from 'react';
import { usePlatformAdapter } from '../../../shared/platform/PlatformProvider';
import type { MobileFileDto } from '../files.api';
import { fetchMobileFileBlob, uploadMobileFile } from '../files.api';

const iconProps = {
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
} as const;

/** 上传失败时给用户看的话：服务端的原因优先，取不到才退回通用文案。 */
function uploadFailureMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '图片上传失败';
}

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

/**
 * 单张检查项的照片上传。`previewUrlsRef` 是预览地址的唯一真源：
 * 删除单张时立即 revoke，卸载时全量 revoke，blob 回来前卸载则靠 `cancelled` 丢弃。
 */
export function ChecklistPhotoUpload({
  photos,
  maxCount,
  onChange,
}: {
  photos: MobileFileDto[];
  maxCount: number;
  onChange(photos: MobileFileDto[]): void;
}) {
  const platform = usePlatformAdapter();
  const cameraRef = useRef<HTMLInputElement>(null);
  const albumRef = useRef<HTMLInputElement>(null);
  const [viewerIndex, setViewerIndex] = useState(-1);
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const previewUrlsRef = useRef(new Map<string, string>());
  const photosRef = useRef<MobileFileDto[]>(photos);
  const [uploading, setUploading] = useState(false);
  const [failedFiles, setFailedFiles] = useState<File[]>([]);
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
    setFailedFiles([]);
    const failed: File[] = [];
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
      } catch (error) {
        // 失败原因（"转码队列忙"…）要透出来，并留着文件让用户能重试——原来只弹一句
        // "图片上传失败"，用户既不知道原因也没有下一步。
        failed.push(file);
        Toast.show({ icon: 'fail', content: uploadFailureMessage(error) });
      }
    }
    if (failed.length > 0) setFailedFiles(failed);
    setUploading(false);
  };

  const chooseImages = async (source: 'camera' | 'album') => {
    const remaining = maxCount - photosRef.current.length;
    if (remaining <= 0 || uploading) return;
    if (!platform.chooseImages) {
      (source === 'camera' ? cameraRef : albumRef).current?.click();
      return;
    }
    setUploading(true);
    try {
      const selected = await platform.chooseImages(remaining, source);
      const next = [...photosRef.current, ...selected].slice(0, maxCount);
      photosRef.current = next;
      onChange(next);
    } catch {
      Toast.show({ icon: 'fail', content: '图片选择或上传失败' });
    } finally {
      setUploading(false);
    }
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
          onClick={() => void chooseImages('camera')}
        >
          <IconCamera />
          <span>拍照</span>
        </button>
        <button
          type="button"
          className="af-check__upload-btn"
          disabled={!canAdd || uploading}
          onClick={() => void chooseImages('album')}
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
      {failedFiles.length > 0 ? (
        <div className="af-check__upload-row">
          <span>{failedFiles.length} 张上传失败</span>
          <button
            type="button"
            className="af-check__upload-btn"
            disabled={uploading}
            onClick={() => void uploadFiles(failedFiles)}
          >
            重试
          </button>
        </div>
      ) : null}
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
