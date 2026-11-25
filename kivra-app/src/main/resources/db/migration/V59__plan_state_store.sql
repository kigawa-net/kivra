-- Pipeline snapshots are independent of development_runs: Reviewer uses UUID run keys.
CREATE TABLE plan_decomposition_states (
    task_id VARCHAR(255) NOT NULL PRIMARY KEY,
    state_json TEXT NOT NULL
);

CREATE TABLE plan_review_states (
    run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    state_json TEXT NOT NULL
);
