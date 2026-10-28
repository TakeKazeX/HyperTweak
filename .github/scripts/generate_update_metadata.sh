#!/usr/bin/env bash
# APK-derived metadata: API body for new clients, identical legacy attachment during migration.
set -euo pipefail

# The client parses these exact field names, so the JSON shape is a contract.
# versionCode / versionName / minSdk / targetSdk are read back from the built APK
# with aapt2 instead of being recomputed from git: the APK is the only
# authoritative source (a shallow clone, a VERSION_CODE override or a checkout of
# a different commit would silently desync the client's update check).
CHANNEL="${CHANNEL:?CHANNEL must be set to ci or stable}"
if [ "$CHANNEL" = "stable" ]; then
  NOTES_FILE="${NOTES_FILE:-.github/release-notes/${CURRENT_TAG}.md}"
else
  NOTES_FILE=""
fi

extract_lang() {
  # Everything after `<!-- lang:X -->` up to the next `<!-- lang:* -->` marker.
  awk -v lang="$2" '
    $0 ~ ("^<!-- lang:" lang " -->[[:space:]]*$") { inside = 1; next }
    $0 ~ "^<!-- lang:" { inside = 0 }
    inside { print }
  ' "$1"
}

trim_blanks() {
  # Strip leading/trailing blank lines only; interior formatting is preserved.
  sed -e '/./,$!d' | awk 'NF { last = NR } { line[NR] = $0 } END { for (i = 1; i <= last; i++) print line[i] }'
}

shopt -s nullglob
APKS=(app/build/outputs/apk/release/*.apk)
if [ "${#APKS[@]}" -ne 1 ]; then
  echo "ERROR: expected exactly one release APK, found ${#APKS[@]}"
  exit 1
fi
APK="${APKS[0]}"
APK_ASSET="$(basename "$APK")"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
AAPT2="$(find "$SDK_ROOT/build-tools" -maxdepth 2 -type f -name aapt2 2>/dev/null | sort -V | tail -n 1 || true)"
if [ -z "$AAPT2" ]; then
  echo "ERROR: Android SDK aapt2 was not found under $SDK_ROOT/build-tools"
  exit 1
fi
BADGING="$("$AAPT2" dump badging "$APK")"

VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([0-9]*\)'.*/\1/p")"
VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")"
# build-tools 37 renamed the minimum-SDK line from `sdkVersion:` to `minSdkVersion:`,
# so accept either spelling rather than failing the publish over a rename.
MIN_SDK="$(printf '%s\n' "$BADGING" | sed -nE "s/^(minSdkVersion|sdkVersion):'([0-9]+)'.*/\2/p")"
TARGET_SDK="$(printf '%s\n' "$BADGING" | sed -nE "s/^targetSdkVersion:'([0-9]+)'.*/\1/p")"

# versionCode and versionName are load-bearing -- the client trusts them -- so an APK whose
# badging cannot be read must fail the publish instead of shipping a wrong contract.
for value in "$VERSION_CODE" "$VERSION_NAME"; do
  if [ -z "$value" ]; then
    echo "ERROR: could not read versionCode/versionName from the aapt2 badging output:"
    printf '%s\n' "$BADGING"
    exit 1
  fi
done

COMMIT="$(git rev-parse HEAD)"
SHORT_COMMIT="$(git rev-parse --short=7 HEAD)"
BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
read -r APK_SHA256 APK_SIZE < <(python3 - "$APK" <<'PYAPK'
import hashlib
import pathlib
import sys
apk = pathlib.Path(sys.argv[1])
digest = hashlib.sha256()
with apk.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
print(digest.hexdigest(), apk.stat().st_size)
PYAPK
)

# `notes` is stable-channel only (D19). A missing or malformed notes file is a
# warning, never a failure: the client falls back to the English commit list.
NOTES_ZH=""
NOTES_EN=""
if [ "$CHANNEL" = "stable" ]; then
  if [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ]; then
    NOTES_ZH="$(extract_lang "$NOTES_FILE" zh | trim_blanks)"
    NOTES_EN="$(extract_lang "$NOTES_FILE" en | trim_blanks)"
    if [ -z "$NOTES_ZH" ] && [ -z "$NOTES_EN" ]; then
      echo "WARNING: $NOTES_FILE has no '<!-- lang:zh -->' / '<!-- lang:en -->' section; omitting notes."
    elif [ -z "$NOTES_ZH" ] || [ -z "$NOTES_EN" ]; then
      echo "WARNING: $NOTES_FILE is missing one language section; the client falls back to the other."
    fi
    if [ -n "$NOTES_ZH" ] || [ -n "$NOTES_EN" ]; then
      # Curated bilingual release body: both languages on one page.
      if [ -n "$NOTES_ZH" ] && [ -n "$NOTES_EN" ]; then
        { printf '%s\n\n' "$NOTES_ZH"; printf -- '---\n\n'; printf '%s\n' "$NOTES_EN"; } > release-body.txt
      elif [ -n "$NOTES_ZH" ]; then
        printf '%s\n' "$NOTES_ZH" > release-body.txt
      else
        printf '%s\n' "$NOTES_EN" > release-body.txt
      fi
    fi
  else
    echo "WARNING: curated release notes '$NOTES_FILE' not found; the release body stays the generated changelog."
  fi
fi

# `jq --arg` keeps notes escaping correct (newlines, quotes, backslashes).
# Schema fields are consumed by UpdateReleaseMetadata and GitHubUpdateRepository.
jq -n \
  --argjson schema 1 \
  --arg channel "$CHANNEL" \
  --arg versionName "$VERSION_NAME" \
  --argjson versionCode "$VERSION_CODE" \
  --arg commit "$COMMIT" \
  --arg shortCommit "$SHORT_COMMIT" \
  --arg builtAt "$BUILT_AT" \
  --arg compareBase "${COMPARE_BASE:-}" \
  --arg apkAsset "$APK_ASSET" \
  --argjson apkSize "$APK_SIZE" \
  --arg apkSha256 "$APK_SHA256" \
  --arg minSdk "$MIN_SDK" \
  --arg targetSdk "$TARGET_SDK" \
  --arg notesZh "$NOTES_ZH" \
  --arg notesEn "$NOTES_EN" '
    {
      schema: $schema,
      channel: $channel,
      versionName: $versionName,
      versionCode: $versionCode,
      commit: $commit,
      shortCommit: $shortCommit,
      builtAt: $builtAt
    }
    + (if $compareBase == "" then {} else { compareBase: $compareBase } end)
    + {
      apkAsset: $apkAsset,
      apkSize: $apkSize,
      apkSha256: $apkSha256
    }
    # Informational only; omitted (not zero-filled) when the badging did not carry it.
    + (if $minSdk == "" then {} else { minSdk: ($minSdk | tonumber) } end)
    + (if $targetSdk == "" then {} else { targetSdk: ($targetSdk | tonumber) } end)
    + (
      if $notesZh == "" and $notesEn == "" then {}
      else
        {
          notes: (
            (if $notesZh == "" then {} else { zh: $notesZh } end)
            + (if $notesEn == "" then {} else { en: $notesEn } end)
          )
        }
      end
    )
  ' > update-metadata.json

jq -e . update-metadata.json >/dev/null
echo "Generated update-metadata.json:"
cat update-metadata.json

# Temporary dual publication until users have migrated. Never build separate metadata
# for old clients: their version, filename and integrity values must match the API body.
cp update-metadata.json build-info.json
