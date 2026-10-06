import { useCallback, useEffect, useRef, useState } from 'react';
import { LoginPage } from './pages/LoginPage';
import { MfaSecurityPage } from './pages/MfaSecurityPage';
import { AccessAuditsPage } from './pages/AccessAuditsPage';
import { AuthenticationAuditsPage } from './pages/AuthenticationAuditsPage';
import { DevicesPage } from './pages/DevicesPage';
import { OverviewPage } from './pages/OverviewPage';
import { PoliciesPage } from './pages/PoliciesPage';
import { TelemetryPage } from './pages/TelemetryPage';
import { UserAccountsPage } from './pages/UserAccountsPage';
import { UserPortalPage } from './pages/UserPortalPage';
import { Shell, type ViewKey } from './components/Shell';
import type { AuthResponse, UserProfile } from './types';

export type Notice = { id: number; tone: 'success' | 'error' | 'info'; title: string; detail?: string };
export type Notify = (notice: Omit<Notice, 'id'>) => void;

export default function App() {
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<UserProfile | null>(null);
  const [activeView, setActiveView] = useState<ViewKey>('overview');
  const [notice, setNotice] = useState<Notice | null>(null);
  const noticeCounter = useRef(0);

  const notify: Notify = useCallback((next) => {
    const id = ++noticeCounter.current;
    setNotice({ ...next, id });
  }, []);

  useEffect(() => {
    const expireSession = () => {
      setToken(null);
      setUser(null);
      setActiveView('overview');
      const id = ++noticeCounter.current;
      setNotice({ id, tone: 'info', title: 'Session expired', detail: 'Sign in again to continue.' });
    };
    window.addEventListener('zero-trust:unauthorized', expireSession);
    return () => window.removeEventListener('zero-trust:unauthorized', expireSession);
  }, []);

  useEffect(() => {
    if (!notice) return;
    const timeout = window.setTimeout(() => setNotice((current) => current?.id === notice.id ? null : current), 4800);
    return () => window.clearTimeout(timeout);
  }, [notice]);

  function authenticated(session: AuthResponse) {
    setToken(session.accessToken);
    setUser(session.user);
    setActiveView('overview');
    notify({ tone: 'success', title: `Welcome, ${session.user.fullName.split(' ')[0]}`, detail: 'Your secure session is active.' });
  }

  function logout() {
    setToken(null);
    setUser(null);
    setNotice(null);
    setActiveView('overview');
  }

  if (!token || !user) return <LoginPage onAuthenticated={authenticated} notice={notice} onDismissNotice={() => setNotice(null)} />;
  if (user.role === 'USER') return <UserPortalPage token={token} user={user} onLogout={logout} notice={notice} onDismissNotice={() => setNotice(null)} />;

  const pageProps = { token, user, notify };
  return (
    <Shell
      user={user}
      activeView={activeView}
      onNavigate={setActiveView}
      onLogout={logout}
      notice={notice}
      onDismissNotice={() => setNotice(null)}
    >
      {activeView === 'overview' && <OverviewPage key="overview" {...pageProps} onNavigate={setActiveView} />}
      {activeView === 'devices' && <DevicesPage key="devices" {...pageProps} />}
      {activeView === 'policies' && <PoliciesPage key="policies" {...pageProps} />}
      {activeView === 'access' && <AccessAuditsPage key="access" {...pageProps} />}
      {activeView === 'authentication' && <AuthenticationAuditsPage key="authentication" {...pageProps} />}
      {activeView === 'telemetry' && <TelemetryPage key="telemetry" {...pageProps} />}
      {activeView === 'security' && <MfaSecurityPage key="security" token={token} user={user} notify={notify} onLogout={logout} />}
      {activeView === 'accounts' && user.role === 'ADMIN' && <UserAccountsPage key="accounts" token={token} user={user} notify={notify} />}
    </Shell>
  );
}
