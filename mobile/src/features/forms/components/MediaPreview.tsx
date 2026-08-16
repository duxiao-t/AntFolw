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
  const [imageFailed, setImageFailed] = useState<boolean[]>(() => imageFiles.map(() => false));
  const [viewerIndex, setViewerIndex] = useState(-1);

  useEffect(() => {
    let alive = true;
    const objectUrls = new Array<string | null>(imageFiles.length).fill(null);
    setImageUrls(imageFiles.map(() => null));
    setImageFailed(imageFiles.map(() => false));
    imageFiles.forEach((file, index) => {
      fetchMobileFileBlob(file.contentUrl)
        .then((blob) => {
          if (!alive) return;
          const objectUrl = URL.createObjectURL(blob);
          objectUrls[index] = objectUrl;
          setImageUrls((previous) => {
            const next = [...previous];
            next[index] = objectUrl;
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
      objectUrls.forEach((objectUrl) => {
        if (objectUrl) URL.revokeObjectURL(objectUrl);
      });
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [imageFiles.map((file) => file.contentUrl).join('|')]);

  const readyUrls = imageUrls.filter((url): url is string => url != null);
  return (
    <div className="af-field__attachments">
      {files.map((file, index) => (
        <AttachmentRow
          key={file.id ?? `${file.contentUrl}-${index}`}
          file={file}
          imageUrl={isImageFile(file) ? imageUrls[imageFiles.indexOf(file)] ?? null : null}
          imageFailed={
            isImageFile(file) ? imageFailed[imageFiles.indexOf(file)] ?? false : false
          }
          onOpenImage={
            isImageFile(file)
              ? () => {
                  const currentUrl = imageUrls[imageFiles.indexOf(file)];
                  const targetIndex = currentUrl ? readyUrls.indexOf(currentUrl) : -1;
                  if (targetIndex >= 0) setViewerIndex(targetIndex);
                }
              : undefined
          }
        />
      ))}
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
  imageFailed,
  onOpenImage,
}: {
  file: MediaFile;
  imageUrl: string | null;
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
          disabled={!imageUrl && !imageFailed}
          onClick={onOpenImage}
        >
          {imageUrl ? (
            <img src={imageUrl} alt={file.name ?? '图片'} />
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
  const [url, setUrl] = useState<string | null>(null);
  const objectUrlRef = useRef<string | null>(null);

  const close = () => {
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }
    setUrl(null);
    setError('');
  };

  const open = async () => {
    setError('');
    setOpening(true);
    try {
      const blob = await fetchMobileFileBlob(file.contentUrl);
      const objectUrl = URL.createObjectURL(blob);
      objectUrlRef.current = objectUrl;
      setUrl(objectUrl);
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
        {opening ? <span>加载中…</span> : <PlayOutline aria-hidden="true" />}
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
      {url ? (
        <MediaVideoPlayer url={url} name={file.name ?? '视频'} onClose={close} />
      ) : null}
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

function mediaErrorMessage(error: unknown) {
  if (isApiError(error) && error.status === 401) {
    return '请重新登录后预览';
  }
  if (isApiError(error) && error.body.code === 'FILE_STORAGE_FAILED') {
    return '附件原文件未写入 MinIO，请重新上传后再预览';
  }
  return '预览失败，请重试';
}
