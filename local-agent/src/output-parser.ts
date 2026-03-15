import { EventEmitter } from "events";
import stripAnsi from "strip-ansi";

class OutputParser extends EventEmitter {
  private buffer: string = "";
  private inResponse: boolean = false;
  private debounceTimer: NodeJS.Timeout | null = null;
  private chunkBuffer: string = "";

  feed(data: string): void {
    const clean = stripAnsi(data);
    this.buffer += clean;

    // Prompt patterns: Claude Code shows "> " or "? " on a new line when waiting
    const promptPattern = /(
|^)[>?]\s*$/;

    if (\!this.inResponse) {
      const lines = this.buffer.split("
");
      const hasContent = lines.some(
        (l) => l.trim().length > 0 && \!/^[>?]\s*$/.test(l.trim())
      );
      if (hasContent) {
        this.inResponse = true;
        this.chunkBuffer += clean;
        this.scheduleFlush();
      }
    } else {
      this.chunkBuffer += clean;
      this.scheduleFlush();
      if (promptPattern.test(this.buffer)) {
        this.finalize();
      }
    }
  }

  private scheduleFlush(): void {
    if (this.debounceTimer) return;
    this.debounceTimer = setTimeout(() => this.flush(), 50);
  }

  private flush(): void {
    this.debounceTimer = null;
    if (this.chunkBuffer.length > 0) {
      this.emit("chunk", this.chunkBuffer);
      this.chunkBuffer = "";
    }
  }

  private finalize(): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    if (this.chunkBuffer.length > 0) {
      this.emit("chunk", this.chunkBuffer);
      this.chunkBuffer = "";
    }
    this.emit("done");
    this.emit("prompt");
    this.inResponse = false;
    this.buffer = "";
  }

  reset(): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    this.buffer = "";
    this.inResponse = false;
    this.chunkBuffer = "";
  }
}

export default OutputParser;
