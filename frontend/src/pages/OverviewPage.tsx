import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import {
  ArrowDownRight, ArrowRight, ArrowUpRight, Activity, CircleCheck, Clock3, Fingerprint,
  RadioTower, RefreshCw, ScrollText, ShieldAlert, ShieldCheck, ShieldX, Waves,
  type LucideIcon,
} from 'lucide-react';
import { api } from '../lib/api';
import type { AccessAudit, AuditPage, Device, Policy, TelemetrySample, UserProfile } from '../types';
import type { Notify } from '../App';
import type { ViewKey } from '../components/Shell';
import { Badge, Button, EmptyState, LoadingState, PageHeading, Panel, SectionTitle } from '../components/ui';

interface OverviewData {
  devices: Device[];
  policies: Policy[];
  audits: AuditPage<AccessAudit>;
  telemetry: TelemetrySample[];
}

const time = (value?: string | null) => value ? new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

function SparkLine({ samples }: { samples: TelemetrySample[] }) {
  const points = useMemo(() => {
    const values = [...samples].reverse().slice(-28).map((item) => Number(item.value)).filter(Number.isFinite);
    if (values.length < 2) return '';
    const min = Math.min(...values);
    const max = Math.max(...values);
    const spread = max - min || 1;
    return values.map((value, index) => `${(index / (values.length - 1)) * 640},${104 - ((value - min) / spread) * 76}`).join(' ');
  }, [samples]);

  return (
    <svg className="spark-chart" viewBox="0 0 640 120" role="img" aria-label="Recent telemetry trend">
      <defs><linearGradient id="telemetry-line" x1="0" x2="1"><stop offset="0%" stopColor="#56dfad" /><stop offset="100%" stopColor="#a3f39c" /></linearGradient><linearGradient id="telemetry-fill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#57dfad" stopOpacity=".2" /><stop offset="100%" stopColor="#57dfad" stopOpacity="0" /></linearGradient></defs>
      {[26, 54, 82, 110].map((y) => <line key={y} x1="0" x2="640" y1={y} y2={y} className="chart-grid-line" />)}
      {points && <>
        <polygon points={`0,120 ${points} 640,120`} fill="url(#telemetry-fill)" />
        <polyline points={points} fill="none" stroke="url(#telemetry-line)" strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" />
        <circle cx={points.split(' ').at(-1)?.split(',')[0]} cy={points.split(' ').at(-1)?.split(',')[1]} r="4.5" fill="#8bf1c3" stroke="#112535" strokeWidth="3" />
      </>}
    </svg>
  );
}

export function OverviewPage({ token, user, notify, onNavigate }: { token: string; user: UserProfile; notify: Notify; onNavigate: (view: ViewKey) => void }) {
  const [data, setData] = useState<OverviewData | null>(null);
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    Promise.all([
      api.devices(token),
      api.policies(token),
      api.accessAudits(token),
      api.telemetry(token),
    ]).then(([devices, policies, audits, telemetry]) => {
      if (active) setData({ devices, policies, audits, telemetry });
    }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : 'Unable to load overview data.');
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [token, reload]);

  const activeDevices = data?.devices.filter((device) => device.status === 'ACTIVE').length ?? 0;
  const auditRows = data?.audits.content ?? [];
  const denyCount = auditRows.filter((event) => event.decision === 'DENY').length;
  const denyRate = auditRows.length ? Math.round((denyCount / auditRows.length) * 100) : 0;
  const recentChecks = data?.audits.totalElements ?? 0;
  const statusCounts = {
    ACTIVE: data?.devices.filter((device) => device.status === 'ACTIVE').length ?? 0,
    INACTIVE: data?.devices.filter((device) => device.status === 'INACTIVE').length ?? 0,
    BLOCKED: data?.devices.filter((device) => device.status === 'BLOCKED').length ?? 0,
    REVOKED: data?.devices.filter((device) => device.status === 'REVOKED').length ?? 0,
  };
  const telemetry = data?.telemetry ?? [];
  const latestValue = telemetry[0];

  return (
    <>
      <PageHeading
        eyebrow="SECURITY POSTURE / LIVE"
        title={`Good to see you, ${user.fullName.split(' ')[0]}`}
        description="A clear view of the identities, policies, and decisions protecting your IoT network."
        action={<Button variant="secondary" icon={RefreshCw} onClick={() => setReload((value) => value + 1)} disabled={loading}>Refresh</Button>}
      />
      {error && <div className="inline-error" role="alert"><ShieldAlert size={16} /><span>{error}</span><button onClick={() => setReload((value) => value + 1)}>Retry</button></div>}
      {loading && !data ? <Panel><LoadingState label="Synchronizing control-plane data" /></Panel> : (
        <>
          <section className="posture-banner">
            <div className="posture-left">
              <div className="posture-status"><span className="pulse-dot" /> ENFORCEMENT ACTIVE</div>
              <h2>Identity is the new perimeter.</h2>
              <p>Every MQTT message is verified. Every access decision is policy-bound and recorded.</p>
              <div className="posture-meta"><span><ShieldCheck size={14} /> Ed25519 signatures</span><i /> <span><Fingerprint size={14} /> Device-bound identity</span><i /> <span><CircleCheck size={14} /> Replay protection</span></div>
            </div>
            <div className="posture-art" aria-hidden="true">
              <div className="orbit orbit-one" /><div className="orbit orbit-two" /><div className="orbit orbit-three" />
              <div className="orbit-core"><ShieldCheck size={27} /></div>
              <span className="orbit-node node-a"><Fingerprint size={16} /></span><span className="orbit-node node-b"><Activity size={16} /></span><span className="orbit-node node-c"><ShieldCheck size={16} /></span>
            </div>
            <div className="posture-version"><span>PHASE 18</span><b>MESSAGE SIGNING</b></div>
          </section>

          <div className="metrics-grid">
            <MetricCard icon={RadioTower} label="Registered devices" value={data?.devices.length ?? 0} foot={`${activeDevices} active identities`} tone="blue" />
            <MetricCard icon={ShieldCheck} label="Enabled policies" value={data?.policies.filter((policy) => policy.enabled).length ?? 0} foot={`${data?.policies.length ?? 0} total rules`} tone="green" />
            <MetricCard icon={Activity} label="Access decisions" value={recentChecks} foot="All-time evaluated events" tone="violet" />
            <MetricCard icon={ShieldX} label="Deny rate" value={`${denyRate}%`} foot={`Latest ${auditRows.length || 0} decisions`} tone={denyRate > 40 ? 'red' : 'amber'} trend={denyRate > 40 ? 'review' : 'within range'} />
          </div>

          <div className="overview-grid">
            <Panel className="telemetry-panel">
              <SectionTitle title="Telemetry pulse" subtitle="Latest accepted device measurements" action={<button className="text-link" onClick={() => onNavigate('telemetry')}>View telemetry <ArrowRight size={14} /></button>} />
              <div className="telemetry-chart-meta"><div><strong>{latestValue ? `${latestValue.value} ${latestValue.unit}` : '—'}</strong><span>{latestValue?.metric ?? 'Awaiting first sample'}</span></div><Badge tone="green" dot>VERIFIED</Badge></div>
              {telemetry.length > 1 ? <SparkLine samples={telemetry} /> : <div className="chart-empty"><Waves size={18} /><span>Telemetry appears after a device publishes a signed sample.</span></div>}
              <div className="chart-axis"><span>OLDEST SAMPLE</span><span>{telemetry.length} RECENT SAMPLES</span><span>NOW</span></div>
            </Panel>
            <Panel className="fleet-panel">
              <SectionTitle title="Device posture" subtitle="Identity status across the fleet" action={<button className="text-link" onClick={() => onNavigate('devices')}>Manage fleet <ArrowRight size={14} /></button>} />
              <div className="fleet-ring-wrap"><div className="fleet-ring" style={{ '--active-share': `${data?.devices.length ? (statusCounts.ACTIVE / data.devices.length) * 100 : 0}%` } as CSSProperties}><span><b>{data?.devices.length ?? 0}</b><small>DEVICES</small></span></div><div className="fleet-summary"><b>{statusCounts.ACTIVE} <span>active</span></b><small>Last seen: {time(data?.devices.filter((device) => device.lastSeenAt).sort((a, b) => (b.lastSeenAt ?? '').localeCompare(a.lastSeenAt ?? ''))[0]?.lastSeenAt)}</small></div></div>
              <div className="status-legend">
                <StatusLegend label="Active" count={statusCounts.ACTIVE} tone="green" total={data?.devices.length ?? 0} />
                <StatusLegend label="Inactive" count={statusCounts.INACTIVE} tone="amber" total={data?.devices.length ?? 0} />
                <StatusLegend label="Blocked / revoked" count={statusCounts.BLOCKED + statusCounts.REVOKED} tone="red" total={data?.devices.length ?? 0} />
              </div>
            </Panel>
          </div>

          <Panel className="activity-panel">
            <SectionTitle title="Recent access activity" subtitle="Latest policy evaluations across API and MQTT" action={<button className="text-link" onClick={() => onNavigate('access')}>View audit log <ArrowRight size={14} /></button>} />
            {auditRows.length ? <div className="table-scroll"><table className="data-table"><thead><tr><th>DECISION</th><th>DEVICE</th><th>REQUESTER</th><th>RESOURCE</th><th>REASON</th><th>TIME</th></tr></thead><tbody>
              {auditRows.slice(0, 5).map((event) => <tr key={event.id}>
                <td><Badge tone={event.decision === 'ALLOW' ? 'green' : 'red'} dot>{event.decision}</Badge></td>
                <td><div className="table-primary">{event.deviceCode || '—'}</div><div className="table-secondary">{event.channel} · {event.action}</div></td>
                <td><div className="table-primary">{event.requesterUsername || 'device identity'}</div><div className="table-secondary">{event.requesterRole}</div></td>
                <td><span className="mono-text">{event.resource}</span></td>
                <td><span className="reason-text">{event.reason.replaceAll('_', ' ')}</span></td>
                <td><span className="time-cell"><Clock3 size={13} />{time(event.evaluatedAt)}</span></td>
              </tr>)}
            </tbody></table></div> : <EmptyState icon={ShieldCheck} title="No access decisions yet" detail="Evaluated API and signed MQTT requests will appear here." />}
          </Panel>

          <div className="overview-bottom-grid">
            <Panel className="quick-panel">
              <SectionTitle title="Quick actions" subtitle="Common control-plane tasks" />
              <div className="quick-actions">
                <button onClick={() => onNavigate('devices')}><span className="quick-icon quick-icon-blue"><RadioTower size={17} /></span><span><b>Provision a device</b><small>Create identity and issue one-time credentials</small></span><ArrowRight size={15} /></button>
                <button onClick={() => onNavigate('policies')}><span className="quick-icon quick-icon-green"><ShieldCheck size={17} /></span><span><b>Review policy rules</b><small>Check what is allowed and denied</small></span><ArrowRight size={15} /></button>
                <button onClick={() => onNavigate('access')}><span className="quick-icon quick-icon-violet"><ScrollText size={17} /></span><span><b>Investigate an event</b><small>{denyCount} denied in the latest {auditRows.length || 0} decisions</small></span><ArrowRight size={15} /></button>
              </div>
            </Panel>
            <Panel className="integrity-panel">
              <SectionTitle title="Trust controls" subtitle="Configured verification layers" />
              <div className="integrity-list">
                <IntegrityRow label="Broker TLS" detail="Verified CA + hostname" />
                <IntegrityRow label="Device identity" detail="Per-device MQTT credentials" />
                <IntegrityRow label="Message integrity" detail="Ed25519 application signature" />
                <IntegrityRow label="Replay protection" detail="Monotonic sequence per device" />
              </div>
              <div className="integrity-footer"><span className="integrity-mark"><ShieldCheck size={15} /></span><span>Invalid signatures are audited before policy evaluation.</span><button onClick={() => notify({ tone: 'info', title: 'Trust model', detail: 'Invalid signatures are denied without consuming an untrusted sequence.' })} aria-label="About trust controls"><ArrowUpRight size={14} /></button></div>
            </Panel>
          </div>
        </>
      )}
    </>
  );
}

function MetricCard({ icon: Icon, label, value, foot, tone, trend }: { icon: LucideIcon; label: string; value: string | number; foot: string; tone: string; trend?: string }) {
  return (
    <Panel className="metric-card">
      <div className="metric-top"><span className={`metric-icon metric-icon-${tone}`}><Icon size={17} /></span>{trend ? <span className="metric-trend"><ArrowDownRight size={13} />{trend}</span> : <span className="metric-more">⋯</span>}</div>
      <div className="metric-label">{label}</div><div className="metric-value">{value}</div><div className="metric-foot">{foot}</div>
    </Panel>
  );
}

function StatusLegend({ label, count, tone, total }: { label: string; count: number; tone: string; total: number }) {
  const width = total ? (count / total) * 100 : 0;
  return <div className="legend-row"><span className={`legend-dot legend-dot-${tone}`} /><span>{label}</span><b>{count}</b><div className="legend-track"><i className={`legend-fill legend-fill-${tone}`} style={{ width: `${width}%` }} /></div></div>;
}

function IntegrityRow({ label, detail }: { label: string; detail: string }) {
  return <div className="integrity-row"><span className="integrity-check"><CircleCheck size={15} /></span><span><b>{label}</b><small>{detail}</small></span><Badge tone="green">ON</Badge></div>;
}
