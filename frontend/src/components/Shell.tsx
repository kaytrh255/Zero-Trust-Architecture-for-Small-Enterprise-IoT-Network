import { useEffect, useState, type ReactNode } from 'react';
import {
  Activity, Bell, ChevronDown, Fingerprint, LayoutDashboard, LogOut, Menu, RadioTower,
  ScrollText, ShieldAlert, ShieldCheck, Waves, X,
} from 'lucide-react';
import { api } from '../lib/api';
import type { UserProfile } from '../types';
import type { Notice } from '../App';
import { Badge } from './ui';

export type ViewKey = 'overview' | 'devices' | 'policies' | 'access' | 'authentication' | 'telemetry';

const navigation = [
  { id: 'overview', label: 'Overview', group: 'Workspace', icon: LayoutDashboard },
  { id: 'devices', label: 'Device identities', group: 'Workspace', icon: RadioTower },
  { id: 'policies', label: 'Policy engine', group: 'Workspace', icon: ShieldCheck },
  { id: 'access', label: 'Access decisions', group: 'Monitoring', icon: ScrollText },
  { id: 'authentication', label: 'Login history', group: 'Monitoring', icon: Fingerprint },
  { id: 'telemetry', label: 'Telemetry', group: 'Monitoring', icon: Waves },
] as const;

const pageMeta: Record<ViewKey, { title: string; crumb: string }> = {
  overview: { title: 'Overview', crumb: 'Workspace / Overview' },
  devices: { title: 'Device identities', crumb: 'Workspace / Device identities' },
  policies: { title: 'Policy engine', crumb: 'Workspace / Policy engine' },
  access: { title: 'Access decisions', crumb: 'Monitoring / Access decisions' },
  authentication: { title: 'Login history', crumb: 'Monitoring / Login history' },
  telemetry: { title: 'Telemetry', crumb: 'Monitoring / Telemetry' },
};

export function Shell({
  children,
  user,
  activeView,
  onNavigate,
  onLogout,
  notice,
  onDismissNotice,
}: {
  children: ReactNode;
  user: UserProfile;
  activeView: ViewKey;
  onNavigate: (view: ViewKey) => void;
  onLogout: () => void;
  notice: Notice | null;
  onDismissNotice: () => void;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [apiHealthy, setApiHealthy] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    const check = async () => {
      try {
        const result = await api.health();
        if (!cancelled) setApiHealthy(result.status === 'UP');
      } catch {
        if (!cancelled) setApiHealthy(false);
      }
    };
    void check();
    const interval = window.setInterval(() => void check(), 30_000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, []);

  const initials = user.fullName.trim().split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase();
  const groups = ['Workspace', 'Monitoring'] as const;

  function navigate(view: ViewKey) {
    onNavigate(view);
    setMenuOpen(false);
  }

  return (
    <div className="app-frame">
      {menuOpen && <button className="mobile-scrim" aria-label="Close navigation" onClick={() => setMenuOpen(false)} />}
      <aside className={`sidebar ${menuOpen ? 'sidebar-open' : ''}`}>
        <div className="sidebar-brand">
          <span className="brand-mark"><ShieldCheck size={18} /></span>
          <span className="brand-wordmark">ZERO<span>TRUST</span></span>
          <button className="sidebar-close icon-button" aria-label="Close navigation" onClick={() => setMenuOpen(false)}><X size={18} /></button>
        </div>
        <div className="workspace-switcher">
          <span className="workspace-avatar"><Activity size={16} /></span>
          <span className="workspace-name"><b>IoT Workspace</b><small>Local environment</small></span>
          <ChevronDown size={14} className="workspace-chevron" />
        </div>
        <div className="sidebar-scroll">
          {groups.map((group) => (
            <div className="nav-group" key={group}>
              <div className="nav-label">{group}</div>
              {navigation.filter((item) => item.group === group).map((item) => {
                const Icon = item.icon;
                const selected = activeView === item.id;
                return (
                  <button key={item.id} className={`nav-item ${selected ? 'nav-item-active' : ''}`} onClick={() => navigate(item.id)} aria-current={selected ? 'page' : undefined}>
                    <Icon size={17} strokeWidth={1.8} /><span>{item.label}</span>
                    {item.id === 'access' && <span className="nav-live-dot" />}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
        <div className="sidebar-bottom">
          <div className="environment-card"><span className={`environment-dot ${apiHealthy === false ? 'environment-dot-down' : ''}`} /><div><b>{apiHealthy === false ? 'API unavailable' : apiHealthy === true ? 'API connected' : 'Checking API'}</b><small>{apiHealthy ? 'Spring Boot · healthy' : apiHealthy === false ? 'Check backend service' : 'Secure connection'}</small></div><ShieldAlert size={16} className="environment-icon" /></div>
          <div className="sidebar-user">
            <span className="user-avatar">{initials || 'ZT'}</span>
            <span className="sidebar-user-info"><b>{user.fullName}</b><small>{user.role.replace('_', ' ')}</small></span>
            <button className="logout-button" aria-label="Sign out" title="Sign out" onClick={onLogout}><LogOut size={16} /></button>
          </div>
        </div>
      </aside>

      <div className="main-column">
        <header className="topbar">
          <button className="mobile-menu icon-button" aria-label="Open navigation" onClick={() => setMenuOpen(true)}><Menu size={20} /></button>
          <div className="topbar-breadcrumb"><span>CONTROL PLANE</span><i>/</i><b>{pageMeta[activeView].title}</b></div>
          <div className="topbar-actions">
            <Badge tone={apiHealthy === false ? 'red' : 'green'} dot>{apiHealthy === false ? 'API offline' : 'SYSTEM LIVE'}</Badge>
            <div className="topbar-divider" />
            <button className="icon-button notification-button" title="No new notifications" aria-label="Notifications"><Bell size={17} /><span /></button>
            <button className="topbar-profile" onClick={onLogout} title="Sign out"><span className="user-avatar user-avatar-small">{initials || 'ZT'}</span><ChevronDown size={14} /></button>
          </div>
        </header>
        <main className="main-content">
          <div className="breadcrumb-mobile"><span>CONTROL PLANE</span><i>/</i>{pageMeta[activeView].crumb.split('/')[1]}</div>
          {children}
          <footer className="content-footer"><span><span className="footer-shield"><ShieldCheck size={12} /></span> Zero Trust Architecture for Small Enterprise IoT</span><span>PHASE 19 · CONTROL PLANE</span></footer>
        </main>
      </div>
      {notice && (
        <div className={`toast toast-${notice.tone}`} role="status">
          <span className="toast-mark">{notice.tone === 'success' ? <ShieldCheck size={17} /> : notice.tone === 'error' ? <ShieldAlert size={17} /> : <Activity size={17} />}</span>
          <span className="toast-copy"><b>{notice.title}</b>{notice.detail && <small>{notice.detail}</small>}</span>
          <button className="toast-close" aria-label="Dismiss notification" onClick={onDismissNotice}><X size={15} /></button>
        </div>
      )}
    </div>
  );
}
