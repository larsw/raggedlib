import { useCallback, useEffect, useRef, useState } from "react";
import { api, isUnauthorized, onUnauthorized } from "../api/client";
import type { Me } from "../api/types";
import { isOtherUser, loginErrorMessage, takeLoginError } from "../lib/auth";
import { forgetUserState, loadJson, saveJson, saveSessionString, STORAGE_KEYS, takeSessionString } from "../lib/storage";
import { usePageVisible } from "./usePageVisible";

export interface SignedOutNotice {
  intent: "danger" | "warning" | "none";
  message: string;
}

export type AuthState =
  | { kind: "loading" }
  | { kind: "signed-in"; me: Me }
  /** loginUrl null: the backend named no way to sign in */
  | { kind: "signed-out"; loginUrl: string | null; notice: SignedOutNotice | null }
  /** /api/me failed for another reason before anyone was signed in (typically: backend unreachable) */
  | { kind: "error"; error: unknown };

/** Why the sign-in state is being checked: on load, after a 401 mid-session, or after signing out. */
type Reason = { kind: "load" } | { kind: "expired"; message: string } | { kind: "signed-out" };

let pendingLoginError: string | null | undefined;

/** The `?login_error=` the backend redirected to after a failed sign-in, taken off the address bar on first use. */
function consumeLoginError(): string | null {
  if (pendingLoginError === undefined) {
    const { code, cleanUrl } = takeLoginError(window.location.href);
    if (code) window.history.replaceState(window.history.state, "", cleanUrl);
    pendingLoginError = code;
  }
  const code = pendingLoginError;
  pendingLoginError = null; // shown once
  return code;
}

function noticeFor(reason: Reason, wasSignedIn: boolean): SignedOutNotice | null {
  const loginError = consumeLoginError();
  if (loginError) return { intent: "danger", message: loginErrorMessage(loginError) };
  if (reason.kind === "signed-out") return { intent: "none", message: "You have signed out." };
  if (reason.kind === "expired" && wasSignedIn) {
    return /disabled/i.test(reason.message)
      ? { intent: "danger", message: loginErrorMessage("account_disabled") }
      : { intent: "warning", message: "Your session has ended. Sign in again to carry on where you were." };
  }
  return null;
}

/** Called before the signed-in app renders: a different user than last time must not inherit what was left here. */
function prepareFor(me: Me) {
  const last = loadJson<number | null>(STORAGE_KEYS.user, null);
  if (isOtherUser(last, me.id)) forgetUserState();
  saveJson(STORAGE_KEYS.user, me.id);
  // back to the tab the user signed in from (the backend's redirect after sign-in drops the #/…)
  const hash = takeSessionString(STORAGE_KEYS.returnHash);
  if (hash && !window.location.hash) window.history.replaceState(window.history.state, "", hash);
}

/**
 * Who is signed in (GET /api/me), kept current: checked on load, again whenever an API call comes back 401 (the
 * session ended), and when the tab becomes visible again (roles may have changed meanwhile).
 */
export function useAuth() {
  const [state, setState] = useState<AuthState>({ kind: "loading" });
  const signedIn = useRef(false);
  const inflight = useRef<Promise<void> | null>(null);
  /** why the check in flight runs: a 401 or a sign-out arriving meanwhile outranks a routine refresh */
  const reason = useRef<Reason>({ kind: "load" });

  const check = useCallback((why: Reason = { kind: "load" }): Promise<void> => {
    if (why.kind !== "load") reason.current = why;
    if (inflight.current) return inflight.current;
    const run = api
      .me()
      .then((me) => {
        if (!signedIn.current) prepareFor(me);
        const loginError = consumeLoginError();
        if (loginError) console.warn(`[raggedlib] sign-in failed (${loginError}), but a session is still active`);
        signedIn.current = true;
        // an unchanged answer keeps the same object, so a refresh doesn't re-render the whole app
        setState((prev) =>
          prev.kind === "signed-in" && JSON.stringify(prev.me) === JSON.stringify(me) ? prev : { kind: "signed-in", me },
        );
      })
      .catch((err: unknown) => {
        if (isUnauthorized(err)) {
          const notice = noticeFor(reason.current, signedIn.current);
          signedIn.current = false;
          setState({ kind: "signed-out", loginUrl: err.loginUrl, notice });
        } else if (!signedIn.current) {
          setState({ kind: "error", error: err });
        }
        // signed in, and /me failed for another reason (backend restarting…): stay; the views show their own errors
      })
      .finally(() => {
        inflight.current = null;
        reason.current = { kind: "load" };
      });
    inflight.current = run;
    return run;
  }, []);

  useEffect(() => {
    void check();
    return onUnauthorized((err) => void check({ kind: "expired", message: err.message }));
  }, [check]);

  const visible = usePageVisible();
  useEffect(() => {
    if (visible && signedIn.current) void check();
  }, [visible, check]);

  /** Re-reads /api/me (e.g. after an admin changed the caller's own roles). */
  const refresh = useCallback(() => check(), [check]);

  const signIn = useCallback((loginUrl: string) => {
    saveSessionString(STORAGE_KEYS.returnHash, window.location.hash);
    window.location.assign(loginUrl);
  }, []);

  /** Ends the session (POST /api/auth/logout) and forgets this user's state in the browser. Throws on failure. */
  const signOut = useCallback(async () => {
    await api.logout();
    forgetUserState();
    // a check that started before the session ended would still find it signed in: let it finish, then ask again
    if (inflight.current) await inflight.current;
    signedIn.current = false;
    await check({ kind: "signed-out" });
  }, [check]);

  return { state, refresh, signIn, signOut };
}
