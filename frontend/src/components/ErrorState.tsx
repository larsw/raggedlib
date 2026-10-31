import { Button, Callout, Code, Intent, NonIdealState } from "@blueprintjs/core";
import { ApiError, errorMessage } from "../api/client";

export function isUnreachable(err: unknown): boolean {
  return err instanceof ApiError && err.unreachable;
}

/** Full-panel error: a "start the backend" hint when it's down, otherwise the error message. */
export function ErrorState({ error, onRetry, title }: { error: unknown; onRetry?: () => void; title?: string }) {
  const retry = onRetry && (
    <Button icon="refresh" onClick={onRetry} text="Retry" />
  );
  if (isUnreachable(error)) {
    return (
      <NonIdealState
        icon="offline"
        title="Backend unreachable"
        description={
          <>
            The raggedlib backend isn't answering on <Code>localhost:8080</Code>. Start it with{" "}
            <Code>docker compose up -d backend</Code>, or <Code>mvn spring-boot:run</Code> in <Code>backend/</Code>.
          </>
        }
        action={retry}
      />
    );
  }
  return (
    <NonIdealState icon="error" title={title ?? "Something went wrong"} description={errorMessage(error)} action={retry} />
  );
}

/** Inline error for places where a full NonIdealState is too much. */
export function ErrorCallout({ error, title }: { error: unknown; title?: string }) {
  const unreachable = isUnreachable(error);
  return (
    <Callout intent={unreachable ? Intent.WARNING : Intent.DANGER} icon={unreachable ? "offline" : "error"} title={title} compact>
      {unreachable ? "Backend unreachable — is it running on localhost:8080?" : errorMessage(error)}
    </Callout>
  );
}
