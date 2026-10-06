import { useEffect, useMemo, useState } from 'react';
import { CalendarClock, KeyRound, RefreshCw, Search, ShieldAlert, ShieldCheck, UserRoundCog, Users, type LucideIcon } from 'lucide-react';
import { api } from '../lib/api';
import type { AuditPage, ManagedUserAccount, UserAccountAudit, UserProfile, UserRole } from '../types';
import type { Notify } from '../App';
import { Badge, Button, EmptyState, LoadingState, PageHeading, Pagination, Panel, SectionTitle } from '../components/ui';

type AccountDraft = { role: UserRole; enabled: boolean };

const time = (value: string) => new Date(value).toLocaleString(undefined, {
  month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit',
});

const roleLabel = (role: UserRole) => ({
  ADMIN: 'ADMIN',
  SECURITY_ANALYST: 'SECURITY ANALYST',
  USER: 'USER',
  DEVICE: 'DEVICE · INTERNAL',
}[role]);

export function UserAccountsPage({ token, user, notify }: { token: string; user: UserProfile; notify: Notify }) {
  const [accounts, setAccounts] = useState<ManagedUserAccount[]>([]);
  const [drafts, setDrafts] = useState<Record<number, AccountDraft>>({});
  const [auditResult, setAuditResult] = useState<AuditPage<UserAccountAudit> | null>(null);
  const [accountsLoading, setAccountsLoading] = useState(true);
  const [auditLoading, setAuditLoading] = useState(true);
  const [savingId, setSavingId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [auditPage, setAuditPage] = useState(0);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    setAccountsLoading(true);
    api.adminUserAccounts(token).then((result) => {
      if (!active) return;
      setAccounts(result);
      setDrafts(Object.fromEntries(result.map((account) => [account.id, { role: account.role, enabled: account.enabled }])));
    }).catch((cause: unknown) => {
      if (active) notify({ tone: 'error', title: 'Could not load user accounts', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }).finally(() => { if (active) setAccountsLoading(false); });
    return () => { active = false; };
  }, [token, reload, notify]);

  useEffect(() => {
    let active = true;
    setAuditLoading(true);
    api.adminUserAccountAudits(token, auditPage).then((result) => {
      if (active) setAuditResult(result);
    }).catch((cause: unknown) => {
      if (active) notify({ tone: 'error', title: 'Could not load account change history', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }).finally(() => { if (active) setAuditLoading(false); });
    return () => { active = false; };
  }, [token, auditPage, reload, notify]);

  const filteredAccounts = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return accounts;
    return accounts.filter((account) => [account.username, account.fullName, account.role].some((value) => value.toLowerCase().includes(query)));
  }, [accounts, search]);

  const enabledAdmins = accounts.filter((account) => account.enabled && account.role === 'ADMIN').length;
  const privileged = accounts.filter((account) => account.role === 'ADMIN' || account.role === 'SECURITY_ANALYST');
  const mfaProtected = privileged.filter((account) => account.mfaEnabled).length;

  function updateDraft(account: ManagedUserAccount, patch: Partial<AccountDraft>) {
    setDrafts((current) => {
      const previous = current[account.id] ?? { role: account.role, enabled: account.enabled };
      return { ...current, [account.id]: { ...previous, ...patch } };
    });
  }

  async function save(account: ManagedUserAccount) {
    const draft = drafts[account.id] ?? { role: account.role, enabled: account.enabled };
    if (draft.role === 'DEVICE') return;
    setSavingId(account.id);
    try {
      const updated = await api.updateAdminUserAccount(token, account.id, draft.role, draft.enabled);
      setAccounts((current) => current.map((item) => item.id === updated.id ? updated : item));
      setDrafts((current) => ({ ...current, [updated.id]: { role: updated.role, enabled: updated.enabled } }));
      setAuditPage(0);
      setReload((value) => value + 1);
      notify({
        tone: 'success',
        title: 'Account access updated',
        detail: `${updated.username} · ${roleLabel(updated.role)} · ${updated.enabled ? 'enabled' : 'disabled'}; active sessions revoked.`,
      });
    } catch (cause) {
      notify({ tone: 'error', title: 'Account was not changed', detail: cause instanceof Error ? cause.message : 'Try again.' });
    } finally {
      setSavingId(null);
    }
  }

  return (
    <>
      <PageHeading
        eyebrow="IDENTITY / ACCOUNT LIFECYCLE"
        title="User accounts"
        description="Review account access, assign least-privilege roles, and disable accounts that should no longer sign in."
        action={<Button variant="secondary" icon={RefreshCw} onClick={() => setReload((value) => value + 1)} disabled={accountsLoading || auditLoading}>Refresh</Button>}
      />

      <div className="account-summary-grid">
        <AccountSummary icon={Users} label="Registered accounts" value={accounts.length} tone="blue" />
        <AccountSummary icon={ShieldCheck} label="Enabled administrators" value={enabledAdmins} tone="green" />
        <AccountSummary icon={KeyRound} label="Privileged accounts with MFA" value={`${mfaProtected} / ${privileged.length}`} tone="violet" />
        <AccountSummary icon={UserRoundCog} label="Disabled accounts" value={accounts.filter((account) => !account.enabled).length} tone="amber" />
      </div>

      <div className="account-security-banner">
        <span className="account-security-icon"><ShieldAlert size={17} /></span>
        <span><b>Every access change is recorded and revokes existing sessions.</b><small>Self role/status changes and removal of the last enabled ADMIN are blocked. DEVICE is reserved for internal identities.</small></span>
      </div>

      <Panel className="table-panel">
        <div className="table-toolbar account-table-toolbar">
          <div><SectionTitle title="Account directory" subtitle="Role and status are enforced by the backend on every request" /></div>
          <label className="search-field"><Search size={15} /><input aria-label="Search accounts" placeholder="Search name, username, role…" value={search} onChange={(event) => setSearch(event.target.value)} /></label>
        </div>
        {accountsLoading ? <LoadingState label="Loading account directory" /> : filteredAccounts.length ? <div className="table-scroll">
          <table className="data-table account-table"><thead><tr><th>ACCOUNT</th><th>ROLE</th><th>MFA</th><th>STATUS</th><th>UPDATED</th><th className="align-right">ACTION</th></tr></thead><tbody>
            {filteredAccounts.map((account) => {
              const draft = drafts[account.id] ?? { role: account.role, enabled: account.enabled };
              const isSelf = account.id === user.id;
              const isDirty = draft.role !== account.role || draft.enabled !== account.enabled;
              const busy = savingId === account.id;
              const canSave = !isSelf && isDirty && draft.role !== 'DEVICE' && !busy;
              return <tr key={account.id}>
                <td><div className="account-identity"><span className="account-avatar"><Users size={15} /></span><span><b>{account.fullName}</b><small>@{account.username} · ID #{account.id}</small></span>{isSelf && <Badge tone="violet">YOU</Badge>}</div></td>
                <td><select className="account-role-select" aria-label={`Role for ${account.username}`} value={draft.role} disabled={isSelf || busy} onChange={(event) => updateDraft(account, { role: event.target.value as UserRole })}>
                  {account.role === 'DEVICE' && <option value="DEVICE" disabled>DEVICE · INTERNAL</option>}
                  <option value="USER">USER</option><option value="SECURITY_ANALYST">SECURITY ANALYST</option><option value="ADMIN">ADMIN</option>
                </select></td>
                <td><Badge tone={account.mfaEnabled ? 'green' : 'amber'} dot>{account.mfaEnabled ? 'ENABLED' : 'NOT ENROLLED'}</Badge></td>
                <td><button className={`switch ${draft.enabled ? 'switch-on' : ''}`} role="switch" aria-checked={draft.enabled} aria-label={`${draft.enabled ? 'Disable' : 'Enable'} ${account.username}`} disabled={isSelf || busy || draft.role === 'DEVICE'} onClick={() => updateDraft(account, { enabled: !draft.enabled })}><i /></button><span className={`account-state ${draft.enabled ? 'account-state-enabled' : 'account-state-disabled'}`}>{draft.enabled ? 'Enabled' : 'Disabled'}</span></td>
                <td><span className="account-updated"><CalendarClock size={13} />{time(account.updatedAt)}</span></td>
                <td className="align-right"><Button size="sm" variant={isDirty ? 'primary' : 'secondary'} disabled={!canSave} onClick={() => void save(account)}>{busy ? 'Saving…' : 'Save'}</Button></td>
              </tr>;
            })}
          </tbody></table>
        </div> : <EmptyState icon={Users} title={search ? 'No accounts match this search' : 'No user accounts found'} detail="Newly registered accounts will appear here for administrative review." />}
        <div className="table-footer"><span>{filteredAccounts.length} of {accounts.length} accounts <i /> Role and status changes are audited</span><span>IDENTITY / LEAST PRIVILEGE</span></div>
      </Panel>

      <Panel className="table-panel account-audit-panel">
        <div className="table-toolbar"><div><SectionTitle title="Account change history" subtitle="Append-only record · newest changes first" /></div><Badge tone="blue"><ShieldCheck size={13} /> ACTOR + TARGET SNAPSHOTS</Badge></div>
        {auditLoading ? <LoadingState label="Loading account change history" /> : auditResult?.content.length ? <div className="table-scroll">
          <table className="data-table account-audit-table"><thead><tr><th>TARGET</th><th>ACCESS CHANGE</th><th>ACTOR</th><th>CHANGED AT</th><th>EVENT</th></tr></thead><tbody>
            {auditResult.content.map((event) => <tr key={event.id}>
              <td><div className="table-primary">{event.targetUsername}</div><div className="table-secondary">Account #{event.targetUserId}</div></td>
              <td><div className="account-audit-change"><Badge tone="slate">{roleLabel(event.previousRole)}</Badge><span>→</span><Badge tone={event.newRole === 'ADMIN' ? 'violet' : event.newRole === 'SECURITY_ANALYST' ? 'blue' : 'slate'}>{roleLabel(event.newRole)}</Badge></div><div className="table-secondary">{event.previousEnabled ? 'Enabled' : 'Disabled'} → {event.newEnabled ? 'Enabled' : 'Disabled'}</div></td>
              <td><div className="table-primary">{event.actorUsername}</div><div className="table-secondary">Account #{event.actorUserId}</div></td>
              <td><span className="account-updated"><CalendarClock size={13} />{time(event.changedAt)}</span></td>
              <td><span className="audit-event-id"><CalendarClock size={13} />USER-{event.id}</span></td>
            </tr>)}
          </tbody></table>
        </div> : <EmptyState icon={ShieldCheck} title="No account changes recorded" detail="Successful role or enabled-status changes will be added here." />}
        {auditResult && <Pagination page={auditResult.page} totalPages={auditResult.totalPages} totalElements={auditResult.totalElements} onPageChange={setAuditPage} />}
      </Panel>
      <div className="audit-footnote"><ShieldCheck size={14} /><span>Every effective change increments the account session version, clears pending MFA login challenges, and commits with its immutable audit event.</span></div>
    </>
  );
}

function AccountSummary({ icon: Icon, label, value, tone }: { icon: LucideIcon; label: string; value: number | string; tone: string }) {
  return <Panel className="account-summary"><span className={`summary-icon summary-icon-${tone}`}><Icon size={17} /></span><div><small>{label}</small><b>{value}</b></div></Panel>;
}
