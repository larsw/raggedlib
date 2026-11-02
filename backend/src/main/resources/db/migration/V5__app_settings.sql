-- Settings changed at runtime (from the UI), overriding the configuration's defaults; one row per setting.
CREATE TABLE app_settings (
    key        text PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
