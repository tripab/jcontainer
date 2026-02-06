#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ROOTFS_DIR="$PROJECT_DIR/rootfs"
ALPINE_VERSION="3.21.2"

if [ -d "$ROOTFS_DIR" ] && [ "$(ls -A "$ROOTFS_DIR" 2>/dev/null)" ]; then
    echo "rootfs/ already exists and is non-empty. Remove it first to re-create."
    exit 0
fi

ARCH="$(uname -m)"
case "$ARCH" in
    x86_64)  ALPINE_ARCH="x86_64" ;;
    aarch64) ALPINE_ARCH="aarch64" ;;
    *)       echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac

TARBALL="alpine-minirootfs-${ALPINE_VERSION}-${ALPINE_ARCH}.tar.gz"
URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION%.*}/releases/${ALPINE_ARCH}/${TARBALL}"

echo "Downloading Alpine miniroot ${ALPINE_VERSION} (${ALPINE_ARCH})..."
mkdir -p "$ROOTFS_DIR"

if command -v curl &>/dev/null; then
    curl -fSL "$URL" -o "/tmp/${TARBALL}"
elif command -v wget &>/dev/null; then
    wget -q "$URL" -O "/tmp/${TARBALL}"
else
    echo "Neither curl nor wget found. Please install one."
    exit 1
fi

echo "Extracting to rootfs/..."
tar -xzf "/tmp/${TARBALL}" -C "$ROOTFS_DIR"
rm -f "/tmp/${TARBALL}"

echo "Done. Alpine miniroot is ready at: $ROOTFS_DIR"
