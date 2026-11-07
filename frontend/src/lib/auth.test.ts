import { describe, expect, test } from "bun:test";
import {
  abbreviateDisplayName,
  isOtherUser,
  loginErrorMessage,
  roleAtLeast,
  roleIn,
  roleLabel,
  takeLoginError,
  toGrantable,
} from "./auth";

describe("roles", () => {
  test("each role includes the ones below it", () => {
    expect(roleAtLeast("ADMIN", "CURATOR")).toBe(true);
    expect(roleAtLeast("CURATOR", "CURATOR")).toBe(true);
    expect(roleAtLeast("VIEWER", "CURATOR")).toBe(false);
    expect(roleAtLeast("NONE", "VIEWER")).toBe(false);
    expect(roleAtLeast(null, "VIEWER")).toBe(false);
    expect(roleAtLeast(undefined, "NONE")).toBe(true);
  });

  test("labels and grantable roles", () => {
    expect(roleLabel("CURATOR")).toBe("curator");
    expect(roleLabel("NONE")).toBe("no access");
    expect(roleLabel(null)).toBe("no access");
    expect(toGrantable("ADMIN")).toBe("admin");
    expect(toGrantable("NONE")).toBeNull();
    expect(toGrantable(null)).toBeNull();
  });

  test("role in a library, NONE when it isn't listed", () => {
    const libs = [
      { id: 1, role: "VIEWER" as const },
      { id: 2, role: "CURATOR" as const },
    ];
    expect(roleIn(libs, 2)).toBe("CURATOR");
    expect(roleIn(libs, 3)).toBe("NONE");
    expect(roleIn(libs, null)).toBe("NONE");
  });
});

describe("sign-in errors", () => {
  test("takes the code off the URL and keeps the rest", () => {
    expect(takeLoginError("http://h:3000/?login_error=account_disabled#/library")).toEqual({
      code: "account_disabled",
      cleanUrl: "/#/library",
    });
    expect(takeLoginError("http://h/?a=1&login_error=x&b=2")).toEqual({ code: "x", cleanUrl: "/?a=1&b=2" });
  });

  test("no code: the URL stays as it is", () => {
    expect(takeLoginError("http://h/#/ask")).toEqual({ code: null, cleanUrl: "http://h/#/ask" });
  });

  test("an empty code still counts as a failure", () => {
    expect(takeLoginError("http://h/?login_error=").code).toBe("login_failed");
  });

  test("messages", () => {
    expect(loginErrorMessage("account_disabled")).toContain("disabled");
    expect(loginErrorMessage("weird_thing")).toContain("weird_thing");
  });
});

describe("abbreviateDisplayName", () => {
  test("short names stay", () => {
    expect(abbreviateDisplayName("Lars Wilhelmsen")).toBe("Lars Wilhelmsen");
    expect(abbreviateDisplayName("  Ann   Author ")).toBe("Ann Author");
  });

  test("long names keep the surname", () => {
    expect(abbreviateDisplayName("Alexander Hamilton-Featherstonehaugh")).toBe("A. Hamilton-Featherstonehaugh");
    expect(abbreviateDisplayName("jean-paul charles aymard sartre")).toBe("J. C. A. sartre");
  });

  test("one long word can't be shortened", () => {
    expect(abbreviateDisplayName("someone.with.a.long@email.example")).toBe("someone.with.a.long@email.example");
  });
});

describe("isOtherUser", () => {
  test("only a different, known previous user counts", () => {
    expect(isOtherUser(null, 5)).toBe(false);
    expect(isOtherUser(5, 5)).toBe(false);
    expect(isOtherUser(4, 5)).toBe(true);
  });
});
