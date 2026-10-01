import { useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  ArrowUpRight, Clock3, Copy, Cpu, Eye, EyeOff, Fingerprint, KeyRound, MoreHorizontal,
  Plus, RadioTower, RefreshCw, RotateCw, Search, ShieldCheck, ShieldOff, UserRound,
} from 'lucide-react';
import { api, ApiError } from '../lib/api';
import type { Device, DeviceProvisioningResponse, DeviceStatus, DeviceType, UserProfile } from '../types';
import type { Notify } from '../App';
import { Badge, Button, EmptyState, LoadingState, Modal, PageHeading, Panel, SectionTitle } from '../components/ui';

const dateTime = (value?: string | null) => value ? new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : 'Never seen';
const statusTone = (status: DeviceStatus) => status === 'ACTIVE' ? 'green' : status === 'INACTIVE' ? 'amber' : 'red';

export function DevicesPage({ token, user, notify }: { token: string; user: UserProfile; notify: Notify }) {
  const canManage = user.role === 'ADMIN';
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const [search, setSearch] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [selectedDevice, setSelectedDevice] = useState<Device | null>(null);
  const [secrets, setSecrets] = useState<DeviceProvisioningResponse | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    api.devices(token).then((result) => { if (active) setDevices(result); }).catch((cause: unknown) => {
      if (active) notify({ tone: 'error', title: 'Could not load device identities', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token, reload, notify]);

  const filtered = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return devices;
    return devices.filter((device) => [device.deviceCode, device.deviceName, device.deviceType, device.status, device.ownerUsername].some((value) => value?.toLowerCase().includes(query)));
  }, [devices, search]);

  async function createDevice(body: { deviceCode: string; deviceName: string; deviceType: DeviceType; ipAddress: string; mqttClientId: string }) {
    try {
      const created = await api.createDevice(token, body);
      setDevices((current) => [...current, created.device].sort((a, b) => a.deviceCode.localeCompare(b.deviceCode)));
      setCreateOpen(false);
      setSecrets(created);
      notify({ tone: 'success', title: `${created.device.deviceCode} provisioned`, detail: 'Save its MQTT password and signing key now.' });
    } catch (cause) {
      throw cause;
    }
  }

  async function rotate(device: Device) {
    try {
      const response = await api.rotateDevice(token, device.id);
      setDevices((current) => current.map((item) => item.id === device.id ? response.device : item));
      setSelectedDevice(null);
      setSecrets(response);
      notify({ tone: 'success', title: 'Device credentials rotated', detail: 'The previous MQTT password and signing key are now invalid.' });
    } catch (cause) {
      notify({ tone: 'error', title: 'Credential rotation failed', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }
  }

  async function changeStatus(device: Device, status: DeviceStatus) {
    try {
      const updated = await api.updateDeviceStatus(token, device.id, status);
      setDevices((current) => current.map((item) => item.id === updated.id ? updated : item));
      setSelectedDevice(updated);
      notify({ tone: 'success', title: `${device.deviceCode} is ${status.toLowerCase()}`, detail: 'The transition was recorded in the device status history.' });
    } catch (cause) {
      notify({ tone: 'error', title: 'Status update failed', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }
  }

  async function transfer(device: Device, ownerUsername: string) {
    try {
      const updated = await api.transferDevice(token, device.id, ownerUsername);
      setDevices((current) => current.map((item) => item.id === updated.id ? updated : item));
      setSelectedDevice(updated);
      notify({ tone: 'success', title: 'Device ownership updated', detail: `New owner: ${updated.ownerUsername ?? ownerUsername}` });
    } catch (cause) {
      notify({ tone: 'error', title: 'Ownership transfer failed', detail: cause instanceof Error ? cause.message : 'Try again.' });
      throw cause;
    }
  }

  async function revoke(device: Device) {
    if (!window.confirm(`Revoke ${device.deviceCode}? Its device identity will be disabled.`)) return;
    try {
      await api.revokeDevice(token, device.id);
      setDevices((current) => current.map((item) => item.id === device.id ? { ...item, status: 'REVOKED' } : item));
      setSelectedDevice(null);
      notify({ tone: 'success', title: 'Device identity revoked', detail: `${device.deviceCode} can no longer send accepted telemetry.` });
    } catch (cause) {
      notify({ tone: 'error', title: 'Device revocation failed', detail: cause instanceof Error ? cause.message : 'Try again.' });
    }
  }

  return (
    <>
      <PageHeading
        eyebrow="IDENTITY REGISTRY / DEVICES"
        title="Device identities"
        description="Provision, inspect, and govern the identities permitted to communicate with your broker."
        action={canManage ? <Button icon={Plus} onClick={() => setCreateOpen(true)}>Provision device</Button> : <Badge tone="blue"><ShieldCheck size={13} /> READ ONLY</Badge>}
      />
      <div className="device-summary-row">
        <div className="device-summary"><span className="summary-icon summary-icon-blue"><RadioTower size={17} /></span><div><small>REGISTERED</small><b>{devices.length}</b></div></div>
        <div className="device-summary"><span className="summary-icon summary-icon-green"><ShieldCheck size={17} /></span><div><small>ACTIVE</small><b>{devices.filter((device) => device.status === 'ACTIVE').length}</b></div></div>
        <div className="device-summary"><span className="summary-icon summary-icon-violet"><Fingerprint size={17} /></span><div><small>SIGNING ENABLED</small><b>{devices.filter((device) => device.mqttSignatureEnabled).length}</b></div></div>
        <div className="device-summary"><span className="summary-icon summary-icon-amber"><Clock3 size={17} /></span><div><small>AWAITING KEY ROTATION</small><b>{devices.filter((device) => !device.mqttSignatureEnabled).length}</b></div></div>
      </div>
      <Panel className="table-panel">
        <div className="table-toolbar"><div><SectionTitle title="Registered fleet" subtitle="Device identifiers and enforcement state" /></div><div className="table-tools"><label className="search-field"><Search size={15} /><input aria-label="Search devices" placeholder="Find a device..." value={search} onChange={(event) => setSearch(event.target.value)} /></label><button className="icon-button" aria-label="Refresh devices" title="Refresh" onClick={() => setReload((value) => value + 1)}><RefreshCw size={16} /></button></div></div>
        {loading ? <LoadingState label="Loading registered identities" /> : filtered.length ? <div className="table-scroll"><table className="data-table device-table"><thead><tr><th>DEVICE</th><th>STATUS</th><th>SIGNATURE</th><th>OWNER</th><th>LAST SEEN</th><th className="align-right">ACTIONS</th></tr></thead><tbody>
          {filtered.map((device) => <tr key={device.id}>
            <td><div className="device-name-cell"><span className="device-type-icon"><Cpu size={16} /></span><span><b>{device.deviceName}</b><small><span className="mono-text">{device.deviceCode}</span> · {device.deviceType}</small></span></div></td>
            <td><Badge tone={statusTone(device.status)} dot>{device.status}</Badge></td>
            <td>{device.mqttSignatureEnabled ? <span className="signature-enabled"><ShieldCheck size={14} /> Ed25519</span> : <Badge tone="amber">KEY REQUIRED</Badge>}</td>
            <td><span className="owner-cell"><UserRound size={14} />{device.ownerUsername ?? 'Unassigned'}</span></td>
            <td><span className="table-secondary">{dateTime(device.lastSeenAt)}</span></td>
            <td className="align-right"><div className="row-actions">{canManage && <button className="table-action" onClick={() => setSelectedDevice(device)}><MoreHorizontal size={17} /><span>Manage</span></button>}<button className="row-open" aria-label={`Open ${device.deviceCode}`} onClick={() => setSelectedDevice(device)}><ArrowUpRight size={16} /></button></div></td>
          </tr>)}
        </tbody></table></div> : <EmptyState icon={RadioTower} title={search ? 'No matching devices' : 'No devices provisioned'} detail={search ? 'Try a different device name or identifier.' : 'Provision a device to create a broker identity and signing key.'} action={!search && canManage ? <Button icon={Plus} onClick={() => setCreateOpen(true)}>Provision first device</Button> : undefined} />}
        <div className="table-footer"><span>{filtered.length} identities shown <i /> Registry is the source of truth</span><span>MQTT IDENTITY / V12</span></div>
      </Panel>

      <div className="device-security-note"><span className="security-note-icon"><KeyRound size={16} /></span><span><b>Private keys are shown once.</b> The database stores only the signing public key. A rotation invalidates the old broker password and Ed25519 key pair.</span><button onClick={() => notify({ tone: 'info', title: 'Protect one-time secrets', detail: 'Copy credentials into the device secret store. They are never retained by this browser.' })}>Security note <ArrowUpRight size={13} /></button></div>

      {createOpen && <CreateDeviceModal onClose={() => setCreateOpen(false)} onCreate={createDevice} />}
      {selectedDevice && <ManageDeviceModal device={selectedDevice} readOnly={!canManage} onClose={() => setSelectedDevice(null)} onRotate={rotate} onStatus={changeStatus} onTransfer={transfer} onRevoke={revoke} />}
      {secrets && <ProvisioningModal response={secrets} onClose={() => setSecrets(null)} />}
    </>
  );
}

function CreateDeviceModal({ onClose, onCreate }: { onClose: () => void; onCreate: (body: { deviceCode: string; deviceName: string; deviceType: DeviceType; ipAddress: string; mqttClientId: string }) => Promise<void> }) {
  const [deviceCode, setDeviceCode] = useState('');
  const [deviceName, setDeviceName] = useState('');
  const [deviceType, setDeviceType] = useState<DeviceType>('SENSOR');
  const [ipAddress, setIpAddress] = useState('');
  const [mqttClientId, setMqttClientId] = useState('');
  const [clientIdEdited, setClientIdEdited] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await onCreate({ deviceCode: deviceCode.trim().toUpperCase(), deviceName: deviceName.trim(), deviceType, ipAddress: ipAddress.trim(), mqttClientId: (clientIdEdited ? mqttClientId : deviceCode).trim().toUpperCase() });
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : 'Unable to create device.');
    } finally { setBusy(false); }
  }

  return (
    <Modal title="Provision device" eyebrow="NEW DEVICE IDENTITY" description="Create a broker identity and issue a one-time Ed25519 signing key." onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <div className="form-grid">
          <label><span>Device code</span><input autoFocus required minLength={2} maxLength={64} pattern="[A-Za-z0-9][A-Za-z0-9._-]{1,63}" value={deviceCode} onChange={(event) => { setDeviceCode(event.target.value.toUpperCase()); if (!clientIdEdited) setMqttClientId(event.target.value.toUpperCase()); }} placeholder="SENSOR-003" /><small>2–64 characters · used as MQTT username</small></label>
          <label><span>Device name</span><input required maxLength={100} value={deviceName} onChange={(event) => setDeviceName(event.target.value)} placeholder="North wing temperature sensor" /></label>
          <label><span>Device type</span><select value={deviceType} onChange={(event) => setDeviceType(event.target.value as DeviceType)}><option value="SENSOR">Sensor</option><option value="CAMERA">Camera</option><option value="ACTUATOR">Actuator</option><option value="GATEWAY">Gateway</option></select></label>
          <label><span>Network address</span><input required maxLength={45} value={ipAddress} onChange={(event) => setIpAddress(event.target.value)} placeholder="192.168.10.24" /></label>
          <label className="form-grid-wide"><span>MQTT client ID</span><input required maxLength={100} pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,99}" value={clientIdEdited ? mqttClientId : deviceCode} onChange={(event) => { setClientIdEdited(true); setMqttClientId(event.target.value); }} placeholder="SENSOR-003" /><small>Unique and immutable after provisioning</small></label>
        </div>
        <div className="form-callout"><KeyRound size={15} /><span>The MQTT password and signing private key are returned once. Have the device secret store ready before you continue.</span></div>
        {error && <div className="form-error" role="alert">{error}</div>}
        <div className="modal-actions"><Button variant="secondary" type="button" onClick={onClose}>Cancel</Button><Button icon={busy ? undefined : Plus} type="submit" disabled={busy}>{busy ? 'Provisioning…' : 'Create identity'}</Button></div>
      </form>
    </Modal>
  );
}

function ManageDeviceModal({
  device,
  readOnly,
  onClose,
  onRotate,
  onStatus,
  onTransfer,
  onRevoke,
}: {
  device: Device;
  readOnly: boolean;
  onClose: () => void;
  onRotate: (device: Device) => Promise<void>;
  onStatus: (device: Device, status: DeviceStatus) => Promise<void>;
  onTransfer: (device: Device, ownerUsername: string) => Promise<void>;
  onRevoke: (device: Device) => Promise<void>;
}) {
  const [ownerUsername, setOwnerUsername] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function transfer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ownerUsername.trim()) return;
    setBusy(true); setError('');
    try { await onTransfer(device, ownerUsername.trim()); setOwnerUsername(''); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Transfer failed.'); }
    finally { setBusy(false); }
  }

  return (
    <Modal title={device.deviceName} eyebrow={`DEVICE / ${device.deviceCode}`} description={readOnly ? 'Inspect the registered identity and current enforcement posture.' : 'Review identity posture and perform an administrator action.'} onClose={onClose}>
      <div className="device-detail-summary"><span className="device-detail-mark"><Cpu size={19} /></span><div><b>{device.deviceType} · {device.mqttClientId}</b><small>{device.ipAddress} · Owner {device.ownerUsername ?? 'unassigned'}</small></div><Badge tone={statusTone(device.status)} dot>{device.status}</Badge></div>
      <div className="device-detail-list"><div><span>Signing key</span>{device.mqttSignatureEnabled ? <Badge tone="green" dot>ED25519 ENABLED</Badge> : <Badge tone="amber">ROTATION REQUIRED</Badge>}</div><div><span>Last seen</span><b>{dateTime(device.lastSeenAt)}</b></div><div><span>Provisioned</span><b>{dateTime(device.createdAt)}</b></div></div>
      {!readOnly && <div className="manage-actions">
        <div className="manage-action-row"><span className="manage-action-icon"><RotateCw size={16} /></span><span><b>Rotate credentials</b><small>Replaces the password and signing key pair.</small></span><Button size="sm" variant="secondary" onClick={() => { if (window.confirm(`Rotate credentials for ${device.deviceCode}? Existing credentials will stop working immediately.`)) void onRotate(device); }}>Rotate</Button></div>
        <div className="manage-action-row"><span className="manage-action-icon"><ShieldOff size={16} /></span><span><b>{device.status === 'ACTIVE' ? 'Block device' : 'Activate device'}</b><small>{device.status === 'ACTIVE' ? 'Deny telemetry at the backend.' : 'Restore active status checks.'}</small></span><Button size="sm" variant="secondary" disabled={device.status === 'REVOKED'} onClick={() => void onStatus(device, device.status === 'ACTIVE' ? 'BLOCKED' : 'ACTIVE')}>{device.status === 'ACTIVE' ? 'Block' : 'Activate'}</Button></div>
        <form className="manage-transfer" onSubmit={transfer}><label htmlFor="new-owner">Transfer ownership</label><div className="inline-form"><input id="new-owner" value={ownerUsername} onChange={(event) => setOwnerUsername(event.target.value)} placeholder="Enabled USER username" /><Button size="sm" variant="secondary" disabled={busy || !ownerUsername.trim()} type="submit">{busy ? 'Saving…' : 'Transfer'}</Button></div><small>Only an enabled USER account can own a device.</small></form>
        {error && <div className="form-error" role="alert">{error}</div>}
        <button className="revoke-link" disabled={device.status === 'REVOKED'} onClick={() => void onRevoke(device)}><ShieldOff size={14} /> Revoke this device identity</button>
      </div>}
    </Modal>
  );
}

function ProvisioningModal({ response, onClose }: { response: DeviceProvisioningResponse; onClose: () => void }) {
  const [visible, setVisible] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);
  const secrets = [
    { id: 'username', label: 'MQTT username', value: response.mqttUsername, note: 'Matches the device code' },
    { id: 'password', label: 'MQTT password', value: response.mqttPassword, note: 'Random 256-bit broker credential' },
    { id: 'signing', label: 'Signing private key', value: response.mqttSigningPrivateKey, note: 'Ed25519 · PKCS#8 DER · base64url' },
  ];

  async function copy(id: string, value: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(id);
      window.setTimeout(() => setCopied((current) => current === id ? null : current), 1800);
    } catch {
      setCopied('error');
    }
  }

  return (
    <Modal title="Save device credentials" eyebrow="ONE-TIME SECRET DELIVERY" description={`${response.device.deviceCode} is ready. These secrets cannot be retrieved again.`} onClose={onClose} size="wide">
      <div className="secret-warning"><span><ShieldOff size={16} /></span><p><b>Shown once. Store securely before closing.</b><small>Secrets are held only in this tab's memory. Closing this dialog clears them; rotate credentials if they are lost.</small></p></div>
      <div className="secret-device"><span className="secret-device-icon"><RadioTower size={17} /></span><span><b>{response.device.deviceName}</b><small>{response.device.deviceCode} · {response.device.deviceType}</small></span><Badge tone="green" dot>READY</Badge></div>
      <div className="secret-list">{secrets.map((secret) => <div className="secret-field" key={secret.id}><div className="secret-field-label"><span>{secret.label}</span><small>{secret.note}</small></div><div className="secret-value-row"><code className={visible ? 'secret-value-visible' : ''}>{visible || secret.id === 'username' ? secret.value : `${secret.value.slice(0, 5)}${'•'.repeat(Math.min(30, Math.max(12, secret.value.length - 5)))}`}</code><button className="icon-button" title={visible ? 'Hide secrets' : 'Reveal secrets'} onClick={() => setVisible((value) => !value)}>{visible ? <EyeOff size={16} /> : <Eye size={16} />}</button><button className="copy-button" onClick={() => void copy(secret.id, secret.value)}><Copy size={14} />{copied === secret.id ? 'Copied' : 'Copy'}</button></div></div>)}</div>
      {copied === 'error' && <div className="form-error">Clipboard access was blocked. Reveal the value and copy it manually.</div>}
      <div className="secret-footer"><span><ShieldCheck size={14} /> Cache-Control: no-store</span><Button onClick={onClose}>I saved these securely</Button></div>
    </Modal>
  );
}
