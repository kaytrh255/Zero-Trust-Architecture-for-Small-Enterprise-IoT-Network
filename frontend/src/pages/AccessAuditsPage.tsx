import { useEffect, useState } from 'react';
import { Activity, CalendarClock, ChevronDown, Download, Filter, RefreshCw, Search, ShieldAlert, ShieldCheck } from 'lucide-react';
import { api } from '../lib/api';
import type { AccessAudit, AuditPage } from '../types';
import type { Notify } from '../App';
import { Badge, Button, EmptyState, LoadingState, PageHeading, Pagination, Panel, SectionTitle } from '../components/ui';

const time = (value: string) => new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });

export function AccessAuditsPage({ token, notify }: { token: string; notify: Notify }) {
  const [result, setResult] = useState<AuditPage<AccessAudit> | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [decision, setDecision] = useState('');
  const [channel, setChannel] = useState('');
  const [deviceCode, setDeviceCode] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    api.accessAudits(token, page, { decision, channel, deviceCode: deviceCode.trim() }).then((data) => {
      if (active) setResult(data);
    }).catch((cause: unknown) => {
      if (active) notify({ tone: 'error', title: 'Could not load access decisions', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token, page, decision, channel, deviceCode, reload, notify]);

  function exportCsv() {
    if (!result?.content.length) return;
    const rows = [
      ['id', 'decision', 'reason', 'deviceCode', 'channel', 'requester', 'resource', 'action', 'sequence', 'evaluatedAt'],
      ...result.content.map((event) => [event.id, event.decision, event.reason, event.deviceCode, event.channel, event.requesterUsername, event.resource, event.action, event.messageSequence ?? '', event.evaluatedAt]),
    ];
    const csv = rows.map((row) => row.map((value) => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\r\n');
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    link.download = 'zero-trust-access-decisions.csv';
    link.click();
    URL.revokeObjectURL(link.href);
  }

  const denied = result?.content.filter((event) => event.decision === 'DENY').length ?? 0;
  const mqtt = result?.content.filter((event) => event.channel === 'MQTT').length ?? 0;

  return (
    <>
      <PageHeading eyebrow="AUDIT TRAIL / ACCESS DECISIONS" title="Access decisions" description="An immutable view of policy outcomes across API requests and signed MQTT telemetry." action={<Button variant="secondary" icon={RefreshCw} onClick={() => setReload((value) => value + 1)} disabled={loading}>Refresh</Button>} />
      <div className="audit-summary-strip"><div><span className="audit-summary-icon audit-icon-slate"><Activity size={16} /></span><span><small>TOTAL EVENTS</small><b>{result?.totalElements ?? '—'}</b></span></div><i /><div><span className="audit-summary-icon audit-icon-red"><ShieldAlert size={16} /></span><span><small>DENIED · THIS PAGE</small><b>{denied}</b></span></div><i /><div><span className="audit-summary-icon audit-icon-blue"><CalendarClock size={16} /></span><span><small>MQTT · THIS PAGE</small><b>{mqtt}</b></span></div><i /><span className="audit-summary-note"><ShieldCheck size={14} /> Every evaluated outcome is recorded</span></div>
      <Panel className="table-panel">
        <div className="table-toolbar audit-toolbar"><div><SectionTitle title="Decision ledger" subtitle="Newest first · stable timestamp and ID ordering" /></div><Button variant="secondary" size="sm" icon={Download} disabled={!result?.content.length} onClick={exportCsv}>Export page</Button></div>
        <div className="filter-toolbar"><div className="filter-toolbar-title"><Filter size={15} /><span>FILTERS</span></div><label className="filter-field"><span>Decision</span><select value={decision} onChange={(event) => { setDecision(event.target.value); setPage(0); }}><option value="">All decisions</option><option value="ALLOW">Allow</option><option value="DENY">Deny</option></select><ChevronDown size={13} /></label><label className="filter-field"><span>Channel</span><select value={channel} onChange={(event) => { setChannel(event.target.value); setPage(0); }}><option value="">All channels</option><option value="API">API</option><option value="MQTT">MQTT</option></select><ChevronDown size={13} /></label><label className="search-field filter-search"><Search size={15} /><input aria-label="Filter by device code" placeholder="Device code" value={deviceCode} onChange={(event) => { setDeviceCode(event.target.value); setPage(0); }} /></label><button className="filter-reset" onClick={() => { setDecision(''); setChannel(''); setDeviceCode(''); setPage(0); }}>Reset</button></div>
        {loading ? <LoadingState label="Loading immutable access history" /> : result?.content.length ? <div className="table-scroll"><table className="data-table audit-table"><thead><tr><th>OUTCOME</th><th>DEVICE / CHANNEL</th><th>REQUESTER</th><th>OPERATION</th><th>REASON</th><th>SEQUENCE</th><th>TIME</th></tr></thead><tbody>{result.content.map((event) => <tr key={event.id}>
          <td><Badge tone={event.decision === 'ALLOW' ? 'green' : 'red'} dot>{event.decision}</Badge></td>
          <td><div className="table-primary">{event.deviceCode || '—'}</div><div className="table-secondary"><Badge tone={event.channel === 'MQTT' ? 'violet' : 'blue'}>{event.channel}</Badge><span className="audit-device-type">{event.deviceType ?? 'API'}</span></div></td>
          <td><div className="table-primary">{event.requesterUsername || 'device identity'}</div><div className="table-secondary">{event.requesterRole}{event.deviceStatus ? ` · ${event.deviceStatus}` : ''}</div></td>
          <td><div className="table-primary">{event.action}</div><div className="table-secondary mono-text">{event.resource}</div></td>
          <td><span className={`reason-chip ${event.decision === 'DENY' ? 'reason-chip-deny' : ''}`}>{event.reason.replaceAll('_', ' ')}</span>{event.matchedPolicyName && <div className="table-secondary">{event.matchedPolicyName}</div>}</td>
          <td>{event.messageSequence !== null ? <span className="sequence-pill">#{event.messageSequence}</span> : <span className="muted-dash">—</span>}</td>
          <td><span className="audit-time">{time(event.evaluatedAt)}</span><small className="audit-id">ID {event.id}</small></td>
        </tr>)}</tbody></table></div> : <EmptyState icon={ShieldCheck} title="No matching access decisions" detail="Evaluated API and MQTT events will appear here. Try clearing a filter or check back after traffic arrives." />}
        {result && <Pagination page={result.page} totalPages={result.totalPages} totalElements={result.totalElements} onPageChange={setPage} />}
      </Panel>
      <div className="audit-footnote"><ShieldCheck size={14} /><span>Invalid Ed25519 signatures are audited before policy evaluation and do not include an untrusted message sequence.</span></div>
    </>
  );
}
