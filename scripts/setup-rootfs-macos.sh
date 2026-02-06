#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ROOTFS_DIR="$PROJECT_DIR/rootfs"

if [ -d "$ROOTFS_DIR" ] && [ "$(ls -A "$ROOTFS_DIR" 2>/dev/null)" ]; then
    echo "rootfs/ already exists and is non-empty. Remove it first to re-create."
    exit 0
fi

mkdir -p "$ROOTFS_DIR"

if command -v docker &>/dev/null; then
    echo "Using Docker to create an Alpine rootfs..."
    CONTAINER_ID="$(docker create alpine:latest /bin/true)"
    docker export "$CONTAINER_ID" | tar -xf - -C "$ROOTFS_DIR"
    docker rm "$CONTAINER_ID" >/dev/null
    echo "Done. Alpine rootfs is ready at: $ROOTFS_DIR"
else
    echo "Docker is not installed."
    echo ""
    echo "To create a rootfs on macOS, install Docker Desktop and re-run this script."
    echo "Alternatively, on a Linux machine or VM, run:"
    echo "  ./scripts/setup-rootfs.sh"
    echo "and copy the rootfs/ directory here."
    exit 1
fi
