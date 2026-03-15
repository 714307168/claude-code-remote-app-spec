import * as pty from "node-pty";
import OutputParser from "./output-parser";

interface PtySession {
  pty: pty.IPty;
  parser: OutputParser;
  projectId: string;
  cwd: string;
}

class PtyManager {
  private sessions: Map<string, PtySession> = new Map();

  spawn(projectId: string, cwd: string): PtySession {
    if (this.sessions.has(projectId)) {
      this.kill(projectId);
    }

    const isWindows = process.platform === "win32";
    const shell = isWindows ? "cmd.exe" : "bash";
    const args = isWindows ? ["/c", "claude"] : ["-c", "claude"];

    const ptyProcess = pty.spawn(shell, args, {
      name: "xterm-256color",
      cols: 220,
      rows: 50,
      cwd,
      env: {
        ...process.env,
        TERM: "xterm-256color",
      } as { [key: string]: string },
    });

    const parser = new OutputParser();

    ptyProcess.onData((data: string) => {
      parser.feed(data);
    });

    ptyProcess.onExit(() => {
      this.sessions.delete(projectId);
    });

    const session: PtySession = { pty: ptyProcess, parser, projectId, cwd };
    this.sessions.set(projectId, session);
    return session;
  }

  write(projectId: string, input: string): void {
    const session = this.sessions.get(projectId);
    if (\!session) {
      throw new Error(`No PTY session for project ${projectId}`);
    }
    session.pty.write(input);
  }

  kill(projectId: string): void {
    const session = this.sessions.get(projectId);
    if (session) {
      session.parser.reset();
      session.pty.kill();
      this.sessions.delete(projectId);
    }
  }

  get(projectId: string): PtySession | undefined {
    return this.sessions.get(projectId);
  }

  isAlive(projectId: string): boolean {
    return this.sessions.has(projectId);
  }
}

export { PtySession, PtyManager };
export default new PtyManager();
