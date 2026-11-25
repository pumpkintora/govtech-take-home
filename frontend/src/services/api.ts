import { ConversationView, MessageDto, UserSummary } from '../types';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  });

  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const body = await res.json();
      if (body?.error) message = body.error;
    } catch {
      // Ignore non-JSON error body
    }
    throw new Error(message);
  }

  return res.json() as Promise<T>;
}

export const api = {
  me(): Promise<{ user: UserSummary; nodeId: string }> {
    return request('/api/auth/me');
  },

  login(username: string, password: string): Promise<UserSummary> {
    return request('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
  },

  register(username: string, displayName: string, password: string): Promise<UserSummary> {
    return request('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, displayName, password }),
    });
  },

  logout(): Promise<{ status: string }> {
    return request('/api/auth/logout', { method: 'POST' });
  },

  listUsers(): Promise<UserSummary[]> {
    return request('/api/users');
  },

  listConversations(): Promise<ConversationView[]> {
    return request('/api/conversations');
  },

  startConversation(targetUserId: string): Promise<ConversationView> {
    return request('/api/conversations', {
      method: 'POST',
      body: JSON.stringify({ targetUserId }),
    });
  },

  getMessages(conversationId: string, beforeSeq?: number, limit = 50): Promise<MessageDto[]> {
    const params = new URLSearchParams({ limit: String(limit) });
    if (beforeSeq !== undefined) {
      params.set('beforeSeq', String(beforeSeq));
    }
    return request(`/api/conversations/${conversationId}/messages?${params.toString()}`);
  },

  syncMessages(afterSeq: number): Promise<{ messages: MessageDto[]; latestSeq: number }> {
    return request(`/api/messages/sync?afterSeq=${afterSeq}`);
  },
};
