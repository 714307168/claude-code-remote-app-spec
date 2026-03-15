import WebSocket from 'ws';
import { EventEmitter } from 'events';
import { v4 as uuidv4 } from 'uuid';
import { Envelope, Events } from './types';

class RelayClient extends EventEmitter {
  private ws: WebSocket | null = null;
  private reconnectDelay: number = 1000;
  private maxDelay: number = 30000;
  private lastSeq: number = 0;
  private agentId: string;
  private token: string;
  private serverUrl: string;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private intentionalDisconnect: boolean = false;

  constructor(serverUrl: string, agentId: string, token: string) {
    super();
    this.serverUrl = serverUrl;
    this.agentId = agentId;
    this.token = token;
  }

  connect(): void {
    this.intentionalDisconnect = false;
    this.ws = new WebSocket(this.serverUrl);

    this.ws.on('open', () => this.onOpen());
    this.ws.on('message', (data: WebSocket.RawData) => this.onMessage(data.toString()));
    this.ws.on('close', () => this.onClose());
    this.ws.on('error', (err: Error) => this.onError(err));
  }

  disconnect(): void {
    this.intentionalDisconnect = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  send(env: Envelope): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[RelayClient] Cannot send — not connected');
      return;
    }
    this.ws.send(JSON.stringify(env));
  }

  private onOpen(): void {
    console.log('[RelayClient] Connected to relay server');
    this.resetBackoff();

    const event = this.lastSeq > 0 ? Events.AUTH_RESUME : Events.AUTH_LOGIN;
    const env: Envelope = {
      id: uuidv4(),
      event,
      ts: Date.now(),
      payload: {
        agent_id: this.agentId,
        token: this.token,
        last_seq: this.lastSeq,
        client_type: 'agent',
      },
    };
    this.send(env);
    this.emit('connected');
  }

  private onMessage(data: string): void {
    try {
      const env: Envelope = JSON.parse(data);
      if (env.seq !== undefined && env.seq > this.lastSeq) {
        this.lastSeq = env.seq;
      }

      // Respond to pings
      if (env.event === Events.PING) {
        this.send({ id: uuidv4(), event: Events.PONG, ts: Date.now() });
        return;
      }

      this.emit('message', env);
    } catch (err) {
      console.error('[RelayClient] Failed to parse message:', err);
    }
  }

  private onClose(): void {
    console.log('[RelayClient] Connection closed');
    this.ws = null;
    this.emit('disconnected');
    if (!this.intentionalDisconnect) {
      this.scheduleReconnect();
    }
  }

  private onError(err: Error): void {
    console.error('[RelayClient] WebSocket error:', err.message);
    this.emit('error', err);
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return;
    console.log(`[RelayClient] Reconnecting in ${this.reconnectDelay}ms...`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, this.reconnectDelay);
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, this.maxDelay);
  }

  private resetBackoff(): void {
    this.reconnectDelay = 1000;
  }
}

export default RelayClient;
