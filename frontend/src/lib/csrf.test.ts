import { describe, expect, test } from "bun:test";
import { csrfHeaders, needsCsrf, readCookie } from "./csrf";

describe("readCookie", () => {
  test("finds a cookie among others, whitespace-tolerant", () => {
    const cookies = "theme=dark; XSRF-TOKEN=3f9a-77c1 ;JSESSIONID=abc";
    expect(readCookie(cookies, "XSRF-TOKEN")).toBe("3f9a-77c1");
    expect(readCookie(cookies, "theme")).toBe("dark");
    expect(readCookie(cookies, "JSESSIONID")).toBe("abc");
  });

  test("matches whole names only", () => {
    expect(readCookie("X-XSRF-TOKEN=nope; XSRF-TOKEN-OLD=no", "XSRF-TOKEN")).toBeNull();
  });

  test("missing and empty", () => {
    expect(readCookie("", "XSRF-TOKEN")).toBeNull();
    expect(readCookie("a=1", "XSRF-TOKEN")).toBeNull();
    expect(readCookie("XSRF-TOKEN=", "XSRF-TOKEN")).toBe("");
  });

  test("decodes percent-encoding, keeps undecodable values as they are", () => {
    expect(readCookie("XSRF-TOKEN=a%3Db", "XSRF-TOKEN")).toBe("a=b");
    expect(readCookie("XSRF-TOKEN=a%zz", "XSRF-TOKEN")).toBe("a%zz");
  });

  test("a value may itself contain '='", () => {
    expect(readCookie("t=abc==", "t")).toBe("abc==");
  });
});

describe("csrfHeaders", () => {
  const cookies = "XSRF-TOKEN=tok123";

  test("safe methods need no token", () => {
    expect(needsCsrf(undefined)).toBe(false);
    expect(needsCsrf("get")).toBe(false);
    expect(needsCsrf("HEAD")).toBe(false);
    expect(csrfHeaders("GET", cookies)).toEqual({});
  });

  test("writes echo the cookie", () => {
    for (const m of ["POST", "put", "DELETE", "PATCH"]) {
      expect(needsCsrf(m)).toBe(true);
      expect(csrfHeaders(m, cookies)).toEqual({ "X-XSRF-TOKEN": "tok123" });
    }
  });

  test("no cookie yet: no header (the backend answers 403, and sets the cookie)", () => {
    expect(csrfHeaders("POST", "")).toEqual({});
    expect(csrfHeaders("POST", "XSRF-TOKEN=")).toEqual({});
  });
});
