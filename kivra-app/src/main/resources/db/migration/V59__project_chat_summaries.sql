CREATE TABLE project_chat_summaries (
    session_id              CHAR(26)    NOT NULL,
    covers_up_to_message_id BIGINT      NOT NULL,
    body                    TEXT        NOT NULL,
    generation              INT         NOT NULL,
    created_at              DATETIME(6) NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT fk_chat_summary_session FOREIGN KEY (session_id) REFERENCES project_chat_sessions (project_id) ON DELETE CASCADE
);
