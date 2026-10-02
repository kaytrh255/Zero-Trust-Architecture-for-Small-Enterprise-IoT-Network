import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { ArrowUpRight, Check, Edit3, FileKey2, Plus, RefreshCw, Search, ShieldCheck, ShieldX, Trash2, type LucideIcon } from 'lucide-react';
import { api } from '../lib/api';
import type { Policy, PolicyAction, PolicyEffect, UserProfile } from '../types';
import type { Notify } from '../App';
import { Badge, Button, EmptyState, LoadingState, Modal, PageHeading, Panel, SectionTitle } from '../components/ui';

interface PolicyFormData {
  name: string;
  subject: string;
  resource: string;
  action: PolicyAction;
  effect: PolicyEffect;
  enabled: boolean;
  description: string;
}

const emptyForm: PolicyFormData = { name: '', subject: 'SENSOR', resource: 'device-telemetry', action: 'WRITE', effect: 'ALLOW', enabled: true, description: '' };

export function PoliciesPage({ token, user, notify }: { token: string; user: UserProfile; notify: Notify }) {
  const canManage = user.role === 'ADMIN';
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const [search, setSearch] = useState('');
  const [effectFilter, setEffectFilter] = useState('ALL');
  const [editor, setEditor] = useState<Policy | 'new' | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    api.policies(token).then((result) => { if (active) setPolicies(result); }).catch((cause: unknown) => {
      if (active) notify({ tone: 'error', title: 'Could not load policy rules', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token, reload, notify]);

  const filtered = useMemo(() => {
    const query = search.trim().toLowerCase();
    return policies.filter((policy) => {
      const matchesText = !query || [policy.name, policy.subject, policy.resource, policy.description].some((value) => value?.toLowerCase().includes(query));
      const matchesEffect = effectFilter === 'ALL' || policy.effect === effectFilter;
      return matchesText && matchesEffect;
    });
  }, [policies, search, effectFilter]);

  async function save(form: PolicyFormData, policy?: Policy) {
    try {
      const result = await api.savePolicy(token, form, policy?.id);
      setPolicies((current) => policy ? current.map((item) => item.id === result.id ? result : item) : [...current, result].sort((a, b) => a.name.localeCompare(b.name)));
      setEditor(null);
      notify({ tone: 'success', title: policy ? 'Policy updated' : 'Policy created', detail: `${result.name} is ${result.enabled ? 'enabled' : 'disabled'}.` });
    } catch (cause) {
      throw cause;
    }
  }

  async function toggle(policy: Policy) {
    try {
      const result = await api.savePolicy(token, { name: policy.name, subject: policy.subject, resource: policy.resource, action: policy.action, effect: policy.effect, description: policy.description ?? '', enabled: !policy.enabled }, policy.id);
      setPolicies((current) => current.map((item) => item.id === result.id ? result : item));
      notify({ tone: 'success', title: result.enabled ? 'Policy enabled' : 'Policy disabled', detail: result.name });
    } catch (cause) {
      notify({ tone: 'error', title: 'Policy state was not changed', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }
  }

  async function remove(policy: Policy) {
    if (!window.confirm(`Delete policy “${policy.name}”? The change will remain in the immutable policy history.`)) return;
    try {
      await api.deletePolicy(token, policy.id);
      setPolicies((current) => current.filter((item) => item.id !== policy.id));
      notify({ tone: 'success', title: 'Policy deleted', detail: 'The deletion snapshot remains in policy history.' });
    } catch (cause) {
      notify({ tone: 'error', title: 'Policy was not deleted', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }
  }

  return (
    <>
      <PageHeading
        eyebrow="AUTHORIZATION / POLICY ENGINE"
        title="Policy engine"
        description="Exact-match rules decide what each authenticated identity may do. No match means deny."
        action={<div className="page-actions"><Button variant="secondary" size="sm" icon={RefreshCw} onClick={() => setReload((value) => value + 1)} disabled={loading}>Refresh</Button>{canManage ? <Button icon={Plus} onClick={() => setEditor('new')}>Create policy</Button> : <Badge tone="blue"><ShieldCheck size={13} /> READ ONLY</Badge>}</div>}
      />
      <div className="policy-principle-banner"><span className="policy-principle-icon"><ShieldCheck size={17} /></span><span><b>Explicit deny takes precedence.</b><small>Enabled rules are evaluated by subject, resource, and action. Unmatched requests fail closed.</small></span><span className="policy-principle-label">DEFAULT <b>DENY</b></span></div>
      <div className="policy-metrics-grid">
        <PolicyStat icon={FileKey2} label="Total rules" value={policies.length} tone="blue" />
        <PolicyStat icon={ShieldCheck} label="Allow rules" value={policies.filter((policy) => policy.effect === 'ALLOW').length} tone="green" />
        <PolicyStat icon={ShieldX} label="Explicit denies" value={policies.filter((policy) => policy.effect === 'DENY').length} tone="red" />
        <PolicyStat icon={Check} label="Enabled" value={policies.filter((policy) => policy.enabled).length} tone="violet" />
      </div>
      <Panel className="table-panel">
        <div className="table-toolbar"><div><SectionTitle title="Authorization rules" subtitle="Exact subject · resource · action matching" /></div><div className="table-tools"><label className="search-field"><Search size={15} /><input aria-label="Search policies" placeholder="Search rules..." value={search} onChange={(event) => setSearch(event.target.value)} /></label><select className="toolbar-select" aria-label="Filter by effect" value={effectFilter} onChange={(event) => setEffectFilter(event.target.value)}><option value="ALL">All effects</option><option value="ALLOW">Allow</option><option value="DENY">Deny</option></select></div></div>
        {loading ? <LoadingState label="Loading policy rules" /> : filtered.length ? <div className="table-scroll"><table className="data-table policies-table"><thead><tr><th>POLICY</th><th>SUBJECT</th><th>RESOURCE</th><th>ACTION</th><th>EFFECT</th><th>STATE</th><th className="align-right">ACTIONS</th></tr></thead><tbody>
          {filtered.map((policy) => <tr key={policy.id}>
            <td><div className="table-primary">{policy.name}</div><div className="table-secondary">ID #{policy.id}</div></td>
            <td><Badge tone="blue">{policy.subject}</Badge></td>
            <td><span className="mono-text">{policy.resource}</span></td>
            <td><span className="action-pill">{policy.action}</span></td>
            <td><Badge tone={policy.effect === 'ALLOW' ? 'green' : 'red'} dot>{policy.effect}</Badge></td>
            <td><button className={`switch ${policy.enabled ? 'switch-on' : ''}`} role="switch" aria-checked={policy.enabled} aria-label={`${policy.enabled ? 'Disable' : 'Enable'} ${policy.name}`} disabled={!canManage} onClick={() => void toggle(policy)}><i /></button><span className="switch-label">{policy.enabled ? 'Enabled' : 'Paused'}</span></td>
            <td className="align-right"><div className="row-actions">{canManage && <><button className="row-icon-button" title="Edit policy" aria-label={`Edit ${policy.name}`} onClick={() => setEditor(policy)}><Edit3 size={15} /></button><button className="row-icon-button row-icon-danger" title="Delete policy" aria-label={`Delete ${policy.name}`} onClick={() => void remove(policy)}><Trash2 size={15} /></button></>}<span className="policy-id-arrow"><ArrowUpRight size={15} /></span></div></td>
          </tr>)}
        </tbody></table></div> : <EmptyState icon={FileKey2} title={search || effectFilter !== 'ALL' ? 'No rules match this filter' : 'No policies configured'} detail="Create a rule or adjust your search to inspect the authorization model." action={canManage && !search ? <Button icon={Plus} onClick={() => setEditor('new')}>Create policy</Button> : undefined} />}
        <div className="table-footer"><span>{filtered.length} rules shown <i /> Policy changes are recorded immutably</span><span>AUTHORIZATION / EXACT MATCH</span></div>
      </Panel>
      <div className="policy-footnote"><ShieldCheck size={14} /><span>Policy checks are evaluated after device identity and active status. A signed message does not bypass an explicit DENY.</span><button onClick={() => notify({ tone: 'info', title: 'Policy evaluation order', detail: 'Identity → active status → explicit deny → exact match → default deny.' })}>How it works <ArrowUpRight size={13} /></button></div>
      {editor && <PolicyEditor policy={editor === 'new' ? undefined : editor} onClose={() => setEditor(null)} onSave={save} />}
    </>
  );
}

function PolicyStat({ icon: Icon, label, value, tone }: { icon: LucideIcon; label: string; value: number; tone: string }) {
  return <Panel className="policy-stat"><span className={`metric-icon metric-icon-${tone}`}><Icon size={17} /></span><div><small>{label}</small><b>{value}</b></div></Panel>;
}

function PolicyEditor({ policy, onClose, onSave }: { policy?: Policy; onClose: () => void; onSave: (form: PolicyFormData, policy?: Policy) => Promise<void> }) {
  const [form, setForm] = useState<PolicyFormData>(policy ? { name: policy.name, subject: policy.subject, resource: policy.resource, action: policy.action, effect: policy.effect, enabled: policy.enabled, description: policy.description ?? '' } : emptyForm);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  function change<K extends keyof PolicyFormData>(key: K, value: PolicyFormData[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError('');
    try { await onSave(form, policy); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Unable to save this policy.'); }
    finally { setBusy(false); }
  }

  return (
    <Modal title={policy ? 'Edit policy' : 'Create policy'} eyebrow="POLICY RULE" description="Choose the exact identity, resource, and action this rule governs." onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <div className="form-grid">
          <label className="form-grid-wide"><span>Policy name</span><input required maxLength={100} value={form.name} onChange={(event) => change('name', event.target.value)} placeholder="Sensor telemetry write" /></label>
          <label><span>Subject</span><input required pattern="[A-Za-z0-9._-]{1,50}" maxLength={50} value={form.subject} onChange={(event) => change('subject', event.target.value.toUpperCase())} placeholder="SENSOR" /><small>Matches a registered device type or role</small></label>
          <label><span>Resource</span><input required pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}" maxLength={100} value={form.resource} onChange={(event) => change('resource', event.target.value.toLowerCase())} placeholder="device-telemetry" /></label>
          <label><span>Action</span><select value={form.action} onChange={(event) => change('action', event.target.value as PolicyAction)}><option value="READ">READ</option><option value="WRITE">WRITE</option><option value="EXECUTE">EXECUTE</option></select></label>
          <label><span>Effect</span><select value={form.effect} onChange={(event) => change('effect', event.target.value as PolicyEffect)}><option value="ALLOW">ALLOW</option><option value="DENY">DENY</option></select></label>
          <label className="form-grid-wide"><span>Description <small>Optional</small></span><textarea rows={3} maxLength={500} value={form.description} onChange={(event) => change('description', event.target.value)} placeholder="Explain the intent and scope of this rule." /></label>
        </div>
        <label className="form-checkbox"><input type="checkbox" checked={form.enabled} onChange={(event) => change('enabled', event.target.checked)} /><span><b>Enable this policy</b><small>Disabled rules do not participate in evaluation.</small></span></label>
        {error && <div className="form-error" role="alert">{error}</div>}
        <div className="modal-actions"><Button variant="secondary" type="button" onClick={onClose}>Cancel</Button><Button icon={busy ? undefined : ShieldCheck} type="submit" disabled={busy}>{busy ? 'Saving…' : policy ? 'Save changes' : 'Create rule'}</Button></div>
      </form>
    </Modal>
  );
}
