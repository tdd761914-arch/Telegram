#!/usr/bin/env bash
# OpenFluxAndroid-compatible gomobile bridge, pinned by the openflux submodule.
# Based on p1neappleXpress/OpenFluxAndroid/scripts/build-android-core.sh (GPL-3.0+).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
core="$root/TMessagesProj/jni/third_party/openflux/mobile"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 ${JAVA_TOOL_OPTIONS:-}"
mobile_version=v0.0.0-20260908204917-8b95e45f8d3e
go install "golang.org/x/mobile/cmd/gomobile@$mobile_version"
go install "golang.org/x/mobile/cmd/gobind@$mobile_version"
export PATH="$(go env GOPATH)/bin:$PATH"
mkdir -p "$root/TMessagesProj/libs"
cd "$core"
gomobile bind -target=android -androidapi=21 -javapkg=io.openflux.bridge \
  -ldflags='-checklinkname=0 -s -w' -o "$root/TMessagesProj/libs/openflux.aar" .
