#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ROOTFS_DIR="$PROJECT_DIR/rootfs"
SEEN_FILE="$(mktemp "${TMPDIR:-/tmp}/jcontainer-rootfs-seen.XXXXXX")"
STAGING_DIR=""

cleanup() {
    rm -f "$SEEN_FILE"
    if [ -n "$STAGING_DIR" ] && [ -d "$STAGING_DIR" ]; then
        rm -rf "$STAGING_DIR"
    fi
}
trap cleanup EXIT

if [ -d "$ROOTFS_DIR" ] && [ "$(ls -A "$ROOTFS_DIR" 2>/dev/null)" ]; then
    missing_commands=""
    for cmd in /bin/sh /bin/ls /bin/echo; do
        if [ ! -x "$ROOTFS_DIR$cmd" ]; then
            missing_commands="$missing_commands $cmd"
        fi
    done

    if [ -n "$missing_commands" ]; then
        echo "rootfs/ is incomplete; missing executable(s):$missing_commands" >&2
        echo "Remove rootfs/ before attempting to recreate it." >&2
        exit 1
    fi

    echo "rootfs/ already contains the required macOS test commands."
    exit 0
fi

if ! command -v otool >/dev/null 2>&1; then
    echo "otool is required to build a macOS-compatible rootfs."
    exit 1
fi

STAGING_DIR="$(mktemp -d "$PROJECT_DIR/.rootfs-macos.XXXXXX")"
mkdir -p \
    "$STAGING_DIR/bin" \
    "$STAGING_DIR/dev" \
    "$STAGING_DIR/etc" \
    "$STAGING_DIR/private/tmp" \
    "$STAGING_DIR/usr/lib" \
    "$STAGING_DIR/var"

ln -s private/tmp "$STAGING_DIR/tmp"

is_seen() {
    grep -Fxq "$1" "$SEEN_FILE" 2>/dev/null
}

mark_seen() {
    printf '%s\n' "$1" >>"$SEEN_FILE"
}

real_path() {
    python3 - "$1" <<'PY'
import os
import sys

print(os.path.realpath(sys.argv[1]))
PY
}

copy_node() {
    local src="$1"
    local dest="$STAGING_DIR$src"

    mkdir -p "$(dirname "$dest")"

    if [ -L "$src" ]; then
        cp -P "$src" "$dest"
        return
    fi

    cp -p "$src" "$dest"
}

copy_binary_tree() {
    local src="$1"
    local resolved="$src"
    local loader=""
    local dep=""

    if [ ! -e "$src" ] && [ ! -L "$src" ]; then
        if command -v dyld_info >/dev/null 2>&1 \
                && dyld_info -dependents "$src" >/dev/null 2>&1; then
            echo "Required dependency is available only in the macOS dyld shared cache: $src" >&2
            echo "A lightweight executable chroot cannot be assembled from host files on this macOS version." >&2
            echo "Run payload integration tests on Linux; macOS integration covers degraded-mode behavior only." >&2
            exit 1
        fi
        echo "Required path does not exist: $src" >&2
        exit 1
    fi

    if is_seen "$src"; then
        return
    fi
    mark_seen "$src"

    copy_node "$src"

    if [ -L "$src" ]; then
        resolved="$(real_path "$src")"
        if [ "$resolved" != "$src" ]; then
            copy_binary_tree "$resolved"
        fi
    fi

    if ! file "$resolved" | grep -q "Mach-O"; then
        return
    fi

    loader="$(otool -l "$resolved" | awk '
        $1 == "cmd" && $2 == "LC_LOAD_DYLINKER" { seen = 1; next }
        seen && $1 == "name" { print $2; exit }
    ')"
    if [ -n "$loader" ]; then
        copy_binary_tree "$loader"
    fi

    while IFS= read -r dep; do
        [ -n "$dep" ] || continue
        copy_binary_tree "$dep"
    done < <(otool -L "$resolved" | awk 'NR > 1 { print $1 }' | grep '^/' || true)
}

echo "Creating a macOS-compatible chroot rootfs..."

for cmd in /bin/sh /bin/ls /bin/echo; do
    copy_binary_tree "$cmd"
done

if [ -d "$ROOTFS_DIR" ]; then
    if ! rmdir "$ROOTFS_DIR" 2>/dev/null; then
        echo "rootfs/ appeared during setup and is not empty; refusing to replace it." >&2
        exit 1
    fi
fi
mv "$STAGING_DIR" "$ROOTFS_DIR"
STAGING_DIR=""

echo "Done. macOS rootfs is ready at: $ROOTFS_DIR"
echo "Included commands: /bin/sh, /bin/ls, /bin/echo"
echo "This rootfs is for macOS chroot testing only; it is not a Linux OCI rootfs."
