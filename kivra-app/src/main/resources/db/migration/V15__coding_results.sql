-- V15: the accepted change set (PatchSet) of a coding attempt (design: Issue #187 design-coding-runtime.md section 2.6 and
-- docs/design/15-coding-attempts.md section 6.5, decided 2026-10-17: a separate table, not a column on coding_attempts).
-- Expand-only: one new table, no change to an existing table (V1 .. V14 are not edited).
--
-- Only a change set that passed the shape checks and PatchPolicy is stored (CodingRuntimeService.submitResult). Process output
-- (stdout / stderr), prompts, model text and secrets are never stored here. The attempt row keeps result_digest; this table
-- keeps the body, and the body is looked up by (attempt_id, digest).
--
-- patch_json: JSON of the PatchSet in the shape PatchSetParser reads: {"files":[{"op":"write","path","content"} |
-- {"op":"delete","path"}],"summary","rationale"?}. The application caps the stored text at PatchSetParser.MAX_INPUT_BYTES
-- (2 MiB), the size the parser accepts. A PatchPolicy-accepted change set is at most 1 MiB of raw content (maxTotalBytes); JSON
-- escaping can expand control characters up to 6 times, so the text can be larger than the raw content. MEDIUMTEXT (up to 16 MiB
-- on MariaDB) holds the 2 MiB cap with room to spare; LONGTEXT would only allow what the reader refuses. As V13 response_json.
-- created_at: UTC DATETIME(6), set by the application clock, like the other tables.
--
-- Retention: rows are not deleted by this migration or by the application yet. A deletion policy (for example after the Run is
-- terminal) is not implemented; it needs its own operations PR.

CREATE TABLE coding_results (
    attempt_id    CHAR(26)    NOT NULL,
    result_digest CHAR(64)    NOT NULL,                       -- SHA-256 hex of the result, the same value as coding_attempts.result_digest
    patch_json    MEDIUMTEXT  NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (attempt_id),
    CONSTRAINT fk_coding_results_attempt FOREIGN KEY (attempt_id) REFERENCES coding_attempts (attempt_id)
);
