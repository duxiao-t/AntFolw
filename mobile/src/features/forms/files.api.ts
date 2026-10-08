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

/**
 * 设计器给「用户选择」字段配的候选范围。参数名与桌面 /api/users 完全一致，
 * 这样两端对同一份字段配置的理解不会分叉。
 */
export type MobileUserScope = {
  /** 范围类型。空名单时也要带上它，服务端才知道该返回零候选而不是"不过滤"。 */
  scopeType?: string;
  deptId?: number;
  includeDescendants?: boolean;
  position?: string;
  leaderOnly?: boolean;
  userIds?: number[];
};

export async function searchMobileUsers(
  endpoint: string,
  keyword: string,
  scope: MobileUserScope = {},
): Promise<MobilePickerUser[]> {
  return apiRequest<MobilePickerUser[]>(withQuery(endpoint, { keyword: keyword.trim(), ...scope }));
}

export async function fetchMobileUser(endpoint: string, id: number): Promise<MobilePickerUser> {
  return apiRequest<MobilePickerUser>(`${endpoint.replace(/\?.*$/, '').replace(/\/$/, '')}/${id}`);
}

export async function fetchMobileDepartment(endpoint: string, id: number): Promise<MobilePickerDept> {
  return apiRequest<MobilePickerDept>(`${endpoint.replace(/\?.*$/, '').replace(/\/$/, '')}/${id}`);
}

export async function searchMobileDepartments(endpoint: string, keyword: string): Promise<MobilePickerDept[]> {
  return apiRequest<MobilePickerDept[]>(withQuery(endpoint, { keyword: keyword.trim() }));
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

/** 拼查询串；空值一律丢掉，免得出现 ?keyword=&deptId= 这种噪声。 */
function withQuery(endpoint: string, params: Record<string, unknown>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value == null || value === '') return;
    if (Array.isArray(value)) {
      if (value.length === 0) return;
      search.set(key, value.join(','));
      return;
    }
    search.set(key, String(value));
  });
  const query = search.toString();
  if (!query) return endpoint;
  return `${endpoint}${endpoint.includes('?') ? '&' : '?'}${query}`;
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
  // 30 分钟（1s/次）。转码并发只有几路，几十个视频一起传时排在后面的要等很久——服务端现在**不会**
  // 因为队列满就失败（它保持 PROCESSING 等空位），所以这里的上限不该比"真正处理完"更早放弃。
  for (let attempt = 0; attempt < 1800; attempt += 1) {
    await new Promise((resolve) => window.setTimeout(resolve, 1000));
    const current = await apiRequest<MobileFileDto>(
      `/api/mobile/files/${encodeURIComponent(file.id)}`,
    );
    // 服务端会给出可读原因（"转码队列忙"/"存储失败"…），别吞掉换成一句通用文案——
    // 用户看到的提示要能指导下一步（重试 / 换更小更短的视频）。
    if (current.status === 'FAILED') {
      throw new Error(current.failureReason || '视频处理失败，请重新上传');
    }
    if (current.status !== 'PROCESSING') {
      onProgress?.({ phase: 'done', progress: 100 });
      return current;
    }
  }
  throw new Error('视频处理超时，请稍后重试');
}

async function fetchMobileFileBlobWithAuth(contentUrl: string, retry = false): Promise<Blob> {
  const url = new URL(contentUrl, window.location.origin);
  if (url.origin !== window.location.origin
    || !/^\/api\/mobile\/files\/[^/]+\/content$/.test(url.pathname)
    || contentUrl.includes('\\')) {
    throw new Error('无效的附件地址');
  }
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
