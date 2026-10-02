import { useState, type FormEvent } from 'react';
import { Activity, ArrowRight, Clipboard, Eye, EyeOff, Fingerprint, KeyRound, LockKeyhole, ShieldCheck, Wifi, X } from 'lucide-react';
import { api, ApiError } from '../lib/api';
import type { Notice } from '../App';
import type { AuthResponse, MfaEnrollment } from '../types';

export function LoginPage({ onAuthenticated, notice, onDismissNotice }: { onAuthenticated: (session: AuthResponse) => void; notice?: Notice | null; onDismissNotice?: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [mfaToken, setMfaToken] = useState<string | null>(null);
  const [mfaCode, setMfaCode] = useState('');
  const [recoveryMode, setRecoveryMode] = useState(false);
  const [enrollmentToken, setEnrollmentToken] = useState<string | null>(null);
  const [enrollmentChallengeTtl, setEnrollmentChallengeTtl] = useState(0);
  const [requiredEnrollment, setRequiredEnrollment] = useState<MfaEnrollment | null>(null);
  const [requiredEnrollmentCode, setRequiredEnrollmentCode] = useState('');
  const [requiredSession, setRequiredSession] = useState<AuthResponse | null>(null);
  const [requiredRecoveryCodes, setRequiredRecoveryCodes] = useState<string[] | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [copyMessage, setCopyMessage] = useState('');

  const verifyingMfa = mode === 'login' && mfaToken !== null;
  const requiredMfaSetup = mode === 'login' && enrollmentToken !== null;
  const viewingRequiredRecovery = requiredMfaSetup && requiredSession !== null && requiredRecoveryCodes !== null;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (viewingRequiredRecovery && requiredSession) {
      onAuthenticated(requiredSession);
      return;
    }
    setError('');
    setSuccess('');
    setBusy(true);
    try {
      if (mode === 'register') {
        await api.register(username.trim(), password, fullName.trim());
        setMode('login');
        setUsername(username.trim().toLowerCase());
        setPassword('');
        setSuccess('Account created. Sign in with your USER account; an administrator can assign devices to you.');
      } else if (mfaToken) {
        onAuthenticated(await api.verifyMfa(mfaToken, mfaCode.trim()));
      } else if (enrollmentToken) {
        if (!requiredEnrollment) {
          const enrollment = await api.beginRequiredMfaEnrollment(enrollmentToken);
          setRequiredEnrollment(enrollment);
          setRequiredEnrollmentCode('');
          setCopyMessage('');
          setSuccess('Save the setup key in your authenticator, then enter a current code to finish required MFA setup.');
        } else {
          const completed = await api.confirmRequiredMfaEnrollment(enrollmentToken, requiredEnrollmentCode.trim());
          setRequiredSession(completed.session);
          setRequiredRecoveryCodes(completed.recoveryCodes);
          setRequiredEnrollmentCode('');
          setSuccess('');
        }
      } else {
        const result = await api.login(username.trim(), password);
        if (result.mfaRequired) {
          setMfaToken(result.mfaToken);
          setPassword('');
          setMfaCode('');
          setSuccess('Password verified. Enter a current code from your authenticator app.');
        } else if (result.mfaEnrollmentRequired) {
          setEnrollmentToken(result.enrollmentToken);
          setEnrollmentChallengeTtl(result.challengeExpiresInSeconds);
          setPassword('');
          setRequiredEnrollment(null);
          setRequiredSession(null);
          setRequiredRecoveryCodes(null);
          setSuccess('Your password is verified. Complete authenticator setup before receiving control-plane access.');
        } else {
          onAuthenticated(result);
        }
      }
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 429 && cause.retryAfter) {
        setError(`${cause.message} Try again in ${cause.retryAfter} seconds.`);
      } else {
        setError(cause instanceof Error ? cause.message : 'Sign-in failed. Try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  function backToPassword() {
    setMfaToken(null);
    setMfaCode('');
    setRecoveryMode(false);
    setEnrollmentToken(null);
    setEnrollmentChallengeTtl(0);
    setRequiredEnrollment(null);
    setRequiredEnrollmentCode('');
    setRequiredSession(null);
    setRequiredRecoveryCodes(null);
    setCopyMessage('');
    setError('');
    setSuccess('');
  }

  async function copyRecoveryCodes() {
    if (!requiredRecoveryCodes) return;
    try {
      await navigator.clipboard.writeText(requiredRecoveryCodes.join('\n'));
      setCopyMessage('Recovery codes copied. Store them somewhere private.');
    } catch {
      setCopyMessage('Clipboard access was blocked. Select and copy the codes manually.');
    }
  }

  const minutesUntilEnrollmentExpiry = Math.max(1, Math.ceil(enrollmentChallengeTtl / 60));

  return (
    <main className="login-screen">
      <section className="login-story">
        <div className="login-brand"><span className="brand-mark"><ShieldCheck size={19} /></span><span>ZERO<span className="brand-light">TRUST</span></span></div>
        <div className="login-story-copy">
          <div className="eyebrow eyebrow-on-dark"><span className="eyebrow-line" /> SECURITY OPERATIONS / 01</div>
          <h1>Every device.<br /><span>Every request.</span><br />Verified.</h1>
          <p>A focused control plane for device identity, policy decisions, and the signals moving through your IoT network.</p>
          <div className="login-feature-list">
            <div><span className="feature-icon"><Fingerprint size={17} /></span><span><b>Identity-bound telemetry</b><small>Ed25519 message verification</small></span></div>
            <div><span className="feature-icon"><ShieldCheck size={17} /></span><span><b>Policy at every request</b><small>Explicit allow and deny decisions</small></span></div>
            <div><span className="feature-icon"><Activity size={17} /></span><span><b>Auditable by design</b><small>Append-only security history</small></span></div>
          </div>
        </div>
        <div className="login-story-footer"><span>LOCAL CONTROL PLANE</span><span>BUILD 22.0</span></div>
        <div className="login-orb login-orb-one" /><div className="login-orb login-orb-two" />
      </section>
      <section className="login-form-side">
        <div className="login-form-wrap">
          <div className="login-mobile-brand"><span className="brand-mark"><ShieldCheck size={19} /></span><span>ZERO<span className="brand-light">TRUST</span></span></div>
          <div className="login-form-heading">
            <div className="eyebrow">{viewingRequiredRecovery ? 'SAVE YOUR RECOVERY CODES' : requiredMfaSetup ? 'PRIVILEGED MFA ENROLLMENT' : verifyingMfa ? 'SECOND FACTOR REQUIRED' : mode === 'login' ? 'SECURE SIGN-IN' : 'CREATE USER ACCOUNT'}</div>
            <h2>{viewingRequiredRecovery ? 'Secure your recovery codes' : requiredMfaSetup ? requiredEnrollment ? 'Set up your authenticator' : 'MFA setup required' : verifyingMfa ? 'Verify your identity' : mode === 'login' ? 'Welcome back' : 'Join the workspace'}</h2>
            <p>{viewingRequiredRecovery
              ? 'Enrollment is complete. Save these one-time codes before continuing; they will not be shown again.'
              : requiredMfaSetup
                ? requiredEnrollment
                  ? `Confirm a current authenticator code before the setup key expires to activate MFA.`
                  : `This privileged account must enroll before it can access the control plane. Start setup within ${minutesUntilEnrollmentExpiry} minutes.`
                : verifyingMfa
                  ? 'Enter a 6-digit authenticator code or use one of your single-use recovery codes.'
                  : mode === 'login'
                    ? 'Sign in with your control-plane account to continue.'
                    : 'Registration creates a standard USER account. Role is never selected by the client.'}</p>
          </div>
          {notice && !requiredMfaSetup && <div className="login-session-expired" role="status"><ShieldCheck size={14} /><span><b>{notice.title}</b>{notice.detail && <small>{notice.detail}</small>}</span><button aria-label="Dismiss notification" onClick={onDismissNotice} type="button"><X size={14} /></button></div>}
          <form onSubmit={submit} className="login-form">
            {viewingRequiredRecovery ? <>
              <div className="login-session-note"><ShieldCheck size={14} /><span>Your session will become active after you continue. Keep these codes separate from your authenticator.</span></div>
              <div className="mfa-recovery-codes login-required-recovery-codes">
                {requiredRecoveryCodes?.map((recoveryCode) => <code key={recoveryCode}>{recoveryCode}</code>)}
              </div>
              <button className="mfa-login-copy" type="button" onClick={() => void copyRecoveryCodes()}><Clipboard size={14} /> Copy all codes</button>
              {copyMessage && <p className="mfa-copy-message" role="status">{copyMessage}</p>}
            </> : verifyingMfa ? <>
              <div className="login-session-note"><Fingerprint size={14} /><span>Additional verification for <b>{username}</b>. This challenge expires in five minutes.</span></div>
              <label className="field-label" htmlFor="mfa-code">{recoveryMode ? 'Recovery code' : 'Authenticator code'}</label>
              <div className="input-wrap"><ShieldCheck size={17} /><input id="mfa-code" autoComplete="one-time-code" autoFocus required inputMode={recoveryMode ? 'text' : 'numeric'} pattern={recoveryMode ? undefined : '[0-9]{6}'} maxLength={recoveryMode ? 40 : 6} value={mfaCode} onChange={(event) => setMfaCode(event.target.value)} placeholder={recoveryMode ? 'XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX' : '000000'} /></div>
              <div className="mfa-login-actions">
                <button type="button" onClick={() => { setRecoveryMode((value) => !value); setMfaCode(''); setError(''); }}>
                  {recoveryMode ? 'Use authenticator code' : 'Use a recovery code'}
                </button>
                <button type="button" onClick={backToPassword}>Back to password sign-in</button>
              </div>
            </> : requiredMfaSetup ? <>
              <div className="login-session-note"><KeyRound size={14} /><span>Verified sign-in for <b>{username}</b>. The enrollment-only challenge cannot access other API routes.</span></div>
              {requiredEnrollment ? <>
                <div className="mfa-secret-box login-required-secret"><div><small>MANUAL SETUP KEY · BASE32</small><code>{requiredEnrollment.secret}</code></div><button className="mfa-login-copy" type="button" onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(requiredEnrollment.secret);
                    setCopyMessage('Setup key copied.');
                  } catch {
                    setCopyMessage('Clipboard access was blocked. Select and copy the setup key manually.');
                  }
                }}><Clipboard size={14} /> Copy key</button></div>
                <details className="mfa-uri-details"><summary>Show authenticator setup URI</summary><code>{requiredEnrollment.otpauthUri}</code></details>
                <p className="mfa-enrollment-expiry">Setup key expires {new Date(requiredEnrollment.expiresAt).toLocaleTimeString()}.</p>
                <label className="field-label" htmlFor="required-enrollment-code">Authenticator code</label>
                <div className="input-wrap"><ShieldCheck size={17} /><input id="required-enrollment-code" autoComplete="one-time-code" autoFocus inputMode="numeric" pattern="[0-9]{6}" maxLength={6} required value={requiredEnrollmentCode} onChange={(event) => setRequiredEnrollmentCode(event.target.value)} placeholder="000000" /></div>
              </> : <div className="mfa-required-notice"><ShieldCheck size={16} /><span><b>Turn on an authenticator to continue.</b><small>Scan the setup key in your authenticator app, then verify a current code. The short-lived challenge grants enrollment only.</small></span></div>}
              <div className="mfa-login-actions"><button type="button" onClick={backToPassword}>Back to password sign-in</button></div>
            </> : <>
              {mode === 'register' && <><label className="field-label" htmlFor="full-name">Full name</label><div className="input-wrap"><Fingerprint size={17} /><input id="full-name" autoComplete="name" required maxLength={100} value={fullName} onChange={(event) => setFullName(event.target.value)} placeholder="Your name" /></div></>}
              <label className="field-label" htmlFor="username">Username</label>
              <div className="input-wrap"><Fingerprint size={17} /><input id="username" autoComplete="username" autoFocus required minLength={mode === 'register' ? 3 : undefined} maxLength={50} pattern={mode === 'register' ? '[A-Za-z0-9._-]{3,50}' : undefined} value={username} onChange={(event) => setUsername(event.target.value)} placeholder="Enter your username" /></div>
              <div className="password-label-row"><label className="field-label" htmlFor="password">Password</label><span className="quiet-label"><LockKeyhole size={12} /> PRIVATE SESSION</span></div>
              <div className="input-wrap"><LockKeyhole size={17} /><input id="password" type={showPassword ? 'text' : 'password'} autoComplete={mode === 'register' ? 'new-password' : 'current-password'} required minLength={mode === 'register' ? 8 : undefined} maxLength={72} value={password} onChange={(event) => setPassword(event.target.value)} placeholder={mode === 'register' ? 'At least 8 characters' : 'Enter your password'} /><button className="input-action" type="button" aria-label={showPassword ? 'Hide password' : 'Show password'} onClick={() => setShowPassword((value) => !value)}>{showPassword ? <EyeOff size={16} /> : <Eye size={16} />}</button></div>
            </>}
            {success && !viewingRequiredRecovery && <div className="form-success" role="status">{success}</div>}
            {error && <div className="form-error" role="alert">{error}</div>}
            <button className="login-submit" type="submit" disabled={busy}>{busy ? <span className="spinner spinner-light" /> : <>{viewingRequiredRecovery ? 'I saved my codes · Continue' : verifyingMfa ? 'Verify and continue' : requiredMfaSetup ? requiredEnrollment ? 'Enable MFA and continue' : 'Start required MFA setup' : mode === 'login' ? 'Enter control plane' : 'Create USER account'} <ArrowRight size={17} /></>}</button>
          </form>
          {!verifyingMfa && !requiredMfaSetup && <>
            <div className="login-session-note"><LockKeyhole size={14} /><span>Access token stays in this tab's memory and is cleared when you sign out.</span></div>
            <div className="login-register-switch">{mode === 'login' ? <>Need a USER account? <button onClick={() => { setMode('register'); setError(''); setSuccess(''); }} type="button">Register here</button></> : <>Already registered? <button onClick={() => { setMode('login'); setError(''); setSuccess(''); }} type="button">Sign in</button></>}</div>
          </>}
          <div className="login-api-note"><Wifi size={14} /><span>API access is protected by role and policy.</span></div>
          <div className="login-bottom"><span>ZERO TRUST ARCHITECTURE</span><span>© 2026</span></div>
        </div>
      </section>
    </main>
  );
}
