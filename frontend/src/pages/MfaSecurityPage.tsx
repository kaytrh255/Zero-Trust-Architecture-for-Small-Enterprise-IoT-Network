import { useEffect, useState, type FormEvent } from 'react';
import { Clipboard, KeyRound, LockKeyhole, RefreshCw, ShieldAlert, ShieldCheck } from 'lucide-react';
import { ApiError, api } from '../lib/api';
import type { AuditPage, MfaEnrollment, MfaRecoveryCodes, MfaSecurityAudit, MfaStatus, UserProfile } from '../types';
import type { Notify } from '../App';
import { Badge, Button, LoadingState, PageHeading, Panel, SectionTitle } from '../components/ui';

const dateTime = (value: string) => new Date(value).toLocaleString(undefined, {
  month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
});

const actionError = (cause: unknown, fallback: string) => {
  if (cause instanceof ApiError && cause.status === 429 && cause.retryAfter) {
    return `${cause.message} Try again in ${cause.retryAfter} seconds.`;
  }
  return cause instanceof Error ? cause.message : fallback;
};

export function MfaSecurityPage({ token, user, notify, onLogout }: {
  token: string;
  user: UserProfile;
  notify: Notify;
  onLogout: () => void;
}) {
  const [status, setStatus] = useState<MfaStatus | null>(null);
  const [audits, setAudits] = useState<AuditPage<MfaSecurityAudit> | null>(null);
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [completedChange, setCompletedChange] = useState<'enabled' | 'disabled' | 'rotated' | null>(null);
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [rotationPassword, setRotationPassword] = useState('');
  const [rotationCode, setRotationCode] = useState('');
  const [recoveryDisablePassword, setRecoveryDisablePassword] = useState('');
  const [recoveryDisableCode, setRecoveryDisableCode] = useState('');
  const [adminTargetUsername, setAdminTargetUsername] = useState('');
  const [adminRecoveryPassword, setAdminRecoveryPassword] = useState('');
  const [adminRecoveryCode, setAdminRecoveryCode] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copyMessage, setCopyMessage] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    Promise.all([api.mfaStatus(token), api.mfaSecurityAudits(token)])
      .then(([currentStatus, auditPage]) => {
        if (!active) return;
        setStatus(currentStatus);
        setAudits(auditPage);
      })
      .catch((cause: unknown) => {
        if (active) notify({ tone: 'error', title: 'Could not load MFA security settings', detail: cause instanceof Error ? cause.message : 'Try again.' });
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token, reload, notify]);

  async function beginEnrollment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      const result = await api.beginMfaEnrollment(token, password);
      setEnrollment(result);
      setPassword('');
      setCode('');
      setCopyMessage('');
    } catch (cause) {
      setError(actionError(cause, 'Could not start MFA enrollment.'));
    } finally {
      setBusy(false);
    }
  }

  async function confirmEnrollment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      const result: MfaRecoveryCodes = await api.confirmMfaEnrollment(token, code.trim());
      setStatus(result.status);
      setEnrollment(null);
      setRecoveryCodes(result.recoveryCodes);
      setCompletedChange('enabled');
      setPassword('');
      setCode('');
    } catch (cause) {
      setError(actionError(cause, 'Could not verify the authenticator code.'));
    } finally {
      setBusy(false);
    }
  }

  async function disableMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      const result = await api.disableMfa(token, password, code.trim());
      setStatus(result);
      setEnrollment(null);
      setCompletedChange('disabled');
      setPassword('');
      setCode('');
    } catch (cause) {
      setError(actionError(cause, 'Could not disable MFA.'));
    } finally {
      setBusy(false);
    }
  }

  async function disableMfaWithRecoveryCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      const result = await api.disableMfaWithRecoveryCode(
        token,
        recoveryDisablePassword,
        recoveryDisableCode.trim(),
      );
      setStatus(result);
      setEnrollment(null);
      setCompletedChange('disabled');
      setRecoveryDisablePassword('');
      setRecoveryDisableCode('');
    } catch (cause) {
      setError(actionError(cause, 'Could not recover MFA with the recovery code.'));
    } finally {
      setBusy(false);
    }
  }

  async function rotateRecoveryCodes(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      const result: MfaRecoveryCodes = await api.rotateMfaRecoveryCodes(token, rotationPassword, rotationCode.trim());
      setStatus(result.status);
      setRecoveryCodes(result.recoveryCodes);
      setCompletedChange('rotated');
      setRotationPassword('');
      setRotationCode('');
      setCopyMessage('');
      setReload((value) => value + 1);
    } catch (cause) {
      setError(actionError(cause, 'Could not rotate recovery codes.'));
    } finally {
      setBusy(false);
    }
  }

  async function recoverAdminMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setBusy(true);
    const targetUsername = adminTargetUsername.trim();
    try {
      await api.adminRecoverMfa(token, targetUsername, adminRecoveryPassword, adminRecoveryCode.trim());
      setAdminTargetUsername('');
      setAdminRecoveryPassword('');
      setAdminRecoveryCode('');
      setReload((value) => value + 1);
      notify({
        tone: 'success',
        title: 'Administrator-assisted MFA recovery completed',
        detail: `MFA was disabled and existing sessions were revoked for ${targetUsername}.`,
      });
    } catch (cause) {
      setError(actionError(cause, 'Could not recover the target account.'));
    } finally {
      setBusy(false);
    }
  }

  function dismissRecoveryCodes() {
    setRecoveryCodes(null);
    setCopyMessage('');
    if (completedChange === 'rotated') setCompletedChange(null);
  }

  async function copy(value: string, label: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopyMessage(`${label} copied.`);
    } catch {
      setCopyMessage('Clipboard access was blocked. Select and copy the text manually.');
    }
  }

  return (
    <>
      <PageHeading
        eyebrow="IDENTITY PROTECTION / MFA"
        title="Account security"
        description="Protect administrator and security-analyst sign-ins with an authenticator app, single-use recovery codes, and an auditable MFA history."
        action={<Button variant="secondary" icon={RefreshCw} onClick={() => setReload((value) => value + 1)} disabled={loading || busy}>Refresh</Button>}
      />
      {loading ? <LoadingState label="Loading MFA settings" /> : status && <>
        <div className="mfa-overview-strip">
          <div><span className="mfa-overview-icon"><LockKeyhole size={16} /></span><span><small>AUTHENTICATOR MFA</small><b>{status.enabled ? 'Enabled' : 'Not enabled'}</b></span></div>
          <i />
          <div><span className="mfa-overview-icon mfa-overview-icon-blue"><KeyRound size={16} /></span><span><small>RECOVERY CODES LEFT</small><b>{status.enabled ? status.recoveryCodesRemaining : '—'}</b></span></div>
          <i />
          <span className="mfa-overview-note"><ShieldCheck size={14} /> TOTP codes are single-use per time window</span>
        </div>

        <div className="mfa-content-grid">
          <Panel className="mfa-settings-panel">
            <div className="mfa-panel-heading"><SectionTitle title="Multi-factor authentication" subtitle={`Signed in as ${user.username}`} /><Badge tone={status.enabled ? 'green' : 'amber'} dot>{status.enabled ? 'PROTECTED' : 'ACTION NEEDED'}</Badge></div>
            {error && <div className="form-error mfa-form-error" role="alert">{error}</div>}
            {status.enabled ? <>
              <div className="mfa-state-callout"><ShieldCheck size={16} /><span><b>Authenticator verification is required at sign-in.</b><small>{status.recoveryCodesRemaining} one-time recovery code{status.recoveryCodesRemaining === 1 ? '' : 's'} remain. An unused recovery code can disable MFA if your authenticator is unavailable.</small></span></div>
              <form className="mfa-action-form" onSubmit={rotateRecoveryCodes}>
                <h3>Replace recovery codes</h3>
                <p>Enter your password and a fresh authenticator code. This invalidates every previous recovery code without ending your current session.</p>
                <label>Confirm account password<input type="password" autoComplete="current-password" maxLength={72} required value={rotationPassword} onChange={(event) => setRotationPassword(event.target.value)} /></label>
                <label>Authenticator code<input inputMode="numeric" autoComplete="one-time-code" maxLength={6} pattern="[0-9]{6}" required value={rotationCode} onChange={(event) => setRotationCode(event.target.value)} placeholder="000000" /></label>
                <Button variant="secondary" type="submit" disabled={busy}>{busy ? 'Verifying…' : 'Rotate recovery codes'}</Button>
              </form>
              {completedChange !== 'enabled' && completedChange !== 'disabled' && <form className="mfa-action-form" onSubmit={disableMfa}>
                <h3>Disable MFA</h3>
                <p>Confirm your account password and a fresh authenticator code. This invalidates every current access token.</p>
                <label>Password<input type="password" autoComplete="current-password" maxLength={72} required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
                <label>Authenticator code<input inputMode="numeric" autoComplete="one-time-code" maxLength={6} pattern="[0-9]{6}" required value={code} onChange={(event) => setCode(event.target.value)} placeholder="000000" /></label>
                <Button variant="danger" type="submit" disabled={busy}>{busy ? 'Verifying…' : 'Disable MFA'}</Button>
              </form>}
              {completedChange !== 'enabled' && completedChange !== 'disabled' && (status.recoveryCodesRemaining > 0 ? <form className="mfa-action-form" onSubmit={disableMfaWithRecoveryCode}>
                <h3>Lost access to your authenticator?</h3>
                <p>Confirm your account password and enter an unused recovery code. The code is consumed, all remaining recovery codes are invalidated, and every active session is revoked. With mandatory MFA enabled, your next sign-in will require a new authenticator setup.</p>
                <label>Confirm account password<input type="password" autoComplete="current-password" maxLength={72} required value={recoveryDisablePassword} onChange={(event) => setRecoveryDisablePassword(event.target.value)} /></label>
                <label>Unused recovery code<input type="text" autoComplete="off" spellCheck={false} maxLength={64} required value={recoveryDisableCode} onChange={(event) => setRecoveryDisableCode(event.target.value)} placeholder="ABCD-EF01-…" /></label>
                <Button variant="danger" type="submit" disabled={busy}>{busy ? 'Verifying…' : 'Disable MFA with recovery code'}</Button>
              </form> : <div className="mfa-state-callout mfa-state-warning"><ShieldAlert size={16} /><span><b>No unused recovery code remains.</b><small>Use your authenticator to disable MFA. If both factors are unavailable, ask a different MFA-enabled ADMIN to recover this account.</small></span></div>)}
              {user.role === 'ADMIN' && <form className="mfa-action-form mfa-admin-recovery-form" onSubmit={recoverAdminMfa}>
                <h3>Recover another privileged account</h3>
                <p>Only a different, enabled ADMIN or SECURITY_ANALYST account with MFA enabled can be recovered. This disables the target’s MFA, deletes its recovery codes and pending challenges, and revokes its existing tokens. With mandatory MFA enabled, the target must enroll again at next sign-in.</p>
                <label>Target username<input type="text" autoComplete="off" maxLength={50} required value={adminTargetUsername} onChange={(event) => setAdminTargetUsername(event.target.value)} /></label>
                <label>Your administrator password<input type="password" autoComplete="current-password" maxLength={72} required value={adminRecoveryPassword} onChange={(event) => setAdminRecoveryPassword(event.target.value)} /></label>
                <label>Your fresh authenticator code<input inputMode="numeric" autoComplete="one-time-code" maxLength={6} pattern="[0-9]{6}" required value={adminRecoveryCode} onChange={(event) => setAdminRecoveryCode(event.target.value)} placeholder="000000" /></label>
                <Button variant="danger" type="submit" disabled={busy}>{busy ? 'Verifying…' : 'Recover target MFA'}</Button>
              </form>}
            </> : enrollment ? <>
              <div className="mfa-state-callout"><KeyRound size={16} /><span><b>Save the setup key in your authenticator app.</b><small>Enrollment expires {dateTime(enrollment.expiresAt)}. Use “enter setup key” in an app such as Google Authenticator, Microsoft Authenticator, or 1Password.</small></span></div>
              <div className="mfa-secret-box"><div><small>MANUAL SETUP KEY · BASE32</small><code>{enrollment.secret}</code></div><Button variant="secondary" size="sm" icon={Clipboard} onClick={() => void copy(enrollment.secret, 'Setup key')}>Copy key</Button></div>
              <details className="mfa-uri-details"><summary>Show authenticator setup URI</summary><code>{enrollment.otpauthUri}</code><Button variant="secondary" size="sm" icon={Clipboard} onClick={() => void copy(enrollment.otpauthUri, 'Setup URI')}>Copy URI</Button></details>
              <form className="mfa-action-form" onSubmit={confirmEnrollment}>
                <h3>Verify and enable</h3>
                <p>Enter the current 6-digit code shown by your authenticator to finish setup.</p>
                <label>Authenticator code<input inputMode="numeric" autoComplete="one-time-code" maxLength={6} pattern="[0-9]{6}" required value={code} onChange={(event) => setCode(event.target.value)} placeholder="000000" /></label>
                <Button type="submit" disabled={busy}>{busy ? 'Verifying…' : 'Enable MFA'}</Button>
              </form>
              {copyMessage && <p className="mfa-copy-message" role="status">{copyMessage}</p>}
            </> : <>
              <div className="mfa-state-callout mfa-state-warning"><ShieldAlert size={16} /><span><b>Privileged accounts should use a second factor.</b><small>We’ll verify your password, then show a setup key. MFA activates only after a valid authenticator code is confirmed.</small></span></div>
              {status.enrollmentPending && <p className="mfa-pending-note">A previous setup expired or was interrupted. Start again to generate a fresh key.</p>}
              <form className="mfa-action-form" onSubmit={beginEnrollment}>
                <label>Confirm account password<input type="password" autoComplete="current-password" maxLength={72} required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
                <Button type="submit" disabled={busy}>{busy ? 'Preparing setup…' : status.enrollmentPending ? 'Restart MFA setup' : 'Set up authenticator'}</Button>
              </form>
              {copyMessage && <p className="mfa-copy-message" role="status">{copyMessage}</p>}
            </>}
          </Panel>

          <Panel className="mfa-audit-panel">
            <SectionTitle title="MFA security history" subtitle="Append-only changes · newest first" />
            {!audits?.content.length ? <div className="mfa-audit-empty">MFA enrollment, recovery-code use/rotation, admin-assisted recovery, and disable events will appear here.</div> : <div className="mfa-audit-list">
              {audits.content.map((entry) => <div className="mfa-audit-item" key={entry.id}>
                <span className={`mfa-audit-marker mfa-audit-${entry.operation.toLowerCase()}`}><ShieldCheck size={14} /></span>
                  <span className="mfa-audit-copy"><b>{entry.operation.replaceAll('_', ' ')}</b><small>{entry.username}{entry.actorUsername && entry.actorUserId !== entry.userId ? ` · performed by ${entry.actorUsername}` : ''} · {dateTime(entry.changedAt)}</small></span>
                <span className="mfa-audit-id">MFA-{entry.id}</span>
              </div>)}
            </div>}
            {audits && audits.totalElements > audits.content.length && <div className="mfa-audit-footnote">Showing the latest {audits.content.length} of {audits.totalElements} events.</div>}
          </Panel>
        </div>
      </>}

      {completedChange === 'disabled' && <div className="mfa-finish-banner" role="status">
        <ShieldAlert size={18} /><span><b>MFA disabled.</b><small>Your current tokens were revoked. Sign out and log in again to continue.</small></span><Button variant="secondary" onClick={onLogout}>Sign out</Button>
      </div>}

      {recoveryCodes && <div className="mfa-recovery-backdrop" role="presentation">
        <section className="mfa-recovery-dialog" role="dialog" aria-modal="true" aria-labelledby="mfa-recovery-title">
          <div className="mfa-recovery-icon"><KeyRound size={21} /></div>
          <div className="eyebrow">{completedChange === 'rotated' ? 'RECOVERY CODE ROTATION' : 'ONE-TIME RECOVERY'}</div>
          <h2 id="mfa-recovery-title">{completedChange === 'rotated' ? 'Save your new recovery codes' : 'Save these recovery codes'}</h2>
          <p>{completedChange === 'rotated'
            ? 'Every previous recovery code has been invalidated. These new codes are shown only now; the database stores irreversible hashes. Your current session remains active.'
            : 'Each code works once if your authenticator is unavailable. They are displayed only now; the database stores irreversible hashes. Your existing session was revoked when MFA was enabled.'}</p>
          <div className="mfa-recovery-codes">{recoveryCodes.map((recoveryCode) => <code key={recoveryCode}>{recoveryCode}</code>)}</div>
          <div className="mfa-recovery-actions">
            <Button variant="secondary" icon={Clipboard} onClick={() => void copy(recoveryCodes.join('\n'), 'Recovery codes')}>Copy all codes</Button>
            {completedChange === 'rotated'
              ? <Button onClick={dismissRecoveryCodes}>I saved them · Continue</Button>
              : <Button onClick={onLogout}>I saved them · Sign out</Button>}
          </div>
          {copyMessage && <p className="mfa-copy-message" role="status">{copyMessage}</p>}
          <small className="mfa-recovery-footnote">Keep these codes somewhere separate from your authenticator. MFA can be disabled with your password and either a fresh authenticator code or an unused recovery code.</small>
        </section>
      </div>}
    </>
  );
}
