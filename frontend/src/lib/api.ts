import type {
  AccessAudit,
  ApiErrorPayload,
  AuditPage,
  AuthenticationAttempt,
  AuthResponse,
  Device,
  DeviceProvisioningResponse,
  DeviceStatus,
  DeviceType,
  Policy,
  PolicyAction,
  PolicyEffect,
  ProtectedTelemetryResponse,
  TelemetrySample,
  UserProfile,
} from '../types';

export class ApiError extends Error {
  status: number;
  code?: string;
  retryAfter?: string;

  constructor(message: string, status: number, code?: string, retryAfter?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

interface RequestOptions {
  token?: string | null;
  method?: string;
  body?: unknown;
  signal?: AbortSignal;
  allowForbidden?: boolean;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' });
  if (options.token) headers.set('Authorization', `Bearer ${options.token}`);
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');

  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      cache: 'no-store',
    });
  } catch {
    throw new ApiError('Could not reach the API. Check that the backend is running and try again.', 0);
  }

  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get('content-type') ?? '';
  const payload: unknown = contentType.includes('application/json')
    ? await response.json().catch(() => null)
    : await response.text().catch(() => '');

  if (!response.ok && !(options.allowForbidden && response.status === 403)) {
    if (response.status === 401 && options.token) window.setTimeout(() => window.dispatchEvent(new Event('zero-trust:unauthorized')), 0);
    const apiPayload = (payload && typeof payload === 'object' ? payload : {}) as ApiErrorPayload;
    const fallback = typeof payload === 'string' && payload ? payload : response.statusText || 'Request failed';
    throw new ApiError(
      apiPayload.message || apiPayload.error || fallback,
      response.status,
      apiPayload.error,
      response.headers.get('Retry-After') ?? undefined,
    );
  }
  return payload as T;
}

const queryString = (values: Record<string, string | number | boolean | null | undefined>) => {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== null && value !== undefined && value !== '') params.set(key, String(value));
  }
  const query = params.toString();
  return query ? `?${query}` : '';
};

export const api = {
  health: () => request<{ status: string }>('/actuator/health'),
  login: (username: string, password: string) =>
    request<AuthResponse>('/api/auth/login', { method: 'POST', body: { username, password } }),
  register: (username: string, password: string, fullName: string) =>
    request<UserProfile>('/api/auth/register', { method: 'POST', body: { username, password, fullName } }),
  currentUser: (token: string) => request<UserProfile>('/api/auth/me', { token }),

  devices: (token: string) => request<Device[]>('/api/devices', { token }),
  createDevice: (token: string, body: {
    deviceCode: string;
    deviceName: string;
    deviceType: DeviceType;
    ipAddress: string;
    mqttClientId: string;
  }) => request<DeviceProvisioningResponse>('/api/devices', { token, method: 'POST', body }),
  rotateDevice: (token: string, id: number) =>
    request<DeviceProvisioningResponse>(`/api/devices/${id}/credentials/rotate`, { token, method: 'POST' }),
  updateDeviceStatus: (token: string, id: number, status: DeviceStatus) =>
    request<Device>(`/api/devices/${id}/status`, { token, method: 'PATCH', body: { status } }),
  transferDevice: (token: string, id: number, ownerUsername: string) =>
    request<Device>(`/api/devices/${id}/owner`, { token, method: 'PATCH', body: { ownerUsername } }),
  revokeDevice: (token: string, id: number) =>
    request<void>(`/api/devices/${id}`, { token, method: 'DELETE' }),

  policies: (token: string) => request<Policy[]>('/api/policies', { token }),
  savePolicy: (token: string, body: {
    name: string;
    subject: string;
    resource: string;
    action: PolicyAction;
    effect: PolicyEffect;
    enabled: boolean;
    description: string;
  }, id?: number) => request<Policy>(id ? `/api/policies/${id}` : '/api/policies', {
    token,
    method: id ? 'PUT' : 'POST',
    body,
  }),
  deletePolicy: (token: string, id: number) =>
    request<void>(`/api/policies/${id}`, { token, method: 'DELETE' }),

  accessAudits: (token: string, page = 0, filters: {
    decision?: string;
    channel?: string;
    deviceCode?: string;
  } = {}) => request<AuditPage<AccessAudit>>(
    `/api/access/audits${queryString({ page, size: 20, ...filters })}`,
    { token },
  ),
  authenticationAudits: (token: string, page = 0, filters: {
    outcome?: string;
    username?: string;
  } = {}) => request<AuditPage<AuthenticationAttempt>>(
    `/api/auth/audits${queryString({ page, size: 20, ...filters })}`,
    { token },
  ),
  telemetry: (token: string) => request<TelemetrySample[]>('/api/telemetry', { token }),
  protectedTelemetry: (token: string, deviceCode: string) =>
    request<ProtectedTelemetryResponse>(`/api/resources/devices/${encodeURIComponent(deviceCode)}/telemetry`, { token, allowForbidden: true }),
};
