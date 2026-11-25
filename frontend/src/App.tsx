import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AuthView } from './components/AuthView';
import { ChatWindow } from './components/ChatWindow';
import { Sidebar } from './components/Sidebar';
import { api } from './services/api';
import { ChatWebSocketClient } from './services/wsClient';
import { ConnectionState, ConversationView, MessageDto, ServerEvent, UserSummary } from './types';

function mergeSortedMessages(existing: MessageDto[], incoming: MessageDto[]): MessageDto[] {
  const byClientId = new Map<string, MessageDto>();
  for (const m of existing) {
    byClientId.set(m.clientMessageId, m);
  }
  for (const m of incoming) {
    byClientId.set(m.clientMessageId, m);
  }
  return Array.from(byClientId.values()).sort((a, b) => {
    // Pending optimistic messages (seq === 0) stay at the bottom ordered by local timestamp
    if (a.seq === 0 && b.seq === 0) return a.createdAt.localeCompare(b.createdAt);
    if (a.seq === 0) return 1;
    if (b.seq === 0) return -1;
    return a.seq - b.seq;
  });
}

export const App: React.FC = () => {
  const [booting, setBooting] = useState(true);
  const [currentUser, setCurrentUser] = useState<UserSummary | null>(null);
  const [connectionState, setConnectionState] = useState<ConnectionState>('disconnected');
  const [nodeId, setNodeId] = useState<string | null>(null);

  const [conversations, setConversations] = useState<ConversationView[]>([]);
  const [directoryUsers, setDirectoryUsers] = useState<UserSummary[]>([]);
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null);
  const [messagesByConv, setMessagesByConv] = useState<Record<string, MessageDto[]>>({});
  const [hasMoreByConv, setHasMoreByConv] = useState<Record<string, boolean>>({});
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [typingByConv, setTypingByConv] = useState<Record<string, boolean>>({});

  const wsRef = useRef<ChatWebSocketClient | null>(null);
  const activeConvRef = useRef<string | null>(null);
  const currentUserRef = useRef<UserSummary | null>(null);
  const lastSeenSeqRef = useRef<number>(0);
  const hasConnectedOnceRef = useRef<boolean>(false);
  const typingExpiryTimersRef = useRef<Record<string, number>>({});

  activeConvRef.current = activeConversationId;
  currentUserRef.current = currentUser;

  const trackSeq = useCallback((seq: number) => {
    if (seq > lastSeenSeqRef.current) {
      lastSeenSeqRef.current = seq;
    }
  }, []);

  const refreshSidebar = useCallback(async () => {
    const [convs, users] = await Promise.all([api.listConversations(), api.listUsers()]);
    setConversations(convs);
    setDirectoryUsers(users);
    for (const c of convs) {
      if (c.lastMessage) trackSeq(c.lastMessage.seq);
    }
  }, [trackSeq]);

  const sendReadReceipt = useCallback((conversationId: string, upToSeq: number) => {
    if (upToSeq <= 0) return;
    wsRef.current?.send({ type: 'message.ack_read', conversationId, upToSeq });
    setConversations((prev) =>
      prev.map((c) => (c.id === conversationId ? { ...c, unreadCount: 0 } : c)),
    );
  }, []);

  const ingestIncomingMessage = useCallback(
    (msg: MessageDto, fromSync = false) => {
      trackSeq(msg.seq);
      const me = currentUserRef.current;

      setMessagesByConv((prev) => ({
        ...prev,
        [msg.conversationId]: mergeSortedMessages(prev[msg.conversationId] ?? [], [msg]),
      }));

      setConversations((prev) => {
        const exists = prev.some((c) => c.id === msg.conversationId);
        if (!exists) {
          // Brand-new incoming 1-to-1 conversation initiated by a peer: refresh sidebar metadata
          void refreshSidebar();
          return prev;
        }
        const isViewing =
          activeConvRef.current === msg.conversationId && document.visibilityState === 'visible';
        const isFromPeer = me && msg.senderId !== me.id;
        return prev
          .map((c) => {
            if (c.id !== msg.conversationId) return c;
            const newerLast = !c.lastMessage || msg.seq >= c.lastMessage.seq ? msg : c.lastMessage;
            const nextUnread =
              isFromPeer && !isViewing && !msg.readAt ? c.unreadCount + 1 : c.unreadCount;
            return { ...c, lastMessage: newerLast, unreadCount: nextUnread };
          })
          .sort((a, b) => (b.lastMessage?.seq ?? 0) - (a.lastMessage?.seq ?? 0));
      });

      // Automatically send delivered/read receipts for messages addressed to us
      if (me && msg.recipientId === me.id && !fromSync) {
        wsRef.current?.send({
          type: 'message.ack_delivered',
          conversationId: msg.conversationId,
          upToSeq: msg.seq,
        });
        if (activeConvRef.current === msg.conversationId && document.visibilityState === 'visible') {
          sendReadReceipt(msg.conversationId, msg.seq);
        }
      }
    },
    [refreshSidebar, sendReadReceipt, trackSeq],
  );

  const handleServerEvent = useCallback(
    (event: ServerEvent) => {
      switch (event.type) {
        case 'session.ready': {
          setNodeId(event.nodeId);
          // On reconnect (or if new messages landed between initial REST load and WS upgrade),
          // run cursor-based gap recovery (`afterSeq`).
          // NOTE (MVP limitation): Fetches up to 100 missed messages per reconnect; in production
          // we would loop until all missed pages are fetched.
          if (hasConnectedOnceRef.current || event.latestSeq > lastSeenSeqRef.current) {
            const cursor = lastSeenSeqRef.current;
            void api
              .syncMessages(cursor)
              .then(({ messages, latestSeq }) => {
                messages.forEach((m) => ingestIncomingMessage(m, true));
                trackSeq(latestSeq);
                return refreshSidebar();
              })
              .catch(() => {});
          }
          hasConnectedOnceRef.current = true;
          break;
        }

        case 'message.ack': {
          const msg = event.message;
          trackSeq(msg.seq);
          setMessagesByConv((prev) => ({
            ...prev,
            [msg.conversationId]: mergeSortedMessages(prev[msg.conversationId] ?? [], [msg]),
          }));
          setConversations((prev) =>
            prev
              .map((c) =>
                c.id === msg.conversationId && (!c.lastMessage || msg.seq >= c.lastMessage.seq)
                  ? { ...c, lastMessage: msg }
                  : c,
              )
              .sort((a, b) => (b.lastMessage?.seq ?? 0) - (a.lastMessage?.seq ?? 0)),
          );
          break;
        }

        case 'message.new': {
          ingestIncomingMessage(event.message, false);
          break;
        }

        case 'message.status': {
          const seqSet = new Set(event.seqs);
          setMessagesByConv((prev) => {
            const list = prev[event.conversationId];
            if (!list) return prev;
            return {
              ...prev,
              [event.conversationId]: list.map((m) => {
                if (!seqSet.has(m.seq)) return m;
                if (event.status === 'READ') {
                  return {
                    ...m,
                    deliveredAt: m.deliveredAt ?? event.timestamp,
                    readAt: m.readAt ?? event.timestamp,
                  };
                }
                return { ...m, deliveredAt: m.deliveredAt ?? event.timestamp };
              }),
            };
          });
          if (event.status === 'READ') {
            // Sync cleared unread badge across all devices of the reader
            setConversations((prev) =>
              prev.map((c) => (c.id === event.conversationId ? { ...c, unreadCount: 0 } : c)),
            );
          }
          break;
        }

        case 'presence.update': {
          setDirectoryUsers((prev) =>
            prev.map((u) => (u.id === event.userId ? { ...u, online: event.online } : u)),
          );
          setConversations((prev) =>
            prev.map((c) =>
              c.peer.id === event.userId ? { ...c, peer: { ...c.peer, online: event.online } } : c,
            ),
          );
          break;
        }

        case 'typing.update': {
          setTypingByConv((prev) => ({ ...prev, [event.conversationId]: event.typing }));
          const existingTimer = typingExpiryTimersRef.current[event.conversationId];
          if (existingTimer) window.clearTimeout(existingTimer);
          if (event.typing) {
            // Auto-expire typing state after 4s in case peer loses connectivity before sending typing.stop
            typingExpiryTimersRef.current[event.conversationId] = window.setTimeout(() => {
              setTypingByConv((prev) => ({ ...prev, [event.conversationId]: false }));
            }, 4000);
          }
          break;
        }

        case 'error': {
          if (event.clientMessageId) {
            setMessagesByConv((prev) => {
              const next: Record<string, MessageDto[]> = {};
              for (const [cid, list] of Object.entries(prev)) {
                next[cid] = list.map((m) =>
                  m.clientMessageId === event.clientMessageId ? { ...m, localStatus: 'FAILED' } : m,
                );
              }
              return next;
            });
          }
          break;
        }
      }
    },
    [ingestIncomingMessage, refreshSidebar, trackSeq],
  );

  // Check existing session cookie on initial page load
  useEffect(() => {
    api
      .me()
      .then(({ user }) => setCurrentUser(user))
      .catch(() => setCurrentUser(null))
      .finally(() => setBooting(false));
  }, []);

  // Connect WebSocket & hydrate data whenever a user authenticates
  useEffect(() => {
    if (!currentUser) {
      wsRef.current?.disconnect();
      wsRef.current = null;
      return;
    }

    hasConnectedOnceRef.current = false;
    lastSeenSeqRef.current = 0;

    void refreshSidebar().finally(() => {
      const client = new ChatWebSocketClient(handleServerEvent, setConnectionState);
      wsRef.current = client;
      client.connect();
    });

    return () => {
      wsRef.current?.disconnect();
      wsRef.current = null;
    };
  }, [currentUser, handleServerEvent, refreshSidebar]);

  // Mark active conversation as read when returning to browser tab
  useEffect(() => {
    function onVisibilityChange() {
      if (document.visibilityState !== 'visible') return;
      const convId = activeConvRef.current;
      const me = currentUserRef.current;
      if (!convId || !me) return;
      const msgs = messagesByConv[convId] ?? [];
      const maxPeerSeq = msgs
        .filter((m) => m.recipientId === me.id && !m.readAt)
        .reduce((max, m) => Math.max(max, m.seq), 0);
      if (maxPeerSeq > 0) {
        sendReadReceipt(convId, maxPeerSeq);
      }
    }
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, [messagesByConv, sendReadReceipt]);

  const loadConversationMessages = useCallback(
    async (conversationId: string) => {
      const msgs = await api.getMessages(conversationId, undefined, 50);
      setMessagesByConv((prev) => ({
        ...prev,
        [conversationId]: mergeSortedMessages(prev[conversationId] ?? [], msgs),
      }));
      setHasMoreByConv((prev) => ({ ...prev, [conversationId]: msgs.length === 50 }));

      const me = currentUserRef.current;
      let maxUnreadSeq = 0;
      for (const m of msgs) {
        trackSeq(m.seq);
        if (me && m.recipientId === me.id && !m.readAt && m.seq > maxUnreadSeq) {
          maxUnreadSeq = m.seq;
        }
      }
      if (maxUnreadSeq > 0) {
        sendReadReceipt(conversationId, maxUnreadSeq);
      }
    },
    [sendReadReceipt, trackSeq],
  );

  const handleSelectConversation = useCallback(
    (conversationId: string) => {
      setActiveConversationId(conversationId);
      void loadConversationMessages(conversationId);
    },
    [loadConversationMessages],
  );

  const handleStartChat = useCallback(
    async (targetUserId: string) => {
      const conv = await api.startConversation(targetUserId);
      setConversations((prev) => {
        const exists = prev.some((c) => c.id === conv.id);
        return exists ? prev : [conv, ...prev];
      });
      setActiveConversationId(conv.id);
      await loadConversationMessages(conv.id);
    },
    [loadConversationMessages],
  );

  const handleLoadOlder = useCallback(async () => {
    if (!activeConversationId || loadingOlder) return;
    const current = messagesByConv[activeConversationId] ?? [];
    const oldestSeq = current.find((m) => m.seq > 0)?.seq;
    if (!oldestSeq) return;
    setLoadingOlder(true);
    try {
      const older = await api.getMessages(activeConversationId, oldestSeq, 50);
      setMessagesByConv((prev) => ({
        ...prev,
        [activeConversationId]: mergeSortedMessages(prev[activeConversationId] ?? [], older),
      }));
      setHasMoreByConv((prev) => ({ ...prev, [activeConversationId]: older.length === 50 }));
    } finally {
      setLoadingOlder(false);
    }
  }, [activeConversationId, loadingOlder, messagesByConv]);

  const handleSendMessage = useCallback(
    (body: string) => {
      if (!currentUser || !activeConversationId) return;
      const conv = conversations.find((c) => c.id === activeConversationId);
      if (!conv) return;

      const clientMessageId = crypto.randomUUID();
      const optimistic: MessageDto = {
        seq: 0,
        id: clientMessageId,
        conversationId: activeConversationId,
        senderId: currentUser.id,
        recipientId: conv.peer.id,
        clientMessageId,
        body,
        createdAt: new Date().toISOString(),
        deliveredAt: null,
        readAt: null,
        localStatus: 'PENDING',
      };

      setMessagesByConv((prev) => ({
        ...prev,
        [activeConversationId]: mergeSortedMessages(prev[activeConversationId] ?? [], [optimistic]),
      }));

      wsRef.current?.send(
        {
          type: 'message.send',
          conversationId: activeConversationId,
          clientMessageId,
          body,
        },
        true,
      );
    },
    [activeConversationId, conversations, currentUser],
  );

  const handleRetryMessage = useCallback((msg: MessageDto) => {
    setMessagesByConv((prev) => ({
      ...prev,
      [msg.conversationId]: (prev[msg.conversationId] ?? []).map((m) =>
        m.clientMessageId === msg.clientMessageId ? { ...m, localStatus: 'PENDING' } : m,
      ),
    }));
    wsRef.current?.send(
      {
        type: 'message.send',
        conversationId: msg.conversationId,
        clientMessageId: msg.clientMessageId,
        body: msg.body,
      },
      true,
    );
  }, []);

  const handleTypingChange = useCallback(
    (typing: boolean) => {
      if (!activeConversationId) return;
      wsRef.current?.send({
        type: typing ? 'typing.start' : 'typing.stop',
        conversationId: activeConversationId,
      });
    },
    [activeConversationId],
  );

  const handleLogout = useCallback(async () => {
    wsRef.current?.disconnect();
    await api.logout().catch(() => {});
    setCurrentUser(null);
    setConversations([]);
    setDirectoryUsers([]);
    setActiveConversationId(null);
    setMessagesByConv({});
    setNodeId(null);
  }, []);

  if (booting) {
    return (
      <div className="auth-page">
        <p className="auth-subtitle">Loading session...</p>
      </div>
    );
  }

  if (!currentUser) {
    return <AuthView onAuthenticated={setCurrentUser} />;
  }

  const activeConversation = conversations.find((c) => c.id === activeConversationId) ?? null;

  return (
    <div className={`app-shell ${activeConversationId ? 'has-active-chat' : ''}`}>
      <Sidebar
        currentUser={currentUser}
        connectionState={connectionState}
        nodeId={nodeId}
        conversations={conversations}
        directoryUsers={directoryUsers}
        activeConversationId={activeConversationId}
        typingByConversation={typingByConv}
        onSelectConversation={handleSelectConversation}
        onStartChat={handleStartChat}
        onLogout={handleLogout}
      />
      <ChatWindow
        currentUser={currentUser}
        conversation={activeConversation}
        messages={activeConversationId ? messagesByConv[activeConversationId] ?? [] : []}
        hasMoreOlder={activeConversationId ? Boolean(hasMoreByConv[activeConversationId]) : false}
        loadingOlder={loadingOlder}
        peerTyping={activeConversationId ? Boolean(typingByConv[activeConversationId]) : false}
        onBackMobile={() => setActiveConversationId(null)}
        onLoadOlder={handleLoadOlder}
        onSendMessage={handleSendMessage}
        onRetryMessage={handleRetryMessage}
        onTypingChange={handleTypingChange}
      />
    </div>
  );
};

export default App;
