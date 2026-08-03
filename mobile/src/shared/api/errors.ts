export interface ApiErrorBody {
  code: string;
  message: string;
  traceId?: string;
  fieldErrors?: ReadonlyArray<{ field: string; message: string }>;
  retryAfter?: number;
}

const KNOWN_ERROR_CODES = new Set<string>();

export class ApiError extends Error {
  override readonly name = 'ApiError';
  readonly status: number;
  readonly body: ApiErrorBody;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message);
    this.status = status;
    this.body = body;
  }
}

export interface ApiErrorFactoryOptions {
  defaultCode?: string;
}

export const ApiErrorFactory = {
  async fromResponse(response: Response, _options: ApiErrorFactoryOptions = {}): Promise<ApiError> {
    let body: ApiErrorBody;
    try {
      const raw = (await response.json()) as Partial<ApiErrorBody> | null;
      body = {
        code: typeof raw?.code === 'string' ? raw.code : `HTTP_${response.status}`,
        message: typeof raw?.message === 'string' ? raw.message : response.statusText || 'Request failed',
        traceId: typeof raw?.traceId === 'string' ? raw.traceId : undefined,
        fieldErrors: Array.isArray(raw?.fieldErrors)
          ? raw.fieldErrors
              .filter((entry): entry is { field: string; message: string } =>
                Boolean(entry && typeof entry.field === 'string' && typeof entry.message === 'string'),
              )
              .map((entry) => ({ field: entry.field, message: entry.message }))
          : undefined,
        retryAfter:
          typeof raw?.retryAfter === 'number'
            ? raw.retryAfter
            : Number.parseFloat(response.headers.get('Retry-After') ?? '') || undefined,
      };
    } catch {
      body = {
        code: `HTTP_${response.status}`,
        message: response.statusText || 'Request failed',
      };
    }
    return new ApiError(response.status, body);
  },
};

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

export function registerErrorCodes(codes: string[]): void {
  for (const code of codes) {
    KNOWN_ERROR_CODES.add(code);
  }
}

export function isKnownErrorCode(code: string): boolean {
  return KNOWN_ERROR_CODES.has(code);
}
