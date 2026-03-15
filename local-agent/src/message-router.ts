import { v4 as uuidv4 } from 'uuid';
import RelayClient from './relay-client';
import ptyManager from './pty-manager';
import projectStore from './project-store';
import { Envelope, Events } from './types';

class MessageRouter {
  private relayClient: RelayClient;
  private streamSeq: Map<string, number> = new Map();

  constructor(relayClient: RelayClient) {
    this.relayClient = relayClient;
    this.relayClient.on('message', (env: Envelope) => this.handleEnvelope(env));
  }

  handleEnvelope(env: Envelope): void {
    switch (env.event) {
      case Events.MESSAGE_SEND:
        this.handleMessageSend(env);
        break;
      case Events.PROJECT_BIND:
        this.handleProjectBind(env);
        break;
      case Events.AGENT_WAKEUP:
        this.handleAgentWakeup(env);
        break;
      case Events.AUTH_OK:
        console.log('[MessageRouter] Auth OK');
        break;
      case Events.AUTH_ERROR:
        console.error('[MessageRouter] Auth error:', env.payload);
        break;
      default:
        console.log('[MessageRouter] Unhandled event: ' + env.event);
    }
  }

  private handleMessageSend(env: Envelope): void {
    const projectId = env.project_id;
    const streamId = env.stream_id;

    if (\!projectId || \!streamId) {
      console.error('[MessageRouter] message.send missing project_id or stream_id');
      return;
    }

    const project = projectStore.getById(projectId);
    if (\!project) {
      console.error('[MessageRouter] Unknown project: ' + projectId);
      this.relayClient.send({
        id: uuidv4(),
        event: Events.MESSAGE_ERROR,
        project_id: projectId,
        stream_id: streamId,
        ts: Date.now(),
        payload: { error: 'Project ' + projectId + ' not found' },
      });
      return;
    }

    if (\!ptyManager.isAlive(projectId)) {
      try {
        ptyManager.spawn(projectId, project.path);
      } catch (err) {
        console.error('[MessageRouter] Failed to spawn PTY:', err);
        this.relayClient.send({
          id: uuidv4(),
          event: Events.MESSAGE_ERROR,
          project_id: projectId,
          stream_id: streamId,
          ts: Date.now(),
          payload: { error: 'Failed to start Claude session' },
        });
        return;
      }
    }

    const session = ptyManager.get(projectId)\!;
    const parser = session.parser;

    parser.reset();
    this.streamSeq.set(streamId, 0);

    const onChunk = (content: string) => {
      this.sendChunk(projectId, streamId, content, false);
    };

    const onDone = () => {
      this.sendChunk(projectId, streamId, '', true);
      parser.removeListener('chunk', onChunk);
      parser.removeListener('done', onDone);
      this.streamSeq.delete(streamId);
    };

    parser.on('chunk', onChunk);
    parser.once('done', onDone);

    const payload = env.payload as { content?: string } | undefined;
    const content = payload?.content ?? '';
    try {
      ptyManager.write(projectId, content + '\n');
    } catch (err) {
      console.error('[MessageRouter] Failed to write to PTY:', err);
      parser.removeListener('chunk', onChunk);
      parser.removeListener('done', onDone);
      this.relayClient.send({
        id: uuidv4(),
        event: Events.MESSAGE_ERROR,
        project_id: projectId,
        stream_id: streamId,
        ts: Date.now(),
        payload: { error: 'Failed to send message to Claude' },
      });
    }
  }

  private handleProjectBind(env: Envelope): void {
    const payload = env.payload as {
      id?: string;
      name?: string;
      path?: string;
      agent_id?: string;
    } | undefined;

    if (\!payload?.id || \!payload?.name || \!payload?.path) {
      console.error('[MessageRouter] project.bind missing required fields');
      return;
    }

    const existing = projectStore.getById(payload.id);
    if (existing) {
      projectStore.update(payload.id, { name: payload.name, path: payload.path });
    } else {
      projectStore.add({
        id: payload.id,
        name: payload.name,
        path: payload.path,
        agentId: payload.agent_id ?? '',
        createdAt: Date.now(),
      });
    }

    console.log('[MessageRouter] Project bound: ' + payload.name + ' (' + payload.id + ')');

    this.relayClient.send({
      id: uuidv4(),
      event: Events.PROJECT_BOUND,
      project_id: payload.id,
      ts: Date.now(),
      payload: { project_id: payload.id },
    });
  }

  private handleAgentWakeup(env: Envelope): void {
    console.log('[MessageRouter] Agent wakeup received', env.payload);
  }

  private sendChunk(
    projectId: string,
    streamId: string,
    content: string,
    done: boolean
  ): void  const seq = (this.streamSeq.get(streamId) ?? 0) + 1;
    this.streamSeq.set(streamId, seq);

    this.relayClient.send({
      id: uuidv4(),
      event: done ? Events.MESSAGE_DONE : Events.MESSAGE_CHUNK,
      project_id: projectId,
      stream_id: streamId,
      seq,
      ts: Date.now(),
      payload: done ? { seq_total: seq } : { content },
    });
  }
}

export default MessageRouter;
