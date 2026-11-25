import React, { useEffect, useRef, useState } from 'react';
import { ConversationView, MessageDto, UserSummary } from '../types';

interface Props {
  currentUser: UserSummary;
  conversation: ConversationView | null;
  messages: MessageDto[];
  hasMoreOlder: boolean;
  loadingOlder: boolean;
  peerTyping: boolean;
  onBackMobile: () => void;
  onLoadOlder: () => void;
  onSendMessage: (body: string) => void;
  onRetryMessage: (msg: MessageDto) => void;
  onTypingChange: (typing: boolean) => void;
}

function formatMessageTime(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export const ChatWindow: React.FC<Props> = ({
  currentUser,
  conversation,
  messages,
  hasMoreOlder,
  loadingOlder,
  peerTyping,
  onBackMobile,
  onLoadOlder,
  onSendMessage,
  onRetryMessage,
  onTypingChange,
}) => {
  const [draft, setDraft] = useState('');
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const typingTimerRef = useRef<number | null>(null);
  const isTypingRef = useRef(false);

  // Auto-scroll to latest message when conversation switches or new message appends
  const lastMsgKey = messages.length > 0 ? messages[messages.length - 1].clientMessageId : '';
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [conversation?.id, lastMsgKey]);

  // Reset typing indicator if conversation changes
  useEffect(() => {
    return () => {
      if (typingTimerRef.current !== null) {
        window.clearTimeout(typingTimerRef.current);
      }
      if (isTypingRef.current) {
        isTypingRef.current = false;
        onTypingChange(false);
      }
    };
  }, [conversation?.id]);

  if (!conversation) {
    return (
      <main className="chat-pane">
        <div className="chat-empty">
          <h2>Select a conversation</h2>
          <p>Choose an existing chat or pick someone from the People tab to start messaging in real time.</p>
        </div>
      </main>
    );
  }

  function handleDraftChange(value: string) {
    setDraft(value);
    if (!isTypingRef.current && value.trim().length > 0) {
      isTypingRef.current = true;
      onTypingChange(true);
    }
    if (typingTimerRef.current !== null) {
      window.clearTimeout(typingTimerRef.current);
    }
    typingTimerRef.current = window.setTimeout(() => {
      if (isTypingRef.current) {
        isTypingRef.current = false;
        onTypingChange(false);
      }
    }, 2000);
  }

  function submitMessage() {
    const trimmed = draft.trim();
    if (!trimmed) return;
    setDraft('');
    if (isTypingRef.current) {
      isTypingRef.current = false;
      onTypingChange(false);
    }
    onSendMessage(trimmed);
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submitMessage();
    }
  }

  return (
    <main className="chat-pane">
      <header className="chat-header">
        <button type="button" className="mobile-back" onClick={onBackMobile}>
          ← Back
        </button>
        <div className="avatar">
          {conversation.peer.displayName.slice(0, 2)}
          <span className={`presence-badge ${conversation.peer.online ? 'online' : ''}`} />
        </div>
        <div>
          <div className="item-name">{conversation.peer.displayName}</div>
          <div className={`chat-peer-status ${peerTyping ? 'typing' : ''}`}>
            {peerTyping ? 'typing...' : conversation.peer.online ? 'Online' : 'Offline'}
          </div>
        </div>
      </header>

      <div className="messages-scroll">
        {hasMoreOlder && (
          <div className="load-older-wrap">
            <button type="button" className="btn-ghost" onClick={onLoadOlder} disabled={loadingOlder}>
              {loadingOlder ? 'Loading...' : 'Load older messages'}
            </button>
          </div>
        )}

        {messages.map((msg) => {
          const isMe = msg.senderId === currentUser.id;
          return (
            <div key={msg.clientMessageId || msg.id} className={`msg-row ${isMe ? 'me' : 'peer'}`}>
              <div className="msg-bubble">
                <div className="msg-text">{msg.body}</div>
                <div className="msg-meta">
                  <span>{formatMessageTime(msg.createdAt)}</span>
                  {isMe && (
                    <>
                      {msg.localStatus === 'PENDING' && <span title="Sending">◷</span>}
                      {msg.localStatus === 'FAILED' && (
                        <>
                          <span className="tick-failed">!</span>
                          <button type="button" className="retry-link" onClick={() => onRetryMessage(msg)}>
                            Retry
                          </button>
                        </>
                      )}
                      {!msg.localStatus && msg.readAt && (
                        <span className="tick-read" title="Read">
                          ✓✓
                        </span>
                      )}
                      {!msg.localStatus && !msg.readAt && msg.deliveredAt && (
                        <span title="Delivered">✓✓</span>
                      )}
                      {!msg.localStatus && !msg.readAt && !msg.deliveredAt && (
                        <span title="Sent">✓</span>
                      )}
                    </>
                  )}
                </div>
              </div>
            </div>
          );
        })}
        <div ref={bottomRef} />
      </div>

      <div className="composer">
        <textarea
          rows={1}
          value={draft}
          onChange={(e) => handleDraftChange(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={`Message ${conversation.peer.displayName}... (Enter to send)`}
        />
        <button type="button" className="btn-send" onClick={submitMessage} disabled={!draft.trim()}>
          Send
        </button>
      </div>
    </main>
  );
};
