#!/usr/bin/env bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/tmp/android-sdk}}
version_name=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' \
  "$project_dir/android-app/app/build.gradle.kts")
if [[ -z "$version_name" ]]; then
  echo "Could not read versionName from app/build.gradle.kts" >&2
  exit 1
fi
artifact="x9u-root-flasher-v${version_name}.apk"

ANDROID_HOME="$sdk_root" \
  "$project_dir/android-app/gradlew" -p "$project_dir/android-app" :app:assembleRelease

mkdir -p "$project_dir/dist"
cp "$project_dir/android-app/app/build/outputs/apk/release/app-release.apk" \
  "$project_dir/dist/$artifact"
(
  cd "$project_dir/dist"
  sha256sum "$artifact" > SHA256SUMS
)
sha256sum "$project_dir/dist/$artifact"
