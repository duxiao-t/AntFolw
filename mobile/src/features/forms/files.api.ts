import type { MobileFile } from '../../shared/api/types';
import { getAuthController } from '../../shared/api/auth';
import { ApiError, ApiErrorFactory, type ApiErrorBody } from '../../shared/api/errors';
import { apiRequest } from '../../shared/api/http';

export type MobileFileDto = MobileFile;
export type UploadProgressEvent =
  | { phase: 'uploading'; progress: number }
  | { phase: 'processing'; progress: number }
  | { phase: 'done'; progress: 100 };
export type UploadProgressHandler = (event: UploadProgressEvent) => void;

export type MobilePickerUser = {
  id: number;
  displayName: string;
  username?: string;
  department?: string | null;
  employeeNo?: string | null;
};

export type MobilePickerDept = {
  id: number;
  name: string;
};

export async function searchMobileUsers(endpoint: string, keyword: string): Promise<MobilePickerUser[]> {
  return apiRequest<MobilePickerUser[]>(withKeyword(endpoint, keyword));
}

export async function fetchMobileUser(endpoint: string, id: number): Promise<MobilePickerUser> {
  return apiRequest<MobilePickerUser>(`${endpoint.replace(/\?.*$/, '').replace(/\/$/, '')}/${id}`);
}

export async function searchMobileDepartments(endpoint: string, keyword: string): Promise<MobilePickerDept[]> {
  return apiRequest<MobilePickerDept[]>(withKeyword(endpoint, keyword));
}

export async function uploadMobileFile(
  endpoint: string,
  file: File,
  onProgress?: UploadProgressHandler,
  extraFields?: Record<string, string>,
): Promise<MobileFileDto> {
  const formData = new FormData();
  formData.set('file', file);
  for (const [key, value] of Object.entries(extraFields ?? {})) {
    if (value != null && value !== '') {
      formData.set(key, value);
    }
  }
  if (onProgress && typeof XMLHttpRequest !== 'undefined') {
    const uploaded = await uploadMobileFileWithProgress(endpoint, formData, onProgress);
    return waitForProcessedFile(uploaded, onProgress);
  }
  const uploaded = await apiRequest<MobileFileDto>(endpoint, {
    method: 'POST',
    body: formData,
  });
  return waitForProcessedFile(uploaded, onProgress);
}

export async function deleteMobileFile(fileId: string): Promise<void> {
  await apiRequest<void>(`/api/mobile/files/${encodeURIComponent(fileId)}`, {
    method: 'DELETE',
  });
}

export async function fetchMobileFileBlob(contentUrl: string): Promise<Blob> {
  return fetchMobileFileBlobWithAuth(contentUrl);
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
  progressState: UploadProgressState = createUploadProgressState(),
): Promise<MobileFileDto> {
  const controller = getAuthController();
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    let settled = false;
    let fallbackTimer: ReturnType<typeof setInterval> | undefined;
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
        return;
      }
      if (event.loaded >= event.total) {
        emitProgress(onProgress, progressState, { phase: 'uploading', progress: 95 });
        emitProgress(onProgress, progressState, { phase: 'processing', progress: 96 });
        return;
      }
      const progress = Math.min(95, Math.max(1, Math.round((event.loaded / event.total) * 100)));
      emitProgress(onProgress, progressState, { phase: 'uploading', progress });
    };
    xhr.upload.onload = () => {
      emitProgress(onProgress, progressState, { phase: 'processing', progress: 96 });
    };
    xhr.onerror = () => rejectOnce(new Error('网络异常，文件上传失败'));
    xhr.onabort = () => rejectOnce(new Error('文件上传已取消'));
    xhr.onload = handleResponse;
    xhr.onloadend = () => {
      if (xhr.readyState === 4) {
        handleResponse();
      }
    };
    xhr.onreadystatechange = () => {
      if (xhr.readyState === 4) {
        handleResponse();
      }
    };
    function handleResponse() {
      if (settled) {
        return;
      }
      settled = true;
      clearFallback();
      if (xhr.status === 401 && !retry && !controller.isAuthEndpoint(endpoint)) {
        void controller.refresh()
          .then(() => uploadMobileFileWithProgress(endpoint, formData, onProgress, true, progressState))
          .then(resolve, reject);
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const metadata = JSON.parse(xhr.responseText || '{}') as MobileFileDto;
          resolve(metadata);
        } catch {
          reject(new Error('文件上传响应解析失败'));
        }
        return;
      }
      reject(apiErrorFromXhr(xhr));
    }
    function rejectOnce(error: Error) {
      if (settled) {
        return;
      }
      settled = true;
      clearFallback();
      reject(error);
    }
    function clearFallback() {
      if (fallbackTimer !== undefined) {
        clearInterval(fallbackTimer);
        fallbackTimer = undefined;
      }
    }
    function startFallback() {
      // ponytail: bounded synthetic progress for WebViews that omit upload byte events;
      // replace with streaming transport progress if the platform exposes it reliably.
      fallbackTimer = setInterval(() => {
        if (settled || progressState.phase !== 'uploading' || progressState.progress >= 90) return;
        emitProgress(onProgress, progressState, {
          phase: 'uploading',
          progress: Math.min(90, Math.max(1, progressState.progress + 3)),
        });
      }, 500);
    }
    if (!retry) {
      emitProgress(onProgress, progressState, { phase: 'uploading', progress: 0 });
    }
    startFallback();
    xhr.send(formData);
  });
}

async function waitForProcessedFile(file: MobileFileDto, onProgress?: UploadProgressHandler) {
  if (file.status !== 'PROCESSING') {
    onProgress?.({ phase: 'done', progress: 100 });
    return file;
  }
  onProgress?.({ phase: 'processing', progress: 97 });
  for (let attempt = 0; attempt < 600; attempt += 1) {
    await new Promise((resolve) => window.setTimeout(resolve, 1000));
    const current = await apiRequest<MobileFileDto>(
      `/api/mobile/files/${encodeURIComponent(file.id)}`,
    );
    if (current.status === 'FAILED') throw new Error('视频处理失败，请重新上传');
    if (current.status !== 'PROCESSING') {
      onProgress?.({ phase: 'done', progress: 100 });
      return current;
    }
  }
  throw new Error('视频处理超时，请稍后重试');
}

async function fetchMobileFileBlobWithAuth(contentUrl: string, retry = false): Promise<Blob> {
  const controller = getAuthController();
  const headers = new Headers({ Accept: '*/*' });
  const auth = controller.authorizationHeader();
  if (auth.Authorization) {
    headers.set('Authorization', auth.Authorization);
  }
  if (retry) {
    headers.set('X-AF-Retry', '1');
  }
  const response = await fetch(contentUrl, {
    headers,
    credentials: 'include',
  });
  if (response.status === 401 && !retry && !controller.isAuthEndpoint(contentUrl)) {
    await controller.refresh();
    return fetchMobileFileBlobWithAuth(contentUrl, true);
  }
  if (!response.ok) {
    throw await ApiErrorFactory.fromResponse(response);
  }
  return response.blob();
}

type UploadProgressState = {
  phase: UploadProgressEvent['phase'];
  progress: number;
};

function createUploadProgressState(): UploadProgressState {
  return {
    phase: 'uploading',
    progress: -1,
  };
}

function emitProgress(
  onProgress: UploadProgressHandler,
  state: UploadProgressState,
  event: UploadProgressEvent,
) {
  if (state.phase === 'done') {
    return;
  }
  if (event.phase === 'done') {
    state.phase = 'done';
    state.progress = 100;
    onProgress(event);
    return;
  }
  if (state.phase === 'processing' && event.phase === 'uploading') {
    return;
  }
  const progress = Math.max(state.progress, event.progress);
  if (event.phase === state.phase && progress === state.progress) {
    return;
  }
  state.phase = event.phase;
  state.progress = progress;
  onProgress({ phase: event.phase, progress } as UploadProgressEvent);
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
