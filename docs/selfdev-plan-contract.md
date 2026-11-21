# Self-development autopilot: plan JSON contract

The autopilot Planner turns one goal into a short ordered list of coding tasks. Its answer is a single JSON object. This page describes the shape of that object and the rules that check it. The checks are in `PlanValidator` (`kivra-app/src/main/kotlin/net/kigawa/kivra/selfdev/PlanValidator.kt`). The prompt that asks the model for this shape is in `PlannerPrompt` (`kivra-app/src/main/kotlin/net/kigawa/kivra/selfdev/PlannerPrompt.kt`). The design is in [ADR 0011](adr/0011-selfdev-autopilot.md).

The model's text is data. It is parsed strictly and checked. Only a plan that passes every check becomes tasks. Nothing in the text is executed.

## Top-level keys

The object has exactly these keys:

| Key       | Type             | Rule                                                        |
|-----------|------------------|-------------------------------------------------------------|
| `summary` | string           | One line, at most 500 characters.                           |
| `tasks`   | array of objects | Between 1 and 8 tasks, in the order they should run.        |

## Per-task keys

Each element of `tasks` has exactly these keys:

| Key            | Type               | Rule                                                                                             |
|----------------|--------------------|--------------------------------------------------------------------------------------------------|
| `title`        | string             | One line, 1 to 200 characters.                                                                   |
| `body`         | string             | Instructions: the goal, the files to change, and the acceptance criteria. 1 to 8,000 characters. |
| `targetPaths`  | array of strings   | Between 1 and 10 paths. Each path follows the path rules below.                                  |
| `sizeEstimate` | string             | One of `"S"`, `"M"`, `"L"`.                                                                      |
| `dependsOn`    | array of integers  | 1-based task numbers. Each one must follow the dependency rule below.                            |

Unknown keys, wrong types, and missing fields are refused. A number written as a string (for example `"2"`) is refused too. The model does not send `requiresApproval`. The validator computes it from the paths and the approval setting.

## Rules

### Every task changes at least one file

A task must change at least one file. A task that only reads, checks, investigates, or reports is not allowed. The Planner prompt asks for concrete changes and named files. The `targetPaths` list must not be empty.

### `dependsOn` refers only to earlier tasks

Each number in `dependsOn` must be smaller than the task's own number and at least 1. A task cannot depend on itself or on a later task. A number must not appear twice in one list. Because every dependency points backward, the rule also rules out cycles.

### Target paths

- A path is repo-relative. It has no leading slash and no `..` segment.
- A path must match the allowlist: `kivra-*/src/**`, `docs/**`, or `README.md`.
- A path that matches the denylist is allowed in the plan, but the task then waits for operator approval. The denylist covers CI workflows, build files, container definitions, database migrations, auth code, and `.kivra/**`. The full list is in `PatchPolicyDefaults` (`kivra-core/src/main/kotlin/net/kigawa/kivra/core/patch/PatchPolicy.kt`).
- A path that matches neither list is refused.
- An existing file larger than the size limit is refused. The limit is `PatchPolicyDefaults.MAX_FILE_BYTES`.

## Automatic approval

The validator sets the initial state of each task from the approval setting. The setting is `AutopilotApproval`:

- `required`: every task waits for approval.
- `auto`: a task is queued only if it is size `S`, has at most 3 paths, and every path is in the narrow stage-2 scope (`docs/**`, `kivra-*/src/test/**`, or `README.md`), with no denylisted path.
- `full`: a task is queued if it has at most 3 paths and no denylisted path.

Any other task waits for approval. The size estimate and path count are the model's own claims. The real diff is checked again later.

## Retries

An invalid answer is sent back to the Planner once (two attempts in total), unless the answer was cut off by the output length limit. For a refused path or an oversized file, the retry note names the rejected path and the rule. It does not repeat the goal's text or the bad answer.
