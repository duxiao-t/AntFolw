import { ImageViewer } from 'antd-mobile';
import { FileOutline, PlayOutline } from 'antd-mobile-icons';
import { useEffect, useRef, useState } from 'react';
import { isApiError } from '../../../shared/api/errors';
import { fetchMobileFileBlob } from '../files.api';
import { AttachmentDownloadButton } from './AttachmentDownloadButton';

export type MediaFile = {
  id?: string;
  name?: string;
  contentType?: string;
  contentUrl: string;
  url?: string;
  size?: number;
};

const IMAGE_EXTENSION = /\.(jpe?g|png|gif|webp|bmp|heic|heif|avif)$/i;
const VIDEO_EXTENSION = /\.(mp4|mov|3gp|3gpp|webm|m4v)$/i;
const THUMB_MAX_SIZE = 256;

export function isImageFile(file: MediaFile) {
  return /^image\//i.test(file.contentType ?? '') || IMAGE_EXTENSION.test(file.name ?? '');
}

export function isVideoFile(file: MediaFile) {
  return /^video\//i.test(file.contentType ?? '') || VIDEO_EXTENSION.test(file.name ?? '');
}

export function formatFileSize(size?: number) {
  if (!size) return '0 KB';
  return size >= 1048576 ? `${(size / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(size / 1024))} KB`;
}

/** 审批只读态：所有附件统一以「缩略图/图标 + 文件名 + 大小 + 下载」行展示。 */
export function ReadonlyMediaList({ files }: { files: MediaFile[] }) {
  const imageFiles = files.filter(isImageFile);
  const [imageUrls, setImageUrls] = useState<Array<string | null>>(() => imageFiles.map(() => null));
  const [thumbUrls, setThumbUrls] = useState<Array<string | null>>(() => imageFiles.map(() => null));
  const [imageFailed, setImageFailed] = useState<boolean[]>(() => imageFiles.map(() => false));
  const [viewerIndex, setViewerIndex] = useState(-1);

  useEffect(() => {
    let alive = true;
    const objectUrls = new Array<string | null>(imageFiles.length).fill(null);
    const thumbRefs = new Array<string | null>(imageFiles.length).fill(null);
    setImageUrls(imageFiles.map(() => null));
    setThumbUrls(imageFiles.map(() => null));
    setImageFailed(imageFiles.map(() => false));
    imageFiles.forEach((file, index) => {
      fetchMobileFileBlob(file.contentUrl)
        .then(async (blob) => {
          if (!alive) return;
          const objectUrl = URL.createObjectURL(blob);
          objectUrls[index] = objectUrl;
          setImageUrls((previous) => {
            const next = [...previous];
            next[index] = objectUrl;
            return next;
          });
          const thumbUrl = (await createImageThumbUrl(blob)) ?? objectUrl;
          if (!alive) {
            if (thumbUrl !== objectUrl) revokeUrl(thumbUrl);
            return;
          }
          thumbRefs[index] = thumbUrl;
          setThumbUrls((previous) => {
            const next = [...previous];
            next[index] = thumbUrl;
            return next;
          });
        })
        .catch(() => {
          if (alive) {
            setImageFailed((previous) => {
              const next = [...previous];
              next[index] = true;
              return next;
            });
          }
        });
    });
    return () => {
      alive = false;
      objectUrls.forEach((objectUrl) => objectUrl && revokeUrl(objectUrl));
      thumbRefs.forEach((thumbUrl) => thumbUrl && revokeUrl(thumbUrl));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [imageFiles.map((file) => file.contentUrl).join('|')]);

  const readyUrls = imageUrls.filter((url): url is string => url != null);
  return (
    <div className="af-field__attachments">
      {files.map((file, index) => {
        const imageIndex = imageFiles.indexOf(file);
        const isImage = imageIndex >= 0;
        return (
          <AttachmentRow
            key={file.id ?? `${file.contentUrl}-${index}`}
            file={file}
            imageUrl={isImage ? imageUrls[imageIndex] ?? null : null}
            thumbUrl={isImage ? thumbUrls[imageIndex] ?? null : null}
            imageFailed={isImage ? imageFailed[imageIndex] ?? false : false}
            onOpenImage={
              isImage
                ? () => {
                    const currentUrl = imageUrls[imageIndex];
                    const targetIndex = currentUrl ? readyUrls.indexOf(currentUrl) : -1;
                    if (targetIndex >= 0) setViewerIndex(targetIndex);
                  }
                : undefined
            }
          />
        );
      })}
      {viewerIndex >= 0 && readyUrls.length > 0 ? (
        <ImageViewer.Multi
          images={readyUrls}
          defaultIndex={viewerIndex}
          visible
          onClose={() => setViewerIndex(-1)}
        />
      ) : null}
    </div>
  );
}

function AttachmentRow({
  file,
  imageUrl,
  thumbUrl,
  imageFailed,
  onOpenImage,
}: {
  file: MediaFile;
  imageUrl: string | null;
  thumbUrl: string | null;
  imageFailed: boolean;
  onOpenImage?: () => void;
}) {
  if (isImageFile(file)) {
    return (
      <div className="af-field__attachment">
        <button
          type="button"
          className="af-field__attachment-thumb af-field__attachment-thumb--image"
          aria-label={`预览 ${file.name ?? '图片'}`}
          disabled={!thumbUrl && !imageFailed}
          onClick={onOpenImage}
        >
          {thumbUrl ? (
            <img src={thumbUrl} alt={file.name ?? '图片'} />
          ) : (
            <span>{imageFailed ? '预览失败' : '加载中…'}</span>
          )}
        </button>
        <div className="af-field__attachment-main">
          <strong className="af-field__attachment-name" title={file.name}>
            {file.name ?? '图片'}
          </strong>
          <small className="af-field__attachment-meta">
            {formatFileSize(file.size)} · 图片
          </small>
        </div>
        <div className="af-field__attachment-actions">
          <AttachmentDownloadButton file={file} />
        </div>
      </div>
    );
  }
  if (isVideoFile(file)) {
    return <VideoAttachmentRow file={file} />;
  }
  return (
    <div className="af-field__attachment">
      <span className="af-field__attachment-thumb af-field__attachment-thumb--file" aria-hidden="true">
        <FileOutline />
      </span>
      <div className="af-field__attachment-main">
        <strong className="af-field__attachment-name" title={file.name}>
          {file.name ?? '附件'}
        </strong>
        <small className="af-field__attachment-meta">
          {formatFileSize(file.size)} · 文件
        </small>
      </div>
      <div className="af-field__attachment-actions">
        <AttachmentDownloadButton file={file} />
      </div>
    </div>
  );
}

function VideoAttachmentRow({ file }: { file: MediaFile }) {
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState('');
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [playingUrl, setPlayingUrl] = useState<string | null>(null);
  const [thumbUrl, setThumbUrl] = useState<string | null>(null);
  const [captureFailed, setCaptureFailed] = useState(false);
  const videoUrlRef = useRef<string | null>(null);
  const thumbUrlRef = useRef<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchMobileFileBlob(file.contentUrl)
      .then(async (blob) => {
        if (!alive) return;
        const objectUrl = URL.createObjectURL(blob);
        videoUrlRef.current = objectUrl;
        setVideoUrl(objectUrl);
        const coverUrl = await createVideoThumbUrl(blob);
        if (!alive) {
          if (coverUrl) revokeUrl(coverUrl);
          return;
        }
        if (coverUrl) {
          thumbUrlRef.current = coverUrl;
          setThumbUrl(coverUrl);
        } else {
          setCaptureFailed(true);
        }
      })
      .catch(() => {
        if (alive) setError(mediaErrorMessage(new Error('video load failed')));
      });
    return () => {
      alive = false;
      if (videoUrlRef.current) revokeUrl(videoUrlRef.current);
      if (thumbUrlRef.current) revokeUrl(thumbUrlRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [file.contentUrl]);

  const close = () => {
    setPlayingUrl(null);
    setError('');
  };

  const open = async () => {
    setError('');
    setOpening(true);
    try {
      if (!videoUrlRef.current) {
        const blob = await fetchMobileFileBlob(file.contentUrl);
        videoUrlRef.current = URL.createObjectURL(blob);
        setVideoUrl(videoUrlRef.current);
      }
      setPlayingUrl(videoUrlRef.current);
    } catch (videoError) {
      setError(mediaErrorMessage(videoError));
    } finally {
      setOpening(false);
    }
  };

  return (
    <div className="af-field__attachment">
      <button
        type="button"
        className="af-field__attachment-thumb af-field__attachment-thumb--video"
        aria-label={`播放 ${file.name ?? '视频'}`}
        disabled={opening}
        onClick={() => void open()}
      >
        {thumbUrl ? (
          <>
            <img src={thumbUrl} alt={file.name ?? '视频'} />
            <span className="af-field__attachment-play-badge" aria-hidden="true">
              <PlayOutline />
            </span>
          </>
        ) : (
          <PlayOutline aria-hidden="true" />
        )}
      </button>
      <div className="af-field__attachment-main">
        <strong className="af-field__attachment-name" title={file.name}>
          {file.name ?? '视频'}
        </strong>
        <small className="af-field__attachment-meta">
          {formatFileSize(file.size)} · 视频
          {error ? (
            <span className="af-field__media-error" role="alert">
              {' '}
              {error}
            </span>
          ) : null}
        </small>
      </div>
      <div className="af-field__attachment-actions">
        <AttachmentDownloadButton file={file} />
      </div>
      {playingUrl && (
        <MediaVideoPlayer url={playingUrl} name={file.name ?? '视频'} onClose={close} />
      )}
    </div>
  );
}

function MediaVideoPlayer({
  url,
  name,
  onClose,
}: {
  url: string;
  name: string;
  onClose(): void;
}) {
  return (
    <div className="af-media-viewer" role="dialog" aria-label={name} onClick={onClose}>
      <video
        src={url}
        controls
        autoPlay
        playsInline
        onClick={(event) => event.stopPropagation()}
      />
      <button type="button" className="af-media-viewer__close" onClick={onClose}>
        关闭
      </button>
    </div>
  );
}

function createImageThumbUrl(blob: Blob): Promise<string | null> {
  if (typeof createImageBitmap !== 'function') {
    return Promise.resolve(null);
  }
  return createImageBitmap(blob)
    .then((bitmap) => {
      const scale = Math.min(1, THUMB_MAX_SIZE / Math.max(bitmap.width, bitmap.height));
      const width = Math.max(1, Math.round(bitmap.width * scale));
      const height = Math.max(1, Math.round(bitmap.height * scale));
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext('2d');
      if (!context) {
        bitmap.close?.();
        return null;
      }
      context.drawImage(bitmap, 0, 0, width, height);
      bitmap.close?.();
      return canvasToBlobUrl(canvas);
    })
    .catch(() => null);
}

function canvasToBlobUrl(canvas: HTMLCanvasElement): Promise<string | null> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => resolve(null), 1500);
    canvas.toBlob(
      (blob) => {
        window.clearTimeout(timer);
        resolve(blob ? URL.createObjectURL(blob) : null);
      },
      'image/jpeg',
      0.82,
    );
  });
}

function createVideoThumbUrl(blob: Blob): Promise<string | null> {
  const tempUrl = URL.createObjectURL(blob);
  return new Promise((resolve) => {
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.preload = 'metadata';
    video.src = tempUrl;

    const finish = (thumbUrl: string | null) => {
      window.clearTimeout(timer);
      video.removeAttribute('src');
      video.load?.();
      revokeUrl(tempUrl);
      resolve(thumbUrl);
    };
    const timer = window.setTimeout(() => finish(null), 1500);

    video.onloadedmetadata = () => {
      try {
        const duration = Number.isFinite(video.duration) ? video.duration : 0;
        video.currentTime = duration > 0.2 ? 0.1 : 0;
        video.onseeked = () => {
          try {
            const sourceWidth = video.videoWidth || 320;
            const sourceHeight = video.videoHeight || 180;
            const scale = Math.min(1, THUMB_MAX_SIZE / Math.max(sourceWidth, sourceHeight));
            const canvas = document.createElement('canvas');
            canvas.width = Math.max(1, Math.round(sourceWidth * scale));
            canvas.height = Math.max(1, Math.round(sourceHeight * scale));
            const context = canvas.getContext('2d');
            if (!context) {
              finish(null);
              return;
            }
            context.drawImage(video, 0, 0, canvas.width, canvas.height);
            canvasToBlobUrl(canvas).then(finish);
          } catch {
            finish(null);
          }
        };
      } catch {
        finish(null);
      }
    };
    video.onerror = () => finish(null);
    video.load();
  });
}

function revokeUrl(url: string) {
  if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function') {
    URL.revokeObjectURL(url);
  }
}

function mediaErrorMessage(error: unknown) {
  if (isApiError(error) && error.status === 401) {
    return '请重新登录后预览';
  }
  if (isApiError(error) && error.body.code === 'FILE_STORAGE_FAILED') {
    return '附件原文件未写入 MinIO，请重新上传后再预览';
  }
  return '预览失败，请重试';
}
