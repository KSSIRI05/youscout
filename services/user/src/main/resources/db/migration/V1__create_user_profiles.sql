-- User owns this table. Authentication credentials remain in Keycloak's database.
CREATE TABLE user_profiles (
    subject VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    biography VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
