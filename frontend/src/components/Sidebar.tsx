import React, { useState } from 'react';
import { ConnectionState, ConversationView, UserSummary } from '../types';

interface Props {
  currentUser: UserSummary;
  connectionState: ConnectionState;
  nodeId: string | null;
  conversations: ConversationView[];
  directoryUsers: UserSummary[];
  activeConversationId: string | null;
  typingByConversation: Record<string, boolean>;
  onSelectConversation: (conversationId: string) => void;
  onStartChat: (targetUserId: string) => void;
  onLogout: () => void;
}

function formatShortTime(iso: string | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export const Sidebar: React.FC<Props> = ({
  currentUser,
  connectionState,
  nodeId,
  conversations,
  directoryUsers,
  activeConversationId,
  typingByConversation,
  onSelectConversation,
  onStartChat,
  onLogout,
}) => {
  const [tab, setTab] = useState<'chats' | 'people'>('chats');

  const statusLabel =
    connectionState === 'connected'
      ? `Connected${nodeId ? ` · ${nodeId}` : ''}`
      : connectionState === 'reconnecting'
        ? 'Reconnecting...'
        : connectionState === 'connecting'
          ? 'Connecting...'
          : 'Offline';

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <div className="me-info">
          <div className="me-name">{currentUser.displayName}</div>
          <div className="conn-pill">
            <span className={`status-dot ${connectionState}`} />
            <span>{statusLabel}</span>
          </div>
        </div>
        <button type="button" className="btn-ghost" onClick={onLogout}>
          Logout
        </button>
      </div>

      <div className="sidebar-tabs">
        <button
          type="button"
          className={`sidebar-tab ${tab === 'chats' ? 'active' : ''}`}
          onClick={() => setTab('chats')}
        >
          Chats ({conversations.length})
        </button>
        <button
          type="button"
          className={`sidebar-tab ${tab === 'people' ? 'active' : ''}`}
          onClick={() => setTab('people')}
        >
          People ({directoryUsers.length})
        </button>
      </div>

      <div className="sidebar-list">
        {tab === 'chats' ? (
          conversations.length === 0 ? (
            <div className="empty-list">
              No conversations yet.
              <br />
              Switch to <strong>People</strong> to start a 1-to-1 chat.
            </div>
          ) : (
            conversations.map((conv) => {
              const isTyping = Boolean(typingByConversation[conv.id]);
              return (
                <button
                  key={conv.id}
                  type="button"
                  className={`list-item ${activeConversationId === conv.id ? 'active' : ''}`}
                  onClick={() => onSelectConversation(conv.id)}
                >
                  <div className="avatar">
                    {conv.peer.displayName.slice(0, 2)}
                    <span className={`presence-badge ${conv.peer.online ? 'online' : ''}`} />
                  </div>
                  <div className="item-body">
                    <div className="item-top">
                      <span className="item-name">{conv.peer.displayName}</span>
                      <span className="item-time">
                        {formatShortTime(conv.lastMessage?.createdAt ?? conv.createdAt)}
                      </span>
                    </div>
                    <div className="item-bottom">
                      <span className={`item-preview ${isTyping ? 'typing' : ''}`}>
                        {isTyping
                          ? 'typing...'
                          : conv.lastMessage
                            ? `${conv.lastMessage.senderId === currentUser.id ? 'You: ' : ''}${conv.lastMessage.body}`
                            : 'Start messaging...'}
                      </span>
                      {conv.unreadCount > 0 && <span className="unread-pill">{conv.unreadCount}</span>}
                    </div>
                  </div>
                </button>
              );
            })
          )
        ) : directoryUsers.length === 0 ? (
          <div className="empty-list">No other users registered yet.</div>
        ) : (
          directoryUsers.map((user) => (
            <button
              key={user.id}
              type="button"
              className="list-item"
              onClick={() => {
                onStartChat(user.id);
                setTab('chats');
              }}
            >
              <div className="avatar">
                {user.displayName.slice(0, 2)}
                <span className={`presence-badge ${user.online ? 'online' : ''}`} />
              </div>
              <div className="item-body">
                <div className="item-top">
                  <span className="item-name">{user.displayName}</span>
                </div>
                <div className="item-bottom">
                  <span className="item-preview">
                    @{user.username} · {user.online ? 'Online' : 'Offline'}
                  </span>
                </div>
              </div>
            </button>
          ))
        )}
      </div>
    </aside>
  );
};
