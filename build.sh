#!/usr/bin/env bash
# Build debug/release APKs and a Google Play Android App Bundle (AAB).
#
# Usage:
#   ./build.sh debug
#   ./build.sh release
#   ./build.sh all
#   ./build.sh gen-keystore
#   ./build.sh update-newpipe [--check]
#
# Output:
#   dist//youtube-companion-debug-<version>.apk
#   dist//youtube-companion-release-<version>.apk
#
# Release / Play Store signing:
#   Create keystore.properties (see keystore.properties.example).
#
# For Google Play, the key configured here is used as the upload key.
# Google Play App Signing can then use Google's app signing key for
# the APKs ultimately delivered to users.

set -euo pipefail

cd "$(dirname "$0")"

MODE="${1:-all}"
DIST_DIR="dist"
KEYSTORE_PROPS="keystore.properties"

version_name() {
    grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts
}

version_code() {
    grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts
}

require_keystore() {
    if [[ ! -f "$KEYSTORE_PROPS" ]]; then
        echo
        echo "ERROR: $KEYSTORE_PROPS not found."
        echo
        echo "Create a release/upload keystore first:"
        echo
        echo "    ./build.sh gen-keystore"
        echo
        echo "Then create $KEYSTORE_PROPS."
        exit 1
    fi
}

gen_keystore() {
    # Default matches the project's canonical Uni keystore path. Prefer the existing
    # keystore/release.jks over generating a new one.
    local out="${2:-keystore/release.jks}"

    if [[ -f "$out" ]]; then
        echo "ERROR: Keystore already exists:"
        echo "       $out"
        echo
        echo "This project already uses keystore/release.jks for release signing."
        echo "Do not generate a second keystore unless you intentionally rotate keys."
        exit 1
    fi

    mkdir -p "$(dirname "$out")"

    echo "==> Generating release/upload keystore at:"
    echo "    $out"
    echo

    keytool -genkeypair -v \
        -keystore "$out" \
        -alias youtubeaa \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000

    echo
    echo "==> Keystore created."
    echo
    echo "Create $KEYSTORE_PROPS with:"
    echo
    echo "storeFile=$out"
    echo "storePassword=YOUR_STORE_PASSWORD"
    echo "keyAlias=youtubeaa"
    echo "keyPassword=YOUR_KEY_PASSWORD"
    echo
    echo "IMPORTANT:"
    echo "Keep the keystore and passwords safe."
    echo "For Google Play this key can be used as the upload key."
}

find_apksigner() {
    if command -v apksigner >/dev/null 2>&1; then
        command -v apksigner
        return 0
    fi

    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

    if [[ -n "$sdk" && -d "$sdk/build-tools" ]]; then
        find "$sdk/build-tools" \
            -type f \
            -name apksigner \
            2>/dev/null |
            sort -V |
            tail -n 1
        return 0
    fi

    return 1
}

verify_apk() {
    local apk="$1"
    echo "==> Verifying APK signature:"
    echo "    $apk"
    local apksigner
    apksigner="$(find_apksigner || true)"

    if [[ -z "$apksigner" ]]; then
        echo
        echo "WARNING: apksigner not found."
        echo "APK signature could not be verified automatically."
        return 0
    fi

    "$apksigner" verify \
        --verbose \
        --print-certs \
        "$apk"

    echo "==> APK signature OK"
}


build_debug() {
    echo "==> Building DEBUG APK..."
    ./gradlew clean assembleDebug
    mkdir -p "$DIST_DIR"
    local src="app/build/outputs/apk/debug/app-debug.apk"
    local dest="$DIST_DIR//youtube-companion-debug-$(version_name).apk"

    if [[ ! -f "$src" ]]; then
        echo "ERROR: Debug APK was not created:"
        echo "       $src"
        exit 1
    fi

    cp "$src" "$dest"

    echo
    echo "==> Debug APK:"
    echo "    $dest"
}

build_release() {
    require_keystore
    echo "==> Building RELEASE APK..."
    ./gradlew clean assembleRelease
    mkdir -p "$DIST_DIR"
    local src="app/build/outputs/apk/release/app-release.apk"
    local dest="$DIST_DIR//youtube-companion-release-$(version_name).apk"

    if [[ ! -f "$src" ]]; then
        echo
        echo "ERROR: Signed release APK was not created:"
        echo "       $src"
        echo
        echo "Check your signing configuration."
        exit 1
    fi

    cp "$src" "$dest"

    verify_apk "$dest"

    echo
    echo "==> Release APK:"
    echo "    $dest"
}


case "$MODE" in
    update-newpipe)
        if ! command -v python3 >/dev/null 2>&1; then
            echo "ERROR: update-newpipe requires Python 3." >&2
            exit 1
        fi
        python3 scripts/update_newpipe.py "${@:2}"
        ;;

    debug)
        build_debug
        ;;

    release)
        build_release
        ;;

    all)
        build_debug
        build_release
        ;;

    gen-keystore)
        gen_keystore "$@"
        ;;

    *)
        echo "Usage: $0 [debug|release|all|gen-keystore|update-newpipe [--check]]" >&2
        exit 1
        ;;
esac
