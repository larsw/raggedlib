import { Button, Intent, TextArea, Tooltip } from "@blueprintjs/core";
import { useEffect, useRef, useState } from "react";

interface Props {
  busy: boolean;
  disabled?: boolean;
  onSend: (text: string) => void;
  onStop: () => void;
}

/** Question box: Enter sends, Shift+Enter adds a newline. */
export function ChatInput({ busy, disabled, onSend, onStop }: Props) {
  const [text, setText] = useState("");
  const ref = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!busy) ref.current?.focus();
  }, [busy]);

  const submit = () => {
    if (busy || disabled || !text.trim()) return;
    onSend(text);
    setText("");
  };

  return (
    <div className="chat-input">
      <TextArea
        inputRef={ref}
        aria-label="Ask a question about your library"
        placeholder="Ask your library…  (Enter to send, Shift+Enter for a new line)"
        value={text}
        onChange={(e) => setText(e.currentTarget.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            submit();
          }
        }}
        autoResize
        rows={2}
        fill
        disabled={disabled}
      />
      {busy ? (
        <Tooltip content="Stop generating" placement="top">
          <Button icon="stop" intent={Intent.DANGER} aria-label="Stop generating" onClick={onStop} size="large" />
        </Tooltip>
      ) : (
        <Tooltip content="Send (Enter)" placement="top">
          <Button
            icon="send-message"
            intent={Intent.PRIMARY}
            aria-label="Send question"
            onClick={submit}
            disabled={disabled || !text.trim()}
            size="large"
          />
        </Tooltip>
      )}
    </div>
  );
}
