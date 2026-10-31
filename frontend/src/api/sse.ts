/**
 * Minimal, spec-following Server-Sent Events parser for streams consumed via fetch()
 * (EventSource can't POST). See https://html.spec.whatwg.org/multipage/server-sent-events.html
 *
 * Handles: chunk boundaries anywhere (even inside "\r\n" or a multi-byte UTF-8 char),
 * CRLF / CR / LF line endings, multi-line `data:`, `event:` names, `id:`, comments, BOM.
 */

export interface SseEvent {
  event: string; // defaults to "message"
  data: string; // data lines joined with "\n"
  id?: string;
}

export class SseParser {
  private buffer = "";
  private eventName = "";
  private dataLines: string[] = [];
  private hasData = false;
  private lastId: string | undefined;
  private pendingCR = false;
  private first = true;

  /** Feed decoded text; returns the events completed by this chunk. */
  feed(chunk: string): SseEvent[] {
    if (this.first && chunk.length > 0) {
      this.first = false;
      if (chunk.charCodeAt(0) === 0xfeff) chunk = chunk.slice(1);
    }
    // A "\r" ending the previous chunk may be the first half of "\r\n".
    if (this.pendingCR) {
      this.pendingCR = false;
      if (chunk.startsWith("\n")) chunk = chunk.slice(1);
    }
    this.buffer += chunk;
    const events: SseEvent[] = [];
    let start = 0;
    for (let i = 0; i < this.buffer.length; i++) {
      const c = this.buffer.charCodeAt(i);
      if (c !== 10 && c !== 13) continue; // \n or \r
      const line = this.buffer.slice(start, i);
      if (c === 13) {
        if (i + 1 < this.buffer.length) {
          if (this.buffer.charCodeAt(i + 1) === 10) i++;
        } else {
          this.pendingCR = true;
        }
      }
      start = i + 1;
      const ev = this.processLine(line);
      if (ev) events.push(ev);
    }
    this.buffer = this.buffer.slice(start);
    return events;
  }

  /** Call at end of stream. Per spec an unterminated final event is discarded, but we are lenient. */
  flush(): SseEvent[] {
    const events: SseEvent[] = [];
    if (this.buffer.length > 0) {
      const ev = this.processLine(this.buffer);
      if (ev) events.push(ev);
      this.buffer = "";
    }
    const ev = this.dispatch();
    if (ev) events.push(ev);
    return events;
  }

  private processLine(line: string): SseEvent | null {
    if (line === "") return this.dispatch();
    if (line.startsWith(":")) return null; // comment / keep-alive
    const colon = line.indexOf(":");
    let field: string;
    let value: string;
    if (colon === -1) {
      field = line;
      value = "";
    } else {
      field = line.slice(0, colon);
      value = line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
    }
    switch (field) {
      case "event":
        this.eventName = value;
        break;
      case "data":
        this.dataLines.push(value);
        this.hasData = true;
        break;
      case "id":
        if (!value.includes("\0")) this.lastId = value;
        break;
      default: // "retry" and unknown fields are ignored
        break;
    }
    return null;
  }

  private dispatch(): SseEvent | null {
    const name = this.eventName || "message";
    const hasData = this.hasData;
    const data = this.dataLines.join("\n");
    this.eventName = "";
    this.dataLines = [];
    this.hasData = false;
    if (!hasData) return null;
    const ev: SseEvent = { event: name, data };
    if (this.lastId !== undefined) ev.id = this.lastId;
    return ev;
  }
}

/** Reads a byte stream and yields SSE events as they complete. */
export async function* readSseStream(
  stream: ReadableStream<Uint8Array>,
  signal?: AbortSignal,
): AsyncGenerator<SseEvent> {
  const reader = stream.getReader();
  const decoder = new TextDecoder("utf-8");
  const parser = new SseParser();
  const onAbort = () => {
    reader.cancel().catch(() => {});
  };
  signal?.addEventListener("abort", onAbort);
  let finished = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        finished = true;
        break;
      }
      yield* parser.feed(decoder.decode(value, { stream: true }));
    }
    const tail = decoder.decode();
    if (tail) yield* parser.feed(tail);
    yield* parser.flush();
  } finally {
    signal?.removeEventListener("abort", onAbort);
    // Consumer stopped early (break/return/throw): release the connection.
    if (!finished) await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
