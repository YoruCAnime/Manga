#!/usr/bin/env bash
# Resolve the version this run should build.
#
# One version has to appear in three places at once: the Git tag, the APK's
# BuildConfig.VERSION_NAME and the GitHub release the in-app updater reads. This
# script is the only place that decides it, so they cannot disagree.
#
# Rules:
#   * pull request  -> the committed baseline, never published
#   * tag vX.Y.Z    -> exactly X.Y.Z
#   * main / manual -> the committed baseline if it is ahead of the newest
#                      stable release, otherwise one patch above that release
#
# versionCode is derived from the version (major * 1,000,000 + minor * 1,000 +
# patch). The published v1.5.1 APK has versionCode 7, so the first CI release
# jumps forward; every later semantic version is still ordered above it. This is
# deliberately not github.run_number: the run counter starts at 1 for this
# repository and would be a downgrade from the installed code 7, which Android
# rejects with INSTALL_FAILED_VERSION_DOWNGRADE.
set -euo pipefail

baseline_name=$(sed -n 's/^mangacracker\.versionName=//p' gradle.properties | tr -d ' \r')
baseline_code=$(sed -n 's/^mangacracker\.versionCode=//p' gradle.properties | tr -d ' \r')
baseline_name=${baseline_name:-1.5.1}
baseline_code=${baseline_code:-7}

# Newest non-prerelease tag. `releases/latest` in the app ignores pre-releases
# too, and a pre-release must never make the next stable version jump over a
# number that was never published.
latest=""
if command -v gh >/dev/null 2>&1; then
  latest=$(gh release list --repo "${GITHUB_REPOSITORY}" --limit 100 \
    --json tagName,isPrerelease \
    --jq '.[] | select(.isPrerelease | not) | .tagName' 2>/dev/null \
    | sed 's/^v//' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+' | sort -V | tail -1 || true)
fi

event="${GITHUB_EVENT_NAME:-}"
ref_type="${GITHUB_REF_TYPE:-}"
ref_name="${GITHUB_REF_NAME:-}"

version=""
release="false"
prerelease="false"

if [ "$event" = "pull_request" ]; then
  version="$baseline_name"
elif [ "$ref_type" = "tag" ]; then
  version="${ref_name#v}"
  release="true"
  case "$version" in
    *-*) prerelease="true" ;;
  esac
elif [ "$event" = "workflow_dispatch" ] && [ -n "${INPUT_VERSION:-}" ]; then
  version="${INPUT_VERSION#v}"
  release="true"
  if [ "${INPUT_PRERELEASE:-false}" = "true" ]; then
    prerelease="true"
  fi
elif [ "$event" = "workflow_dispatch" ] || [ "$event" = "push" ]; then
  release="true"
  if [ "${INPUT_PRERELEASE:-false}" = "true" ]; then
    prerelease="true"
  fi

  if [ -n "$latest" ] &&
     [ "$(printf '%s\n%s\n' "$latest" "$baseline_name" | sort -V | tail -1)" = "$baseline_name" ] &&
     [ "$baseline_name" != "$latest" ]; then
    # A human already bumped the baseline to something unpublished; publish it
    # verbatim instead of adding another patch on top.
    version="$baseline_name"
  elif [ -n "$latest" ]; then
    core="${latest%%-*}"
    IFS=. read -r major minor patch <<< "$core"
    version="${major}.${minor}.$((10#$patch + 1))"
  else
    version="$baseline_name"
  fi
fi

# A manual run from anything other than main or a tag is a review build, not a
# release: publishing a branch as a stable version would make the in-app updater
# offer unreviewed code, and a pre-release versionCode would collide with the
# stable release of the same number.
if [ "$event" = "workflow_dispatch" ] && [ "$ref_type" != "tag" ] && [ "${GITHUB_REF:-}" != "refs/heads/main" ]; then
  release="false"
  prerelease="false"
  echo "::notice::Building from ${GITHUB_REF_NAME}; the APK is an artifact, not a release. Dispatch from main to publish."
fi

if ! printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$'; then
  echo "::error::Invalid version '$version' (expected X.Y.Z)."
  exit 1
fi

# A tag may only move forward, except when re-running the same tag to replace a
# failed asset. Equal is allowed; lower is not.
if [ -n "$latest" ] && [ "$release" = "true" ]; then
  if [ "$(printf '%s\n%s\n' "$latest" "$version" | sort -V | tail -1)" != "$version" ]; then
    echo "::error::Version $version is older than the newest stable release v$latest."
    exit 1
  fi
fi

core="${version%%-*}"
IFS=. read -r major minor patch <<< "$core"
if [ -z "${major:-}" ] || [ -z "${minor:-}" ] || [ -z "${patch:-}" ]; then
  echo "::error::Could not split '$version' into major.minor.patch."
  exit 1
fi
if [ "$((10#$minor))" -gt 999 ] || [ "$((10#$patch))" -gt 999 ]; then
  echo "::error::Minor and patch must be <= 999 so the encoded versionCode stays ordered."
  exit 1
fi
code=$((10#$major * 1000000 + 10#$minor * 1000 + 10#$patch))

# Re-running the current release is allowed (equal); going backwards is not.
if [ "$release" = "true" ] && [ "$code" -lt "$baseline_code" ]; then
  echo "::error::Computed versionCode $code is below the committed baseline $baseline_code."
  exit 1
fi

tag="v$version"
previous_tag=""
if [ -n "$latest" ] && [ "$latest" != "$version" ]; then
  previous_tag="v$latest"
fi

{
  echo "version=$version"
  echo "version_code=$code"
  echo "tag=$tag"
  echo "release=$release"
  echo "prerelease=$prerelease"
  echo "previous_tag=$previous_tag"
} >> "$GITHUB_OUTPUT"

{
  echo "VERSION=$version"
  echo "VERSION_CODE=$code"
  echo "TAG=$tag"
  echo "RELEASE=$release"
  echo "PRERELEASE=$prerelease"
  echo "PREVIOUS_TAG=$previous_tag"
} >> "$GITHUB_ENV"

echo "MangaCracker $version ($code) -> $tag (release=$release, prerelease=$prerelease, previous=${previous_tag:-none})"
