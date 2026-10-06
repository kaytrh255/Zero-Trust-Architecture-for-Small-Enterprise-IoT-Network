import { useEffect, useState } from 'react';
import { CalendarClock, Download, Fingerprint, RefreshCw, Search, ShieldAlert, ShieldCheck, UserRoundCheck, UserRoundX } from 'lucide-react';
import { api } from '../lib/api';
import type { AuditPage, AuthenticationAttempt } from '../types';
import type { Notify } from '../App';
import { Badge, Button, EmptyState, LoadingState, PageHeading, Pagination, Panel, SectionTitle } from '../components/ui';

const time = (value: string) => new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });

export function AuthenticationAuditsPage({ token, notify }: { token: string; notify: Notify }) {
  const [result, setResult] = useState<AuditPage<AuthenticationAttempt> | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [outcome, setOutcome] = useState('');
  const [username, setUsername] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    api.authenticationAudits(token, page, { outcome, username: username.trim() }).then((data) => {
      if (active) setResult(data);
    }).catch((cause: unknown) => {
      if (active) notify({ tone: 'error', title: 'Could not load login history', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token, page, outcome, username, reload, notify]);

  const current = result?.content ?? [];
  const successes = current.filter((attempt) => attempt.outcome === 'SUCCESS').length;
  const failures = current.filter((attempt) => attempt.outcome === 'FAILURE').length;

  function exportCsv() {
    if (!current.length) return;
    const rows = [
      ['id', 'username', 'outcome', 'authenticatedUserId', 'attemptedAt'],
      ...current.map((attempt) => [attempt.id, attempt.attemptedUsername, attempt.outcome, attempt.authenticatedUserId ?? '', attempt.attemptedAt]),
    ];
    const csv = rows.map((row) => row.map((value) => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\r\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'zero-trust-login-history.csv'; anchor.click(); URL.revokeObjectURL(url);
  }

  return (
    <>
      <PageHeading eyebrow="AUDIT TRAIL / AUTHENTICATION" title="Login history" description="Successful and rejected sign-ins, without storing passwords, tokens, or client IP addresses." action={<Button variant="secondary" icon={RefreshCw} onClick={() => setReload((value) => value + 1)} disabled={loading}>Refresh</Button>} />
      <div className="audit-summary-strip auth-summary-strip"><div><span className="audit-summary-icon audit-icon-slate"><Fingerprint size={16} /></span><span><small>TOTAL ATTEMPTS</small><b>{result?.totalElements ?? '—'}</b></span></div><i /><div><span className="audit-summary-icon audit-icon-green"><UserRoundCheck size={16} /></span><span><small>SUCCESS · THIS PAGE</small><b>{successes}</b></span></div><i /><div><span className="audit-summary-icon audit-icon-red"><UserRoundX size={16} /></span><span><small>FAILURE · THIS PAGE</small><b>{failures}</b></span></div><i /><span className="audit-summary-note"><ShieldCheck size={14} /> Append-only authentication history</span></div>
      <Panel className="table-panel">
        <div className="table-toolbar"><div><SectionTitle title="Authentication ledger" subtitle="Validated login attempts · newest first" /></div><Button variant="secondary" size="sm" icon={Download} disabled={!current.length} onClick={exportCsv}>Export page</Button></div>
        <div className="filter-toolbar"><div className="filter-toolbar-title"><Search size={15} /><span>FILTERS</span></div><label className="filter-field"><span>Outcome</span><select value={outcome} onChange={(event) => { setOutcome(event.target.value); setPage(0); }}><option value="">All outcomes</option><option value="SUCCESS">Success</option><option value="FAILURE">Failure</option></select></label><label className="search-field filter-search"><Search size={15} /><input aria-label="Filter by username" placeholder="Username" value={username} onChange={(event) => { setUsername(event.target.value); setPage(0); }} /></label><button className="filter-reset" onClick={() => { setOutcome(''); setUsername(''); setPage(0); }}>Reset</button></div>
        {loading ? <LoadingState label="Loading authentication history" /> : current.length ? <div className="table-scroll"><table className="data-table audit-table auth-table"><thead><tr><th>OUTCOME</th><th>ATTEMPTED USER</th><th>AUTHENTICATED ACCOUNT</th><th>ATTEMPT TIME</th><th>EVENT</th></tr></thead><tbody>{current.map((attempt) => <tr key={attempt.id}>
          <td><Badge tone={attempt.outcome === 'SUCCESS' ? 'green' : 'red'} dot>{attempt.outcome}</Badge></td>
          <td><div className="table-primary">{attempt.attemptedUsername}</div><div className="table-secondary">Username normalized for audit search</div></td>
          <td>{attempt.authenticatedUserId ? <span className="authenticated-user"><UserRoundCheck size={14} /> Account #{attempt.authenticatedUserId}</span> : <span className="failed-user"><UserRoundX size={14} /> Not authenticated</span>}</td>
          <td><span className="audit-time">{time(attempt.attemptedAt)}</span></td>
          <td><span className="audit-event-id"><CalendarClock size={13} /> AUTH-{attempt.id}</span></td>
        </tr>)}</tbody></table></div> : <EmptyState icon={ShieldAlert} title="No login attempts found" detail="Sign-in activity will be recorded here as requests are admitted to authentication." />}
        {result && <Pagination page={result.page} totalPages={result.totalPages} totalElements={result.totalElements} onPageChange={setPage} />}
      </Panel>
      <div className="audit-footnote"><ShieldCheck size={14} /><span>Rate-limited requests return 429 before password verification and are not included in this audit table.</span></div>
    </>
  );
}
