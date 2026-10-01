import { useEffect, useMemo, useState } from 'react';
import { Activity, Download, RadioTower, RefreshCw, Search, Thermometer, Waves, type LucideIcon } from 'lucide-react';
import { api } from '../lib/api';
import type { TelemetrySample } from '../types';
import type { Notify } from '../App';
import { Badge, Button, EmptyState, LoadingState, PageHeading, Panel, SectionTitle } from '../components/ui';

const stamp = (value: string) => new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });

function TelemetryChart({ samples }: { samples: TelemetrySample[] }) {
  const values = [...samples].reverse().slice(-36).map((sample) => Number(sample.value)).filter(Number.isFinite);
  const range = Math.max(...values) - Math.min(...values) || 1;
  const points = values.length > 1 ? values.map((value, index) => `${(index / (values.length - 1)) * 800},${120 - ((value - Math.min(...values)) / range) * 88}`).join(' ') : '';
  const lastPoint = points.split(' ').at(-1)?.split(',');
  return (
    <svg className="telemetry-wide-chart" viewBox="0 0 800 150" role="img" aria-label="Telemetry measurements over time">
      <defs><linearGradient id="wide-line" x1="0" x2="1"><stop offset="0%" stopColor="#5e8ff2" /><stop offset="100%" stopColor="#52d7a4" /></linearGradient><linearGradient id="wide-area" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#59c9ae" stopOpacity=".17" /><stop offset="100%" stopColor="#59c9ae" stopOpacity="0" /></linearGradient></defs>
      {[26, 58, 90, 122].map((y) => <line key={y} x1="0" x2="800" y1={y} y2={y} className="chart-grid-line chart-grid-dark" />)}
      {points && <><polygon points={`0,150 ${points} 800,150`} fill="url(#wide-area)" /><polyline points={points} fill="none" stroke="url(#wide-line)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />{lastPoint && <circle cx={lastPoint[0]} cy={lastPoint[1]} r="4.5" fill="#68e0b3" stroke="#fff" strokeWidth="2" />}</>}
    </svg>
  );
}

export function TelemetryPage({ token, notify }: { token: string; notify: Notify }) {
  const [samples, setSamples] = useState<TelemetrySample[]>([]);
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    api.telemetry(token).then((result) => { if (active) setSamples(result); }).catch((cause: unknown) => {
      if (active) notify({ tone: 'error', title: 'Could not load telemetry', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token, reload, notify]);

  const filtered = useMemo(() => {
    const value = query.trim().toLowerCase();
    return value ? samples.filter((sample) => sample.deviceCode.toLowerCase().includes(value) || sample.metric.toLowerCase().includes(value)) : samples;
  }, [samples, query]);
  const devices = new Set(samples.map((sample) => sample.deviceCode)).size;
  const metrics = new Set(samples.map((sample) => sample.metric)).size;
  const latest = samples[0];

  function exportCsv() {
    if (!filtered.length) return;
    const rows = [['deviceCode', 'deviceSequence', 'metric', 'value', 'unit', 'measuredAt', 'receivedAt'], ...filtered.map((sample) => [sample.deviceCode, sample.deviceSequence, sample.metric, sample.value, sample.unit, sample.measuredAt, sample.receivedAt])];
    const csv = rows.map((row) => row.map((value) => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\r\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = 'zero-trust-telemetry.csv'; link.click(); URL.revokeObjectURL(url);
  }

  return (
    <>
      <PageHeading eyebrow="SIGNAL MONITORING / TELEMETRY" title="Telemetry" description="Recent measurements accepted only after device identity, signature, status, policy, and sequence checks." action={<Button variant="secondary" icon={RefreshCw} onClick={() => setReload((value) => value + 1)} disabled={loading}>Refresh</Button>} />
      <div className="telemetry-stat-grid"><TelemetryStat icon={Waves} label="ACCEPTED SAMPLES" value={samples.length} note="Most recent 100" tone="green" /><TelemetryStat icon={RadioTower} label="REPORTING DEVICES" value={devices} note="Verified identities" tone="blue" /><TelemetryStat icon={Thermometer} label="ACTIVE METRICS" value={metrics} note="Unique metric names" tone="violet" /><TelemetryStat icon={Activity} label="LATEST VALUE" value={latest ? `${latest.value} ${latest.unit}` : '—'} note={latest ? `${latest.metric} · ${latest.deviceCode}` : 'Awaiting signed sample'} tone="amber" /></div>
      <Panel className="telemetry-visual-panel">
        <div className="telemetry-visual-head"><div><div className="eyebrow">ACCEPTED MEASUREMENTS</div><h2>Signal overview</h2><p>Last {Math.min(samples.length, 36)} samples, ordered chronologically.</p></div><Badge tone="green" dot>VERIFIED STREAM</Badge></div>
        {loading ? <LoadingState label="Reading accepted telemetry" /> : samples.length > 1 ? <><TelemetryChart samples={samples} /><div className="chart-axis"><span>{stamp(samples[Math.min(samples.length - 1, 35)].measuredAt)}</span><span>SIGNED DEVICE PAYLOADS</span><span>{latest ? stamp(latest.measuredAt) : 'NOW'}</span></div></> : <div className="chart-empty telemetry-chart-empty"><Waves size={18} /><span>Publish a signed MQTT telemetry message to populate the signal view.</span></div>}
      </Panel>
      <Panel className="table-panel telemetry-table-panel">
        <div className="table-toolbar"><div><SectionTitle title="Accepted samples" subtitle="Only measurements that passed the backend decision chain" /></div><div className="table-tools"><label className="search-field"><Search size={15} /><input aria-label="Search telemetry" placeholder="Device or metric..." value={query} onChange={(event) => setQuery(event.target.value)} /></label><Button variant="secondary" size="sm" icon={Download} disabled={!filtered.length} onClick={exportCsv}>Export</Button></div></div>
        {loading ? <LoadingState label="Loading telemetry" /> : filtered.length ? <div className="table-scroll"><table className="data-table telemetry-table"><thead><tr><th>MEASUREMENT</th><th>DEVICE</th><th>VALUE</th><th>SEQUENCE</th><th>MEASURED AT</th><th>RECEIVED AT</th></tr></thead><tbody>{filtered.map((sample) => <tr key={sample.id}><td><div className="telemetry-metric"><span className="metric-row-icon"><Thermometer size={15} /></span><div><b>{sample.metric}</b><small>MQTT telemetry</small></div></div></td><td><span className="mono-text">{sample.deviceCode}</span></td><td><span className="telemetry-value">{sample.value}<small> {sample.unit}</small></span></td><td><span className="sequence-pill">#{sample.deviceSequence}</span></td><td><span className="table-secondary">{stamp(sample.measuredAt)}</span></td><td><span className="table-secondary">{stamp(sample.receivedAt)}</span></td></tr>)}</tbody></table></div> : <EmptyState icon={Waves} title={query ? 'No matching measurements' : 'No accepted telemetry yet'} detail={query ? 'Try another device code or metric.' : 'Denied, malformed, and invalidly signed messages are never stored as telemetry.'} />}
        <div className="table-footer"><span>{filtered.length} samples shown <i /> Maximum 100 newest accepted records</span><span>SEQUENCE-CHECKED / ED25519</span></div>
      </Panel>
    </>
  );
}

function TelemetryStat({ icon: Icon, label, value, note, tone }: { icon: LucideIcon; label: string; value: string | number; note: string; tone: string }) {
  return <Panel className="telemetry-stat"><span className={`metric-icon metric-icon-${tone}`}><Icon size={17} /></span><div><small>{label}</small><b>{value}</b><span>{note}</span></div></Panel>;
}
