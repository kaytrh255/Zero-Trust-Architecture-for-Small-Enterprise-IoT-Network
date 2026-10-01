import { useState, type FormEvent } from 'react';
import { Activity, ArrowRight, Eye, EyeOff, Fingerprint, LockKeyhole, ShieldCheck, Wifi, X } from 'lucide-react';
import { api, ApiError } from '../lib/api';
import type { Notice } from '../App';
import type { AuthResponse } from '../types';

export function LoginPage({ onAuthenticated, notice, onDismissNotice }: { onAuthenticated: (session: AuthResponse) => void; notice?: Notice | null; onDismissNotice?: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
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
      } else {
        onAuthenticated(await api.login(username.trim(), password));
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
        <div className="login-story-footer"><span>LOCAL CONTROL PLANE</span><span>BUILD 19.0</span></div>
        <div className="login-orb login-orb-one" /><div className="login-orb login-orb-two" />
      </section>
      <section className="login-form-side">
        <div className="login-form-wrap">
          <div className="login-mobile-brand"><span className="brand-mark"><ShieldCheck size={19} /></span><span>ZERO<span className="brand-light">TRUST</span></span></div>
          <div className="login-form-heading">
            <div className="eyebrow">{mode === 'login' ? 'SECURE SIGN-IN' : 'CREATE USER ACCOUNT'}</div>
            <h2>{mode === 'login' ? 'Welcome back' : 'Join the workspace'}</h2>
            <p>{mode === 'login' ? 'Sign in with your control-plane account to continue.' : 'Registration creates a standard USER account. Role is never selected by the client.'}</p>
          </div>
          {notice && <div className="login-session-expired" role="status"><ShieldCheck size={14} /><span><b>{notice.title}</b>{notice.detail && <small>{notice.detail}</small>}</span><button aria-label="Dismiss notification" onClick={onDismissNotice} type="button"><X size={14} /></button></div>}
          <form onSubmit={submit} className="login-form">
            {mode === 'register' && <><label className="field-label" htmlFor="full-name">Full name</label><div className="input-wrap"><Fingerprint size={17} /><input id="full-name" autoComplete="name" required maxLength={100} value={fullName} onChange={(event) => setFullName(event.target.value)} placeholder="Your name" /></div></>}
            <label className="field-label" htmlFor="username">Username</label>
            <div className="input-wrap"><Fingerprint size={17} /><input id="username" autoComplete="username" autoFocus required minLength={mode === 'register' ? 3 : undefined} maxLength={50} pattern={mode === 'register' ? '[A-Za-z0-9._-]{3,50}' : undefined} value={username} onChange={(event) => setUsername(event.target.value)} placeholder="Enter your username" /></div>
            <div className="password-label-row"><label className="field-label" htmlFor="password">Password</label><span className="quiet-label"><LockKeyhole size={12} /> PRIVATE SESSION</span></div>
            <div className="input-wrap"><LockKeyhole size={17} /><input id="password" type={showPassword ? 'text' : 'password'} autoComplete={mode === 'register' ? 'new-password' : 'current-password'} required minLength={mode === 'register' ? 8 : undefined} maxLength={72} value={password} onChange={(event) => setPassword(event.target.value)} placeholder={mode === 'register' ? 'At least 8 characters' : 'Enter your password'} /><button className="input-action" type="button" aria-label={showPassword ? 'Hide password' : 'Show password'} onClick={() => setShowPassword((value) => !value)}>{showPassword ? <EyeOff size={16} /> : <Eye size={16} />}</button></div>
            {success && <div className="form-success" role="status">{success}</div>}
            {error && <div className="form-error" role="alert">{error}</div>}
            <button className="login-submit" type="submit" disabled={busy}>{busy ? <span className="spinner spinner-light" /> : <>{mode === 'login' ? 'Enter control plane' : 'Create USER account'} <ArrowRight size={17} /></>}</button>
          </form>
          <div className="login-session-note"><LockKeyhole size={14} /><span>Access token stays in this tab's memory and is cleared when you sign out.</span></div>
          <div className="login-register-switch">{mode === 'login' ? <>Need a USER account? <button onClick={() => { setMode('register'); setError(''); setSuccess(''); }} type="button">Register here</button></> : <>Already registered? <button onClick={() => { setMode('login'); setError(''); setSuccess(''); }} type="button">Sign in</button></>}</div>
          <div className="login-api-note"><Wifi size={14} /><span>API access is protected by role and policy.</span></div>
          <div className="login-bottom"><span>ZERO TRUST ARCHITECTURE</span><span>© 2026</span></div>
        </div>
      </section>
    </main>
  );
}
