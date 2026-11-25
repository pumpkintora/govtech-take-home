export interface UserSummary {
  id: string;
  username: string;
  displayName: string;
  online: boolean;
}

export interface MessageDto {
  seq: number;
  id: string;
  conversationId: string;
  senderId: string;
  recipientId: string;
  clientMessageId: string;
  body: string;
  createdAt: string;
  deliveredAt: string | null;
  readAt: string | null;
  /** Client-only flag for optimistic pending / failed sends */
  localStatus?: 'PENDING' | 'FAILED';
}

export interface ConversationView {
  id: string;
  peer: UserSummary;
  lastMessage: MessageDto | null;
  unreadCount: number;
  createdAt: string;
}

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

export type ServerEvent =
  | { v: number; type: 'session.ready'; nodeId: string; userId: string; latestSeq: number }
  | { v: number; type: 'message.ack'; clientMessageId: string; message: MessageDto }
  | { v: number; type: 'message.new'; message: MessageDto }
  | {
      v: number;
      type: 'message.status';
      conversationId: string;
      status: 'DELIVERED' | 'READ';
      seqs: number[];
      timestamp: string;
    }
  | { v: number; type: 'typing.update'; conversationId: string; userId: string; typing: boolean }
  | { v: number; type: 'presence.update'; userId: string; online: boolean }
  | { v: number; type: 'error'; code: string; message: string; clientMessageId?: string };
