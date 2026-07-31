import type { MobileFile } from '../../shared/api/types';
import { getAuthController } from '../../shared/api/auth';
import { ApiError, type ApiErrorBody } from '../../shared/api/errors';
import { apiRequest } from '../../shared/api/http';

export type MobileFileDto = MobileFile;
export type UploadProgressHandler = (progress: number) => void;

export type MobilePickerUser = {
  id: number;
  displayName: string;
  username?: string;
};

export type MobilePickerDept = {
  id: number;
  name: string;
};

export async function searchMobileUsers(endpoint: string, keyword: string): Promise<MobilePickerUser[]> {
  return apiRequest<MobilePickerUser[]>(withKeyword(endpoint, keyword));
}

export async function searchMobileDepartments(endpoint: string, keyword: string): Promise<MobilePickerDept[]> {
  return apiRequest<MobilePickerDept[]>(withKeyword(endpoint, keyword));
}

export async function uploadMobileFile(
  endpoint: string,
  file: File,
  onProgress?: UploadProgressHandler,
): Promise<MobileFileDto> {
  const formData = new FormData();
  formData.set('file', file);
  if (onProgress && typeof XMLHttpRequest !== 'undefined') {
    return uploadMobileFileWithProgress(endpoint, formData, onProgress);
  }
  return apiRequest<MobileFileDto>(endpoint, {
    method: 'POST',
    body: formData,
  });
}

export async function deleteMobileFile(fileId: string): Promise<void> {
  await apiRequest<void>(`/api/mobile/files/${encodeURIComponent(fileId)}`, {
    method: 'DELETE',
  });
}

function withKeyword(endpoint: string, keyword: string) {
  const trimmed = keyword.trim();
  if (!trimmed) {
    return endpoint;
  }
  const separator = endpoint.includes('?') ? '&' : '?';
  return `${endpoint}${separator}keyword=${encodeURIComponent(trimmed)}`;
}

function uploadMobileFileWithProgress(
  endpoint: string,
  formData: FormData,
  onProgress: UploadProgressHandler,
  retry = false,
): Promise<MobileFileDto> {
  const controller = getAuthController();
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', endpoint);
    xhr.withCredentials = true;
    xhr.setRequestHeader('Accept', 'application/json');
    const auth = controller.authorizationHeader();
    if (auth.Authorization) {
      xhr.setRequestHeader('Authorization', auth.Authorization);
    }
    if (retry) {
      xhr.setRequestHeader('X-AF-Retry', '1');
    }
    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable || event.total <= 0) {
        onProgress(35);
        return;
      }
      onProgress(Math.min(99, Math.max(8, Math.round((event.loaded / event.total) * 100))));
    };
    xhr.onerror = () => reject(new Error('网络异常，文件上传失败'));
    xhr.onabort = () => reject(new Error('文件上传已取消'));
    xhr.onload = () => {
      if (xhr.status === 401 && !retry && !controller.isAuthEndpoint(endpoint)) {
        void controller.refresh()
          .then(() => uploadMobileFileWithProgress(endpoint, formData, onProgress, true))
          .then(resolve, reject);
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText || '{}') as MobileFileDto);
        } catch {
          reject(new Error('文件上传响应解析失败'));
        }
        return;
      }
      reject(apiErrorFromXhr(xhr));
    };
    onProgress(8);
    xhr.send(formData);
  });
}

function apiErrorFromXhr(xhr: XMLHttpRequest) {
  let raw: Partial<ApiErrorBody> | null = null;
  try {
    raw = JSON.parse(xhr.responseText || 'null') as Partial<ApiErrorBody> | null;
  } catch {
    raw = null;
  }
  const retryAfter = Number.parseFloat(xhr.getResponseHeader('Retry-After') ?? '');
  return new ApiError(xhr.status, {
    code: typeof raw?.code === 'string' ? raw.code : `HTTP_${xhr.status}`,
    message: typeof raw?.message === 'string' ? raw.message : xhr.statusText || 'Request failed',
    traceId: typeof raw?.traceId === 'string' ? raw.traceId : undefined,
    fieldErrors: Array.isArray(raw?.fieldErrors)
      ? raw.fieldErrors.filter((entry): entry is { field: string; message: string } =>
          Boolean(entry && typeof entry.field === 'string' && typeof entry.message === 'string'),
        )
      : undefined,
    retryAfter: Number.isFinite(retryAfter) ? retryAfter : undefined,
  });
}
