#!/usr/bin/env bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
upstream=https://github.com/woshigenm/CVE-2026-98260.git
commit=11c7c82000205db8b2a29f79ee297f124484f2bb
cache_dir=${X9U_CVE98260_CACHE:-/tmp/x9u-cve98260-upstream}
profile_patch="$project_dir/third_party/cve98260-profile.patch"

ndk_root=${ANDROID_NDK_HOME:-}
if [[ -z "$ndk_root" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  candidate="$ANDROID_SDK_ROOT/ndk/29.0.14206865"
  [[ -d "$candidate" ]] && ndk_root=$candidate
fi
if [[ -z "$ndk_root" && -d /tmp/aera-ksud-ndk/android-ndk-r29 ]]; then
  ndk_root=/tmp/aera-ksud-ndk/android-ndk-r29
fi
if [[ -z "$ndk_root" ]]; then
  echo "Android NDK r29 was not found. Set ANDROID_NDK_HOME." >&2
  exit 1
fi

if [[ ! -d "$cache_dir/.git" ]]; then
  git clone --filter=blob:none --no-checkout "$upstream" "$cache_dir"
fi
if ! git -C "$cache_dir" cat-file -e "$commit^{commit}" 2>/dev/null; then
  git -C "$cache_dir" fetch --depth=1 origin "$commit"
fi

stage_dir=$(mktemp -d /tmp/x9u-cve98260-build.XXXXXX)
cleanup() {
  rm -rf -- "$stage_dir"
}
trap cleanup EXIT

git -C "$cache_dir" archive "$commit" | tar -x -C "$stage_dir"
patch -d "$stage_dir" -p1 < "$profile_patch"
ANDROID_NDK_HOME="$ndk_root" sh "$stage_dir/app/native/build_native.sh"

install -d "$project_dir/android-app/app/src/main/assets"
install -d "$project_dir/android-app/app/src/main/jniLibs/arm64-v8a"
install -m 0755 "$stage_dir/app/prebuilt/arm64/magicroot" \
  "$project_dir/android-app/app/src/main/assets/cve98260"
install -m 0644 "$project_dir/third_party/cve98260-LICENSE.txt" \
  "$project_dir/android-app/app/src/main/assets/cve98260-LICENSE.txt"
install -m 0644 "$stage_dir/app/prebuilt/arm64/libmagicroot.so" \
  "$project_dir/android-app/app/src/main/jniLibs/arm64-v8a/libcve98260.so"

sha256sum \
  "$project_dir/android-app/app/src/main/assets/cve98260" \
  "$project_dir/android-app/app/src/main/jniLibs/arm64-v8a/libcve98260.so"
