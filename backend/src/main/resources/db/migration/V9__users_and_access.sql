-- Multi-user support: users signed in through OIDC, their roles per library, personal and system-wide API keys,
-- favorite lists and book reviews.

-- One row per OIDC identity (issuer + subject), created at first sign-in and refreshed at every one after.
CREATE TABLE users (
    id               bigserial PRIMARY KEY,
    issuer           text        NOT NULL,
    subject          text        NOT NULL,
    email            text,
    display_name     text,
    -- the groups claim at the last sign-in; maps to library roles (raggedlib.auth.group-roles) for API keys too
    groups           text[]      NOT NULL DEFAULT '{}',
    -- global admin because the admin claim matched at the last sign-in (raggedlib.auth.admin-claim/admin-values)
    admin_from_claim boolean     NOT NULL DEFAULT false,
    -- global admin granted in the app by another admin
    admin_granted    boolean     NOT NULL DEFAULT false,
    -- disabled by an admin: sign-in refused, sessions and API keys stop working
    disabled         boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    last_login_at    timestamptz,
    UNIQUE (issuer, subject)
);

-- Roles granted per library. A global admin is ADMIN everywhere without a row here.
CREATE TABLE library_members (
    user_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    library_id bigint      NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    role       text        NOT NULL CHECK (role IN ('VIEWER', 'CURATOR', 'ADMIN')),
    granted_by bigint      REFERENCES users (id) ON DELETE SET NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, library_id)
);

CREATE INDEX library_members_library_idx ON library_members (library_id);

-- API keys for MCP clients and scripts. Only the SHA-256 of a key is stored; the key itself is shown once.
-- user_id null = a system-wide key, created by an admin, that acts as no user (no favorites, no reviews).
CREATE TABLE api_keys (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      bigint      REFERENCES users (id) ON DELETE CASCADE,
    name         text        NOT NULL,
    key_hash     text        NOT NULL UNIQUE,
    key_prefix   text        NOT NULL,
    scopes       text[]      NOT NULL,
    -- restricts the key to these libraries; null = every library its owner (or, for a system key, the server) has
    library_ids  bigint[],
    created_by   bigint      REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    expires_at   timestamptz,
    revoked_at   timestamptz
);

CREATE INDEX api_keys_user_idx ON api_keys (user_id);

-- Favorite lists: private to their owner, or shared with every signed-in user (read-only for them).
CREATE TABLE favorite_lists (
    id          bigserial PRIMARY KEY,
    owner_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        text        NOT NULL,
    description text,
    shared      boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX favorite_lists_owner_idx ON favorite_lists (owner_id);
CREATE INDEX favorite_lists_shared_idx ON favorite_lists (updated_at) WHERE shared;

CREATE TABLE favorite_list_books (
    list_id  bigint      NOT NULL REFERENCES favorite_lists (id) ON DELETE CASCADE,
    book_id  bigint      NOT NULL REFERENCES books (id) ON DELETE CASCADE,
    note     text,
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (list_id, book_id)
);

CREATE INDEX favorite_list_books_book_idx ON favorite_list_books (book_id);

-- One review per user and book: a 1-5 rating, a text, or both.
CREATE TABLE book_reviews (
    id         bigserial PRIMARY KEY,
    book_id    bigint      NOT NULL REFERENCES books (id) ON DELETE CASCADE,
    user_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    rating     smallint CHECK (rating BETWEEN 1 AND 5),
    body       text        NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (book_id, user_id),
    CHECK (rating IS NOT NULL OR body <> '')
);

CREATE INDEX book_reviews_user_idx ON book_reviews (user_id);
