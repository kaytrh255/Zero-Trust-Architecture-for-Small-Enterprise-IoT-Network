export type UserRole = 'ADMIN' | 'SECURITY_ANALYST' | 'USER' | 'DEVICE';
export type AssignableUserRole = Exclude<UserRole, 'DEVICE'>;
export type DeviceType = 'SENSOR' | 'CAMERA' | 'ACTUATOR' | 'GATEWAY';
export type DeviceStatus = 'ACTIVE' | 'INACTIVE' | 'BLOCKED' | 'REVOKED';
export type PolicyAction = 'READ' | 'WRITE' | 'EXECUTE';
export type PolicyEffect = 'ALLOW' | 'DENY';
export type DecisionOutcome = 'ALLOW' | 'DENY';
export type DecisionReason =
  | 'POLICY_ALLOW'
  | 'EXPLICIT_DENY'
  | 'NO_MATCHING_POLICY'
  | 'DEVICE_NOT_FOUND'
  | 'DEVICE_NOT_ACTIVE'
  | 'DEVICE_NOT_OWNED'
  | 'REQUESTER_ROLE_NOT_ALLOWED'
  | 'INVALID_DEVICE_CREDENTIAL'
  | 'REPLAYED_MESSAGE';
export type AccessChannel = 'API' | 'MQTT';

export interface UserProfile {
  id: number;
  username: string;
  fullName: string;
  role: UserRole;
  enabled: boolean;
}

export interface ManagedUserAccount extends UserProfile {
  mfaEnabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface UserAccountAudit {
  id: number;
  operation: 'ACCOUNT_UPDATED';
  targetUserId: number;
  targetUsername: string;
  previousRole: UserRole;
  newRole: UserRole;
  previousEnabled: boolean;
  newEnabled: boolean;
  actorUserId: number;
  actorUsername: string;
  changedAt: string;
}

export interface AuthResponse {
  mfaRequired: false;
  mfaEnrollmentRequired: false;
  accessToken: string;
  tokenType: 'Bearer';
  expiresInSeconds: number;
  user: UserProfile;
}

export interface MfaChallengeResponse {
  mfaRequired: true;
  mfaEnrollmentRequired: false;
  mfaToken: string;
  challengeExpiresInSeconds: number;
  accessToken: null;
  tokenType: null;
  expiresInSeconds: number;
  user: null;
}

export interface MfaEnrollmentChallengeResponse {
  mfaRequired: false;
  mfaEnrollmentRequired: true;
  enrollmentToken: string;
  challengeExpiresInSeconds: number;
  accessToken: null;
  tokenType: null;
  expiresInSeconds: number;
  user: null;
}

export interface MfaRequiredEnrollmentCompletion {
  session: AuthResponse;
  recoveryCodes: string[];
}

export type LoginResponse = AuthResponse | MfaChallengeResponse | MfaEnrollmentChallengeResponse;

export type MfaSecurityAuditOperation = 'ENROLLMENT_STARTED' | 'ENABLED' | 'DISABLED' | 'RECOVERY_CODE_USED' | 'RECOVERY_CODES_ROTATED' | 'ADMIN_MFA_RECOVERY';

export interface MfaStatus {
  enabled: boolean;
  enrollmentPending: boolean;
  enrollmentExpiresAt: string | null;
  recoveryCodesRemaining: number;
}

export interface MfaEnrollment {
  secret: string;
  otpauthUri: string;
  expiresAt: string;
}

export interface MfaRecoveryCodes {
  recoveryCodes: string[];
  status: MfaStatus;
}

export interface MfaSecurityAudit {
  id: number;
  userId: number;
  username: string;
  operation: MfaSecurityAuditOperation;
  changedAt: string;
  actorUserId: number | null;
  actorUsername: string | null;
}

export interface Device {
  id: number;
  deviceCode: string;
  deviceName: string;
  deviceType: DeviceType;
  ipAddress: string;
  mqttClientId: string;
  mqttSignatureEnabled: boolean;
  status: DeviceStatus;
  ownerId: number | null;
  ownerUsername: string | null;
  createdAt: string;
  lastSeenAt: string | null;
}

export type DeviceCredentialOperation = 'PROVISION' | 'ROTATE';

export interface DeviceCredentialAudit {
  id: number;
  deviceId: number;
  deviceCode: string;
  operation: DeviceCredentialOperation;
  previousSigningKeyFingerprint: string | null;
  newSigningKeyFingerprint: string;
  changedByUserId: number;
  changedByUsername: string;
  changedAt: string;
}

export interface DeviceProvisioningResponse {
  device: Device;
  mqttUsername: string;
  mqttPassword: string;
  mqttSigningPrivateKey: string;
}

export interface Policy {
  id: number;
  name: string;
  subject: string;
  resource: string;
  action: PolicyAction;
  effect: PolicyEffect;
  enabled: boolean;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AccessAudit {
  id: number;
  requesterId: number | null;
  requesterUsername: string;
  requesterRole: UserRole;
  channel: AccessChannel;
  deviceId: number | null;
  deviceCode: string;
  deviceType: DeviceType | null;
  deviceStatus: DeviceStatus | null;
  resource: string;
  action: PolicyAction;
  decision: DecisionOutcome;
  reason: DecisionReason;
  messageSequence: number | null;
  matchedPolicyId: number | null;
  matchedPolicyName: string | null;
  evaluatedAt: string;
}

export interface AuthenticationAttempt {
  id: number;
  attemptedUsername: string;
  authenticatedUserId: number | null;
  outcome: 'SUCCESS' | 'FAILURE';
  attemptedAt: string;
}

export interface TelemetrySample {
  id: number;
  deviceCode: string;
  deviceSequence: number;
  metric: string;
  value: number;
  unit: string;
  measuredAt: string;
  receivedAt: string;
}

export interface AccessDecision {
  auditId: number | null;
  decision: DecisionOutcome;
  reason: DecisionReason;
  deviceCode: string;
  resource: string;
  action: PolicyAction;
  matchedPolicyId: number | null;
  matchedPolicyName: string | null;
  evaluatedAt: string;
}

export interface ProtectedTelemetryResponse {
  accessDecision: AccessDecision;
  telemetry: TelemetrySample[];
}

export interface AuditPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface ApiErrorPayload {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  path?: string;
}
