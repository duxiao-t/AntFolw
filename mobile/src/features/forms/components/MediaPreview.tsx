import { ImageViewer } from 'antd-mobile';
import { EyeOutline, PlayOutline } from 'antd-mobile-icons';
import { useEffect, useRef, useState } from 'react';
import { isApiError } from '../../../shared/api/errors';
import { fetchMobileFileBlob } from '../files.api';

export type MediaFile = {
  id?: string;
  name?: string;
  contentType?: string;
  contentUrl: string;
};

const IMAGE_EXTENSION = /\.(jpe?g|png|gif|webp|bmp|heic|heif|avif)$/i;
const VIDEO_EXTENSION = /\.(mp4|mov|3gp|3gpp|webm|m4v)$/i;

export function isImageFile(file: MediaFile) {
  return /^image\//i.test(file.contentType ?? '') || IMAGE_EXTENSION.test(file.name ?? '');
}

export function isVideoFile(file: MediaFile) {
  return /^video\//i.test(file.contentType ?? '') || VIDEO_EXTENSION.test(file.name ?? '');
}

/** 审批只读态：图片缩略图网格（多图滑动）、视频播放瓦片、其他附件仅名称。 */
export function ReadonlyMediaList({ files }: { files: MediaFile[] }) {
  const images = files.filter(isImageFile);
  const videos = files.filter(isVideoFile);
  const others = files.filter((file) => !isImageFile(file) && !isVideoFile(file));

  return (
    <div className="af-field__media-list">
      {images.length > 0 ? <ReadonlyImages files={images} /> : null}
      {videos.length > 0 ? (
        <div className="af-field__media-grid">
          {videos.map((file, index) => (
            <ReadonlyVideoTile key={file.id ?? index} file={file} />
          ))}
        </div>
      ) : null}
      {others.length > 0 ? (
        <div className="af-field__media-names">
          {others.map((file) => (
            <div key={file.id ?? file.name} className="af-field__summary">
              {file.name ?? '附件'}
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

/** 附件面板：图片/视频显示「预览」按钮，普通附件不渲染。 */
export function MediaPreviewButton({ file }: { file: MediaFile }) {
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState('');
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const objectUrlRef = useRef<string | null>(null);

  if (!isImageFile(file) && !isVideoFile(file)) {
    return null;
  }

  const close = () => {
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }
    setImageUrl(null);
    setVideoUrl(null);
    setError('');
  };

  const open = async () => {
    setError('');
    setOpening(true);
    try {
      const blob = await fetchMobileFileBlob(file.contentUrl);
      const objectUrl = URL.createObjectURL(blob);
      objectUrlRef.current = objectUrl;
      if (isVideoFile(file)) {
        setVideoUrl(objectUrl);
      } else {
        setImageUrl(objectUrl);
      }
    } catch (previewError) {
      setError(mediaErrorMessage(previewError));
    } finally {
      setOpening(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className="attachment-file__preview"
        aria-label={`预览${file.name ?? '附件'}`}
        disabled={opening}
        onClick={() => void open()}
      >
        {opening ? <span>加载中</span> : <EyeOutline aria-hidden="true" />}
      </button>
      {error ? (
        <small className="attachment-file__download-error" role="alert">
          {error}
        </small>
      ) : null}
      {imageUrl ? (
        <ImageViewer.Multi images={[imageUrl]} visible onClose={close} />
      ) : null}
      {videoUrl ? (
        <MediaVideoPlayer url={videoUrl} name={file.name ?? '视频'} onClose={close} />
      ) : null}
    </>
  );
}

function ReadonlyImages({ files }: { files: MediaFile[] }) {
  const [urls, setUrls] = useState<Array<string | null>>(() => files.map(() => null));
  const [failed, setFailed] = useState<boolean[]>(() => files.map(() => false));
  const [viewerIndex, setViewerIndex] = useState(-1);

  useEffect(() => {
    let alive = true;
    const objectUrls = new Array<string | null>(files.length).fill(null);
    setUrls(files.map(() => null));
    setFailed(files.map(() => false));
    files.forEach((file, index) => {
      fetchMobileFileBlob(file.contentUrl)
        .then((blob) => {
          if (!alive) return;
          const objectUrl = URL.createObjectURL(blob);
          objectUrls[index] = objectUrl;
          setUrls((previous) => {
            const next = [...previous];
            next[index] = objectUrl;
            return next;
          });
        })
        .catch(() => {
          if (alive) {
            setFailed((previous) => {
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
  }, [files.map((file) => file.contentUrl).join('|')]);

  const readyUrls = urls.filter((url): url is string => url != null);
  return (
    <>
      <div className="af-field__media-grid">
        {files.map((file, index) => (
          <button
            key={file.id ?? index}
            type="button"
            className="af-field__media-thumb"
            aria-label={`预览 ${file.name ?? '图片'}`}
            disabled={urls[index] == null && !failed[index]}
            onClick={() => {
              const currentUrl = urls[index];
              const targetIndex = currentUrl ? readyUrls.indexOf(currentUrl) : -1;
              if (targetIndex >= 0) setViewerIndex(targetIndex);
            }}
          >
            {urls[index] ? (
              <img src={urls[index] as string} alt={file.name ?? '图片'} />
            ) : (
              <span>{failed[index] ? '预览失败' : '加载中…'}</span>
            )}
          </button>
        ))}
      </div>
      {viewerIndex >= 0 && readyUrls.length > 0 ? (
        <ImageViewer.Multi
          images={readyUrls}
          defaultIndex={viewerIndex}
          visible
          onClose={() => setViewerIndex(-1)}
        />
      ) : null}
    </>
  );
}

function ReadonlyVideoTile({ file }: { file: MediaFile }) {
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
    <div className="af-field__media-video">
      <button
        type="button"
        className="af-field__media-video-btn"
        aria-label={`播放 ${file.name ?? '视频'}`}
        disabled={opening}
        onClick={() => void open()}
      >
        <PlayOutline aria-hidden="true" />
        <span>{opening ? '加载中…' : file.name ?? '视频'}</span>
      </button>
      {error ? (
        <small className="af-field__media-error" role="alert">
          {error}
        </small>
      ) : null}
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
