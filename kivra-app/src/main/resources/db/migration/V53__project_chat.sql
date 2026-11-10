-- V53: project chat sessions and messages.
-- Each project has a single chat session (one-to-one).
-- Messages are appended in chronological order (id auto-increment).

CREATE TABLE project_chat_sessions (
    project_id   CHAR(26)     NOT NULL,
    title        VARCHAR(256) NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (project_id),
    CONSTRAINT fk_chat_session_project FOREIGN KEY (project_id) REFERENCES projects (project_id) ON DELETE CASCADE
);

CREATE INDEX ix_chat_session_updated ON project_chat_sessions (updated_at);

CREATE TABLE project_chat_messages (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    session_id   CHAR(26)     NOT NULL,
    role         ENUM('user','assistant') NOT NULL,
    content      LONGTEXT     NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id) REFERENCES project_chat_sessions (project_id) ON DELETE CASCADE
);

CREATE INDEX ix_chat_messages_session ON project_chat_messages (session_id, created_at);
