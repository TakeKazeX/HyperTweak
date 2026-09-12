# Release notes (curated, bilingual)

One file per release tag. `release.yml` looks up `.github/release-notes/<tag>.md`
using the tag it is releasing, so the file name must match the git tag exactly —
`v1.8.0.md` for tag `v1.8.0`, `v1.7.1.md` for tag `v1.7.1`. A file for one tag is
never reused by another tag, and a file that does not exist for the tag being
released is not an error (see *Failure behaviour* below).

This directory is published with the repository, which is why it lives here and not
under `docs/`: `docs/` is excluded by `.gitignore`, so anything placed there is
invisible to CI.

## Why these exist

The CI channel (`ci-latest`) shows the English commit list straight from the GitHub
compare API, so it needs no notes at all. Stable releases are read by end users, so
they are described by hand instead of dumping 100+ internal commits on them
(decision D19). The notes are written once per release, in both languages, in this
single file — one file avoids the two language versions drifting apart.

## File format

The file is split into two language sections by HTML-comment markers. Each marker
must sit alone on its own line, spelled exactly like this (case-sensitive, single
space after `<!--` and before `-->`):

```markdown
<!-- lang:zh -->
### ✨ 新增
- **锁屏充电详情**：实时显示功率/电压/电流/温度
### 🔧 修复
- 图标调节器在 release（R8）构建下静默失效

<!-- lang:en -->
### ✨ Features
- **Lockscreen charging details**: live power / voltage / current / temperature
### 🐛 Fixes
- The status bar icon tuner silently did nothing in release (R8) builds
```

Rules:

* Everything after `<!-- lang:zh -->` and before the next `<!-- lang:* -->` marker is
  the Chinese section. Everything after `<!-- lang:en -->` runs to the next marker or
  to end of file, so the English section is normally last.
* Group entries under `###` headings using the same style as the release changelog
  (`### ✨ 新增` / `### ✨ Features`, `### 🔧 修复` / `### 🐛 Fixes`, `### ⚙️ 工程` /
  `### ⚙️ Build & Chore`). One bullet per user-visible change, `- ` at the start of
  the line.
* Write for users, not for maintainers: lead with the feature and what it does. Drop
  refactors, dependency bumps, CI tweaks and other internal noise unless they change
  behaviour users can observe.
* Blank lines between groups are fine; leading and trailing blank lines in a section
  are trimmed when the notes are extracted.
* Both sections should cover the same changes so neither language looks stale.

See [`v1.8.0.md`](v1.8.0.md) for a complete worked example.

## How it is consumed

`release.yml` reads `.github/release-notes/${CURRENT_TAG}.md` while generating
`build-info.json` and:

1. writes the two sections into `build-info.json` as
   `notes: { "zh": "...", "en": "..." }` — this field exists **only** for the stable
   channel, never for CI builds; and
2. uses them as the GitHub Release body, Chinese and English on the same page
   separated by a horizontal rule.

The Android client picks the section matching the device language and falls back to
the other one when only a single section is present, so the update log is never
empty.

## Failure behaviour

The release is never blocked by this directory:

* file missing for the tag → warning only, `notes` is omitted, the release body stays
  the auto-generated changelog, and the client falls back to a commit list;
* file present but no `<!-- lang:* -->` markers → warning only, same fallback;
* only one language present → warning, the missing key is omitted from the JSON and
  the client falls back to the language that exists.

## Relationship with `docs/CHANGELOG.md`

| File | Audience |
| --- | --- |
| `docs/CHANGELOG.md` (local only, git-ignored) | Cumulative human history, including the rolling "unreleased" section |
| `.github/release-notes/<tag>.md` (published) | The bilingual fragment for one release tag |

Update both in the release pull request — the Chinese section can usually be reused
verbatim from the changelog. If a tag has been published without a notes file, CI only
warns; add the file for the next release rather than retro-fitting history.
