import { ConnectionState, ServerEvent } from '../types';

/**
 * Lightweight resilient WebSocket client built directly on the browser's native WebSocket API.
 *
 * WHY custom client instead of Socket.IO:
 * - Pairs directly with our custom RFC 6455 Java server (zero protocol abstraction bloat).
 * - Implements exponential backoff with random jitter so a backend replica restart does not
 *   trigger a synchronized thundering-herd reconnect storm from mobile/desktop clients.
 * - Buffers outgoing `message.send` frames during transient offline blips and replays them with
 *   the same `clientMessageId` upon reconnect; server-side idempotency prevents duplicates.
 */
export class ChatWebSocketClient {
  private ws: WebSocket | null = null;
  private shouldReconnect = false;
  private reconnectAttempts = 0;
  private reconnectTimer: number | null = null;
  private pendingOutbound: string[] = [];

  private readonly onEvent: (event: ServerEvent) => void;
  private readonly onStateChange: (state: ConnectionState) => void;

  constructor(
    onEvent: (event: ServerEvent) => void,
    onStateChange: (state: ConnectionState) => void,
  ) {
    this.onEvent = onEvent;
    this.onStateChange = onStateChange;
  }

  connect(): void {
    this.shouldReconnect = true;
    this.clearReconnectTimer();
    this.openSocket(false);
  }

  disconnect(): void {
    this.shouldReconnect = false;
    this.clearReconnectTimer();
    this.pendingOutbound = [];
    if (this.ws) {
      this.ws.close(1000, 'Client logout');
      this.ws = null;
    }
    this.onStateChange('disconnected');
  }

  send(payload: Record<string, unknown>, queueIfOffline = false): boolean {
    const serialized = JSON.stringify({ v: 1, ...payload });
    // NOTE (MVP limitation): `pendingOutbound` lives only in memory, so refreshing the page while
    // offline clears queued unsent messages. In production, we would persist the outbox in
    // IndexedDB/localStorage and add an ACK timeout to auto-retry messages stuck in PENDING.
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(serialized);
      return true;
    }
    if (queueIfOffline) {
      this.pendingOutbound.push(serialized);
    }
    return false;
  }

  private openSocket(isReconnect: boolean): void {
    this.onStateChange(isReconnect ? 'reconnecting' : 'connecting');

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${window.location.host}/ws`;
    const socket = new WebSocket(url);
    this.ws = socket;

    socket.onopen = () => {
      this.reconnectAttempts = 0;
      this.onStateChange('connected');
      this.flushPending();
    };

    socket.onmessage = (evt) => {
      try {
        const parsed = JSON.parse(evt.data as string) as ServerEvent;
        this.onEvent(parsed);
      } catch {
        // Ignore malformed server frame
      }
    };

    socket.onclose = () => {
      if (this.ws === socket) {
        this.ws = null;
      }
      if (!this.shouldReconnect) {
        this.onStateChange('disconnected');
        return;
      }
      this.scheduleReconnect();
    };

    socket.onerror = () => {
      // Browser always fires onclose after onerror; let onclose drive backoff.
    };
  }

  private flushPending(): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return;
    }
    const toFlush = [...this.pendingOutbound];
    this.pendingOutbound = [];
    for (const raw of toFlush) {
      this.ws.send(raw);
    }
  }

  private scheduleReconnect(): void {
    this.onStateChange('reconnecting');
    const baseDelay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 15_000);
    // Add +/- 25% jitter to spread reconnect bursts across time.
    const jitter = baseDelay * (0.75 + Math.random() * 0.5);
    this.reconnectAttempts += 1;

    this.clearReconnectTimer();
    this.reconnectTimer = window.setTimeout(() => {
      if (this.shouldReconnect) {
        this.openSocket(true);
      }
    }, Math.round(jitter));
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}
