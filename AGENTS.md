# Coding Agent Guide

HyperTweak keeps agent operating rules in this file and project facts in
[`CLAUDE.md`](CLAUDE.md). Read both before changing code. When they differ from
the repository, trust the repository and update the reference documentation if
needed.

## Workflow and Boundaries

1. Inspect the working tree and the relevant implementation, tests, and build
   files before editing.
2. Make the smallest change that satisfies the request. Reuse existing
   architecture, components, and helpers; avoid unrelated refactors.
3. Preserve user changes already present in the worktree. Do not use
   destructive Git commands such as `reset --hard` or `checkout --`.
4. Confirm before deleting material, publishing externally, changing access or
   permissions, or expanding the task beyond the repository.
5. Validate in proportion to risk, then report changed files, checks run, and
   any remaining limitations.

## Android and Hook Safety

- Isolate exceptions in cross-process hooks, reflection, and system-host
  callbacks so a failure cannot crash the hooked process.
- Prefer safe casts and null-aware handling at platform boundaries.
- Cache reflection fields, methods, and class resolutions on hot paths; do not
  repeat lookups during animations or frequent callbacks.
- Access runtime settings through `Preferences`, including cross-process state,
  rather than bypassing the project preference abstraction.

## Compose and UI

Keep the existing Compose and Miuix visual language. Verify narrow screens,
large font scales, scrolling, loading and empty states, and state retention
when changing UI or navigation.

## Comments and Verification

Add short comments only for complex or non-obvious logic. Use the narrowest
useful checks first (compile or focused tests), then add unit tests, lint, and
Debug/Release builds when the change warrants them. Use the commands and
constraints in [`CLAUDE.md`](CLAUDE.md) as the project reference.

- Do not run `screencap` or otherwise capture, pull, store, or inspect
  screenshots during development or verification unless the user explicitly
  requests it. Leave visual inspection to the user and rely on logs, state
  queries, and functional checks instead.

## Git

Use Conventional Commits: `feat:`, `fix:`, `refactor:`, `perf:`, `docs:`,
`build:`, `ci:`, or `chore:`. Before committing, inspect the staged file list
and confirm it contains only the intended changes.
