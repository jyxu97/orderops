import { NavLink, Outlet } from 'react-router-dom';
import { ConnectionIndicator } from './ConnectionIndicator';

/** Grows as routes are added; only links to pages that exist. */
const NAV_LINKS = [
  { to: '/order/create', label: 'New order' },
  { to: '/orders', label: 'Orders' },
  { to: '/operations', label: 'Operations' },
  { to: '/operations/failures', label: 'Failures' },
];

export function AppLayout() {
  return (
    <div className="app">
      <header className="app__header">
        <div className="app__brand">
          <span className="app__logo" aria-hidden="true" />
          <div className="app__brand-text">
            <strong>OrderOps</strong>
            <small>Real-time order fulfillment</small>
          </div>
        </div>

        <nav className="app__nav">
          {NAV_LINKS.map(({ to, label }) => (
            <NavLink
              key={to}
              to={to}
              // `end` so a parent path does not stay highlighted on its child routes.
              end
              className={({ isActive }) => (isActive ? 'app__link app__link--active' : 'app__link')}
            >
              {label}
            </NavLink>
          ))}
        </nav>

        <ConnectionIndicator />
      </header>

      <main className="app__main">
        <Outlet />
      </main>
    </div>
  );
}
