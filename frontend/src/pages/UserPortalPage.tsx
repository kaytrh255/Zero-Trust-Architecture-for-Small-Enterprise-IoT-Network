import { useState, type FormEvent } from 'react';
import { ArrowLeft, ArrowRight, Fingerprint, LockKeyhole, LogOut, RadioTower, ShieldAlert, ShieldCheck, Waves, X } from 'lucide-react';
import { api } from '../lib/api';
import type { AccessDecision, ProtectedTelemetryResponse, UserProfile } from '../types';
import type { Notice } from '../App';
import { Badge, Button } from '../components/ui';

export function UserPortalPage({ token, user, onLogout, notice, onDismissNotice }: { token: string; user: UserProfile; onLogout: () => void; notice: Notice | null; onDismissNotice: () => void }) {
  const [deviceCode, setDeviceCode] = useState('SENSOR-001');
  const [result, setResult] = useState<ProtectedTelemetryResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function read(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true); setError(''); setResult(null);
    try {
      setResult(await api.protectedTelemetry(token, deviceCode.trim().toUpperCase()));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The protected resource could not be read.');
    } finally { setLoading(false); }
  }

  const decision: AccessDecision | undefined = result?.accessDecision;

  return (
    <main className="user-portal">
      <header className="user-portal-header"><a className="sidebar-brand user-portal-brand" href="#top"><span className="brand-mark"><ShieldCheck size={18} /></span><span className="brand-wordmark">ZERO<span>TRUST</span></span></a><div className="portal-header-actions"><Badge tone="green" dot>USER SESSION</Badge><span className="portal-user-chip"><span className="user-avatar user-avatar-small">{user.fullName.split(/\s+/).map((part) => part[0]).slice(0, 2).join('').toUpperCase()}</span>{user.fullName}</span><button className="logout-button" aria-label="Sign out" onClick={onLogout}><LogOut size={16} /></button></div></header>
      <section className="user-portal-body" id="top">
        <div className="user-portal-copy"><div className="eyebrow"><span className="eyebrow-line" /> PROTECTED RESOURCE ACCESS</div><h1>Your identity.<br /><span>Your access.</span></h1><p>Every read is evaluated against device state, policy, and ownership before telemetry is returned.</p><div className="portal-trust-list"><span><Fingerprint size={15} /> Authenticated as {user.username}</span><span><LockKeyhole size={15} /> Bearer token never stored on disk</span><span><ShieldCheck size={15} /> Decision and access are audited</span></div></div>
        <section className="resource-card"><div className="resource-card-top"><span className="resource-card-icon"><RadioTower size={19} /></span><Badge tone="blue">TELEMETRY RESOURCE</Badge></div><h2>Read device telemetry</h2><p>Request access to a device you own. The backend checks the access policy and ownership before returning any samples.</p><form className="resource-form" onSubmit={read}><label htmlFor="device-code">Device code</label><div className="resource-code-input"><RadioTower size={16} /><input id="device-code" required minLength={1} maxLength={64} value={deviceCode} onChange={(event) => setDeviceCode(event.target.value.toUpperCase())} placeholder="SENSOR-001" /></div><Button icon={loading ? undefined : ArrowRight} type="submit" disabled={loading}>{loading ? 'Evaluating access…' : 'Evaluate & read'}</Button></form><div className="resource-card-note"><ShieldCheck size={14} /> No telemetry is queried unless the decision is ALLOW.</div></section>
        {error && <div className="portal-error" role="alert"><ShieldAlert size={16} />{error}</div>}
        {decision && <section className={`decision-result decision-${decision.decision.toLowerCase()}`}><div className="decision-result-head"><span className="decision-result-icon">{decision.decision === 'ALLOW' ? <ShieldCheck size={19} /> : <ShieldAlert size={19} />}</span><span><small>ACCESS DECISION</small><h2>{decision.decision}</h2></span><Badge tone={decision.decision === 'ALLOW' ? 'green' : 'red'} dot>{decision.reason.replaceAll('_', ' ')}</Badge></div><div className="decision-result-meta"><span><b>Device</b>{decision.deviceCode}</span><span><b>Resource</b>{decision.resource}</span><span><b>Action</b>{decision.action}</span><span><b>Audit ID</b>{decision.auditId ? `#${decision.auditId}` : '—'}</span></div>{decision.decision === 'ALLOW' ? <div className="protected-samples"><div className="protected-samples-title"><span><Waves size={15} /> {result?.telemetry.length ?? 0} protected samples returned</span><span>Owner + policy verified</span></div>{result?.telemetry.length ? <div className="portal-sample-list">{result.telemetry.slice(0, 6).map((sample) => <div key={sample.id}><span><b>{sample.metric}</b><small>{sample.deviceCode} · seq #{sample.deviceSequence}</small></span><strong>{sample.value}<small> {sample.unit}</small></strong><time>{new Date(sample.measuredAt).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</time></div>)}</div> : <div className="portal-empty-samples">Policy and ownership checks passed. No samples have been stored for this device yet.</div>}</div> : <div className="denial-detail"><LockKeyhole size={15} /><span>Access was denied. Telemetry was not queried and no resource data was returned.</span></div>}</section>}
        <div className="user-portal-footer"><span><ArrowLeft size={13} /> YOUR SESSION IS ROLE-RESTRICTED</span><span>ACCESS CONTROL / ZERO TRUST</span></div>
      </section>
      {notice && <div className={`toast toast-${notice.tone}`} role="status"><span className="toast-mark"><ShieldCheck size={17} /></span><span className="toast-copy"><b>{notice.title}</b>{notice.detail && <small>{notice.detail}</small>}</span><button className="toast-close" onClick={onDismissNotice} aria-label="Dismiss"><X size={15} /></button></div>}
    </main>
  );
}
