export type ClientType = 'agent' | 'device';

export interface Envelope {
  id: string;
  event: string;
  project_id?: string;
  stream_id?: string;
  seq?: number;
  payload?: unknown;
  ts: number;
}

export const Events = {
  AUTH_LOGIN: 'auth.login',
  AUTH_RESUME: 'auth.resume',
  AUTH_REFRESH: 'auth.refresh',
  AUTH_OK: 'auth.ok',
  AUTH_ERROR: 'auth.error',
  PROJECT_BIND: 'project.bind',
  PROJECT_BOUND: 'project.bound',
  MESSAGE_SEND: 'message.send',
  MESSAGE_CHUNK: 'message.chunk',
  MESSAGE_DONE: 'message.done',
  MESSAGE_ERROR: 'message.error',
  AGENT_STATUS: 'agent.status',
  AGENT_WAKEUP: 'agent.wakeup',
  FILE_SYNC: 'file.sync',
  PING: 'ping',
  PONG: 'pong',
  ERROR: 'error',
} as const;

export type EventType = typeof Events[keyof typeof Events];
