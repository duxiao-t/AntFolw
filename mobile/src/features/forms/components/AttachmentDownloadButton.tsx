import { useState } from 'react';
import { isApiError } from '../../../shared/api/errors';
import { fetchMobileFileBlob } from '../files.api';

const DOWNLOAD_OBJECT_URL_REVOKE_DELAY_MS = 60_000;

export type DownloadableAttachment = {
  id?: string;
  name?: string;
  contentUrl: string;
  url?: string;
};

type AttachmentDownloadButtonProps = {
  file: DownloadableAttachment;
};

export function AttachmentDownloadButton({ file }: AttachmentDownloadButtonProps) {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState('');

  return (
    <div className="attachment-file__download">
      <button
        type="button"
        aria-label={`下载${file.name}`}
        disabled={downloading}
        onClick={() => void downloadFile()}
      >
        <DownloadIcon />
      </button>
      {error ? <small className="attachment-file__download-error" role="alert">{error}</small> : null}
    </div>
  );

  async function downloadFile() {
    const contentUrl = file.contentUrl || file.url || '';
    if (!contentUrl) {
      setError('附件地址为空');
      return;
    }
    setDownloading(true);
    setError('');
    let objectUrl = '';
    try {
      const blob = await fetchMobileFileBlob(contentUrl);
      objectUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = objectUrl;
      link.download = file.name || '附件';
      link.rel = 'noopener';
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), DOWNLOAD_OBJECT_URL_REVOKE_DELAY_MS);
    } catch (downloadError) {
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
      setError(downloadErrorMessage(downloadError));
    } finally {
      setDownloading(false);
    }
  }
}

function downloadErrorMessage(error: unknown) {
  if (isApiError(error) && error.status === 401) {
    return '请重新登录后下载';
  }
  if (isApiError(error) && error.body.code === 'FILE_STORAGE_FAILED') {
    return '附件原文件未写入 MinIO，请重新上传后再下载';
  }
  return '下载失败，请重试';
}

function DownloadIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 3v12" />
      <path d="m7 10 5 5 5-5" />
      <path d="M5 21h14" />
    </svg>
  );
}
