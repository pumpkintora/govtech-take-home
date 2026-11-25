import React, { useState } from 'react';
import { api } from '../services/api';
import { UserSummary } from '../types';

interface Props {
  onAuthenticated: (user: UserSummary) => void;
}

export const AuthView: React.FC<Props> = ({ onAuthenticated }) => {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const user =
        mode === 'login'
          ? await api.login(username, password)
          : await api.register(username, displayName, password);
      onAuthenticated(user);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Authentication failed');
    } finally {
      setLoading(false);
    }
  }

  async function quickLogin(demoUser: string) {
    setError(null);
    setLoading(true);
    try {
      const user = await api.login(demoUser, 'password123');
      onAuthenticated(user);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Quick login failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-brand">
          <span className="brand-dot" />
          <h1>PulseChat</h1>
        </div>
        <p className="auth-subtitle">
          Real-time 1-to-1 private messaging · Custom RFC 6455 WebSocket + RabbitMQ + PostgreSQL
        </p>

        <div className="auth-tabs">
          <button
            type="button"
            className={`auth-tab ${mode === 'login' ? 'active' : ''}`}
            onClick={() => {
              setMode('login');
              setError(null);
            }}
          >
            Sign In
          </button>
          <button
            type="button"
            className={`auth-tab ${mode === 'register' ? 'active' : ''}`}
            onClick={() => {
              setMode('register');
              setError(null);
            }}
          >
            Create Account
          </button>
        </div>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label htmlFor="username">Username</label>
            <input
              id="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="e.g. alice"
              autoComplete="username"
              required
            />
          </div>

          {mode === 'register' && (
            <div className="form-group">
              <label htmlFor="displayName">Display Name</label>
              <input
                id="displayName"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder="e.g. Alice Tan"
              />
            </div>
          )}

          <div className="form-group">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Min 6 characters"
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              required
            />
          </div>

          <button className="btn-primary" type="submit" disabled={loading}>
            {loading ? 'Please wait...' : mode === 'login' ? 'Sign In' : 'Create Account'}
          </button>
        </form>

        <div className="demo-box">
          <div className="demo-title">Quick Demo Login (password: password123)</div>
          <div className="demo-buttons">
            <button type="button" className="demo-chip" onClick={() => quickLogin('alice')} disabled={loading}>
              Alice
            </button>
            <button type="button" className="demo-chip" onClick={() => quickLogin('bob')} disabled={loading}>
              Bob
            </button>
            <button type="button" className="demo-chip" onClick={() => quickLogin('charlie')} disabled={loading}>
              Charlie
            </button>
          </div>
          <p className="demo-hint">
            Tip: Open a second user in an Incognito/Private window to test 1-to-1 chat across devices,
            or open a second regular tab to test multi-tab sync for the same user.
          </p>
        </div>
      </div>
    </div>
  );
};
