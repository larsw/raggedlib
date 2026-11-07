import { Button, Callout, Card, Classes, Intent, Spinner } from "@blueprintjs/core";
import { useEffect, type ReactNode } from "react";
import type { Me } from "../../api/types";
import { useAuth, type SignedOutNotice } from "../../hooks/useAuth";
import type { Theme } from "../../hooks/useTheme";
import { AppLogo, ThemeButton } from "../AppNavbar";
import { ErrorState } from "../ErrorState";

/** What the signed-in app gets from the gate. */
export interface SignedIn {
  me: Me;
  /** Re-reads /api/me. */
  refreshMe: () => Promise<void>;
  /** Ends the session; throws if the backend refused. */
  signOut: () => Promise<void>;
}

const RETRY_MS = 5000;

interface Props {
  theme: Theme;
  onToggleTheme: () => void;
  children: (auth: SignedIn) => ReactNode;
}

/**
 * Renders the app only for a signed-in user: a spinner while /api/me answers, the sign-in screen without a session
 * (also when one ends mid-way), and a "backend unreachable" state, retried every few seconds, when there's no answer.
 */
export function AuthGate({ theme, onToggleTheme, children }: Props) {
  const { state, refresh, signIn, signOut } = useAuth();

  useEffect(() => {
    if (state.kind !== "error") return;
    const t = setInterval(() => void refresh(), RETRY_MS);
    return () => clearInterval(t);
  }, [state.kind, refresh]);

  switch (state.kind) {
    case "loading":
      return (
        <div className="auth-screen" aria-busy>
          <Spinner size={32} aria-label="Checking who is signed in" />
        </div>
      );
    case "error":
      return (
        <div className="auth-screen">
          <ErrorState error={state.error} onRetry={() => void refresh()} title="Couldn't reach raggedlib" />
        </div>
      );
    case "signed-out":
      return (
        <SignInScreen
          loginUrl={state.loginUrl}
          notice={state.notice}
          onSignIn={signIn}
          theme={theme}
          onToggleTheme={onToggleTheme}
        />
      );
    case "signed-in":
      return children({ me: state.me, refreshMe: refresh, signOut });
  }
}

const NOTICE_ICON = { danger: "error", warning: "time", none: "log-out" } as const;

function SignInScreen({
  loginUrl,
  notice,
  onSignIn,
  theme,
  onToggleTheme,
}: {
  loginUrl: string | null;
  notice: SignedOutNotice | null;
  onSignIn: (loginUrl: string) => void;
  theme: Theme;
  onToggleTheme: () => void;
}) {
  return (
    <div className="auth-screen">
      <div className="auth-screen-corner">
        <ThemeButton theme={theme} onClick={onToggleTheme} />
      </div>
      <Card className="auth-card" elevation={2}>
        <div className="auth-brand">
          <AppLogo />
          <span>raggedlib</span>
        </div>
        <h2 className={`${Classes.HEADING} auth-title`}>Sign in to your libraries</h2>
        <p className={Classes.TEXT_MUTED}>
          Search, ask and read the books shared with you. Sign in with your organization's account to continue.
        </p>
        {notice && (
          <Callout
            intent={notice.intent === "none" ? Intent.NONE : notice.intent}
            icon={NOTICE_ICON[notice.intent]}
            compact
            className="auth-notice"
          >
            {notice.message}
          </Callout>
        )}
        {loginUrl ? (
          <Button
            size="large"
            intent={Intent.PRIMARY}
            icon="log-in"
            text="Sign in"
            fill
            autoFocus
            onClick={() => onSignIn(loginUrl)}
          />
        ) : (
          <Callout intent={Intent.WARNING} icon="warning-sign" compact>
            The server didn't say where to sign in. Check its OIDC configuration (application-oidc.yml).
          </Callout>
        )}
      </Card>
    </div>
  );
}
