import { describe, expect, test } from "bun:test";
import type { Member } from "../api/types";
import { matchesUser, visibleMembers } from "./admin";

describe("matchesUser", () => {
  const ann = { displayName: "Ann Author", email: "ann@raggedlib.dev", groups: ["librarians", "staff"] };

  test("every word must match the name, email or a group", () => {
    expect(matchesUser(ann, "")).toBe(true);
    expect(matchesUser(ann, "ann")).toBe(true);
    expect(matchesUser(ann, "AUTHOR raggedlib")).toBe(true);
    expect(matchesUser(ann, "librarian")).toBe(true);
    expect(matchesUser(ann, "ann curators")).toBe(false);
  });

  test("users without email or groups", () => {
    expect(matchesUser({ displayName: "Bob", email: null }, "bob")).toBe(true);
    expect(matchesUser({ displayName: "Bob", email: null }, "dev")).toBe(false);
  });
});

describe("visibleMembers", () => {
  const member = (userId: number, displayName: string, role: Member["role"]): Member => ({
    userId,
    displayName,
    email: null,
    granted: role === "NONE" ? null : role,
    role,
    admin: false,
    disabled: false,
  });
  const members = [member(1, "Ann", "NONE"), member(2, "Bob", "VIEWER"), member(3, "Cid", "NONE"), member(4, "Dee", "ADMIN")];

  test("members with access first, otherwise in the server's order", () => {
    expect(visibleMembers(members, "", false).map((m) => m.displayName)).toEqual(["Bob", "Dee", "Ann", "Cid"]);
  });

  test("only those with access, and filtered", () => {
    expect(visibleMembers(members, "", true).map((m) => m.displayName)).toEqual(["Bob", "Dee"]);
    expect(visibleMembers(members, "ci", false).map((m) => m.displayName)).toEqual(["Cid"]);
  });
});
