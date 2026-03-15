import { EventEmitter } from 'events';
import stripAnsi from 'strip-ansi';

class OutputParser extends EventEmitter {
  private buffer: string = '';
  private inResponse: boolean = false;
  private debounceTimer: NodeJS.Timeout | null = null;
  private chunkBuffer: string = '';

  feed(data: string): void {
    const clean = stripAnsi(data);
    this.buffer += clean;

    // Detect prompt patterns indicating Claude is waiting for input
    // Claude Code prompt ends with "> " or "? " on a new line
    const promptPattern = /(\n|^)[>\?]\s*$/;
    const interactivePattern = /(\n|^)>\s*$/m;

    if (!this.inResponse) {
      // Start capturing after we see content that isn't just the prompt
      const lines = this.buffer.split('\n');
      // Look for non-empty, non-prompt lines as signal that response started
      const hasContent = lines.some(
        (l) => l.trim().length > 0 && !/^[>\?]\s*$/.test(l.trim())
      );
      if (hasContent) {
        this.inResponse = true;
        this.chunkBuffer += clean;
        this.scheduleFlush();
      }
    } else {
      this.chunkBuffer += clean;
      this.scheduleFlush();

      // Check if Claude prompt appeared — response is done
      if (promptPattern.test(this.buffer) || interactivePattern.test(this.buffer)) {
        this.finalize();
      }
    }
  }

  private scheduleFlush(): void {
    if (this.debounceTimer) return;
    this.debounceTimer = setTimeout(() => {
      this.flush();
    }, 50);
  }

  private flush(): void {
    this.debounceTimer = null;
    if (this.chunkBuffer.length > 0) {
      this.emit('chunk', this.chunkBuffer);
      this.chunkBuffer = '';
    }
  }

  private finalize(): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    // Flush any remaining chunk content
    if (this.chunkBuffer.length > 0) {
      this.emit('chunk', this.chunkBuffer);
      this.chunkBuffer = '';
    }
    this.emit('done');
    this.emit('prompt');
    this.inResponse = false;
    this.buffer = '';
  }

  reset(): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    this.buffer = '';
    this.inResponse = false;
    this.chunkBuffer = '';
  }
}

export default OutputParser;
