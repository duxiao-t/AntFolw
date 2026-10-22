import { DeleteOutline, FileOutline, PictureOutline } from 'antd-mobile-icons';
import type { UploadItem } from './fileUploadValue';
import {
  fileSizeLabel,
  fileTypeClass,
  fileTypeLabel,
  isImageUploadItem,
  progressPercent,
  progressTone,
  statusLabel,
} from './fileUploadLabels';

type FileUploadItemRowProps = {
  item: UploadItem;
  previewUrl: string;
  onRetry(item: UploadItem): void;
  onDelete(localId: string): void;
};

/** 单条上传记录：缩略图/类型图标 + 文件名 + 状态 + 进度 + 重试/删除。 */
export function FileUploadItemRow({ item, previewUrl, onRetry, onDelete }: FileUploadItemRowProps) {
  return (
    <div className={`upload-file-row af-upload-list__item af-upload-list__item--${item.status}`}>
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
        {item.status === 'failed'
          || (item.status === 'processing' && item.remote?.status === 'PROCESSING') ? (
          <button
            type="button"
            className="af-link-button af-upload-list__retry"
            aria-label={`${item.remote?.status === 'PROCESSING' ? '继续等待' : '重试'} ${item.file.name}`}
            onClick={() => onRetry(item)}
          >
            {item.remote?.status === 'PROCESSING' ? '继续等待' : '重试'}
          </button>
        ) : null}
        <button
          type="button"
          className="af-link-button af-upload-list__delete"
          aria-label={`删除 ${item.file.name}`}
          disabled={item.status === 'deleting'}
          onClick={() => onDelete(item.localId)}
        >
          <DeleteOutline aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
