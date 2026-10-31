import { describe, expect, test } from "bun:test";
import { parseChatEvent } from "./client";
import { readSseStream, SseParser, type SseEvent } from "./sse";

function parseAll(chunks: string[]): SseEvent[] {
  const p = new SseParser();
  const out: SseEvent[] = [];
  for (const c of chunks) out.push(...p.feed(c));
  out.push(...p.flush());
  return out;
}

/** Splits a string into every possible pair of chunks and checks the result is unchanged. */
function expectSplitInvariant(input: string, expected: SseEvent[]) {
  expect(parseAll([input])).toEqual(expected);
  for (let i = 0; i <= input.length; i++) {
    expect(parseAll([input.slice(0, i), input.slice(i)])).toEqual(expected);
  }
  // one char at a time
  expect(parseAll(input.split(""))).toEqual(expected);
}

describe("SseParser", () => {
  test("parses named events with JSON data", () => {
    const input =
      'event: sources\ndata: [{"ref":1}]\n\n' + 'event: token\ndata: {"t":"Hel"}\n\n' + 'event: token\ndata: {"t":"lo"}\n\n';
    expect(parseAll([input])).toEqual([
      { event: "sources", data: '[{"ref":1}]' },
      { event: "token", data: '{"t":"Hel"}' },
      { event: "token", data: '{"t":"lo"}' },
    ]);
  });

  test("defaults the event name to message", () => {
    expect(parseAll(["data: hi\n\n"])).toEqual([{ event: "message", data: "hi" }]);
  });

  test("joins multi-line data with newlines", () => {
    expect(parseAll(["event: x\ndata: a\ndata:b\ndata:  c\n\n"])).toEqual([{ event: "x", data: "a\nb\n c" }]);
  });

  test("empty data field yields empty string, event without data is dropped", () => {
    expect(parseAll(["data\n\nevent: nodata\n\n"])).toEqual([{ event: "message", data: "" }]);
  });

  test("ignores comments and unknown fields, keeps id", () => {
    expect(parseAll([": keep-alive\nretry: 1000\nfoo: bar\nid: 7\ndata: x\n\n"])).toEqual([
      { event: "message", data: "x", id: "7" },
    ]);
  });

  test("is invariant to chunk boundaries (LF)", () => {
    expectSplitInvariant('event: token\ndata: {"t":"a b"}\n\nevent: done\ndata: {"elapsedMs":5}\n\n', [
      { event: "token", data: '{"t":"a b"}' },
      { event: "done", data: '{"elapsedMs":5}' },
    ]);
  });

  test("is invariant to chunk boundaries (CRLF, split inside \\r\\n)", () => {
    expectSplitInvariant("event: token\r\ndata: one\r\ndata: two\r\n\r\nevent: done\r\ndata: {}\r\n\r\n", [
      { event: "token", data: "one\ntwo" },
      { event: "done", data: "{}" },
    ]);
  });

  test("handles bare CR line endings", () => {
    expectSplitInvariant("event: a\rdata: 1\r\rdata: 2\r\r", [
      { event: "a", data: "1" },
      { event: "message", data: "2" },
    ]);
  });

  test("strips a leading BOM", () => {
    expect(parseAll(["﻿data: x\n\n"])).toEqual([{ event: "message", data: "x" }]);
  });

  test("flushes an unterminated final event", () => {
    expect(parseAll(["event: done\ndata: {}"])).toEqual([{ event: "done", data: "{}" }]);
  });

  test("event name does not leak into the next event", () => {
    expect(parseAll(["event: a\ndata: 1\n\ndata: 2\n\n"])).toEqual([
      { event: "a", data: "1" },
      { event: "message", data: "2" },
    ]);
  });
});

describe("readSseStream", () => {
  function streamOf(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
    return new ReadableStream({
      start(controller) {
        for (const c of chunks) controller.enqueue(c);
        controller.close();
      },
    });
  }

  test("decodes UTF-8 split across byte chunks", async () => {
    const bytes = new TextEncoder().encode('event: token\ndata: {"t":"Grüße – λ 🔐"}\n\n');
    // split every byte so multi-byte chars are torn apart
    const chunks = Array.from(bytes, (b) => new Uint8Array([b]));
    const events: SseEvent[] = [];
    for await (const ev of readSseStream(streamOf(chunks))) events.push(ev);
    expect(events).toEqual([{ event: "token", data: '{"t":"Grüße – λ 🔐"}' }]);
    expect(parseChatEvent(events[0]!.event, events[0]!.data)).toEqual({ type: "token", t: "Grüße – λ 🔐" });
  });

  test("cancels the underlying stream when the consumer stops early", async () => {
    let cancelled = false;
    const enc = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        controller.enqueue(enc.encode("data: tick\n\n"));
      },
      cancel() {
        cancelled = true;
      },
    });
    let n = 0;
    for await (const _ of readSseStream(stream)) {
      if (++n === 3) break;
    }
    expect(n).toBe(3);
    expect(cancelled).toBe(true);
  });
});

describe("parseChatEvent", () => {
  test("maps the chat protocol", () => {
    expect(parseChatEvent("sources", "[]")).toEqual({ type: "sources", sources: [] });
    expect(parseChatEvent("token", '{"t":"x"}')).toEqual({ type: "token", t: "x" });
    expect(parseChatEvent("done", '{"elapsedMs":42}')).toEqual({ type: "done", elapsedMs: 42 });
    expect(parseChatEvent("error", '{"error":"boom"}')).toEqual({ type: "error", error: "boom" });
  });

  test("tolerates malformed payloads", () => {
    expect(parseChatEvent("token", "not json")).toBeNull();
    expect(parseChatEvent("error", "plain text")).toEqual({ type: "error", error: "plain text" });
    expect(parseChatEvent("ping", "{}")).toBeNull();
  });
});
