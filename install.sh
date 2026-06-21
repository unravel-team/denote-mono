#!/usr/bin/env sh
set -eu

REPO="${REPO:-unravel-team/denote-mono}"
VERSION="${VERSION:-${1:-latest}}"
PREFIX="${PREFIX:-$HOME/.local}"
BINDIR="${BINDIR:-$PREFIX/bin}"
BIN_NAME="${BIN_NAME:-denote}"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "error: $1 is required" >&2
    exit 1
  fi
}

api_curl() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" "$@"
  else
    curl -fsSL "$@"
  fi
}

download_curl() {
  curl -fsSL "$@"
}

detect_os() {
  case "$(uname -s)" in
    Darwin) echo macos ;;
    Linux) echo linux ;;
    *)
      echo "error: unsupported OS: $(uname -s)" >&2
      exit 1
      ;;
  esac
}

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64) echo x64 ;;
    arm64|aarch64) echo arm64 ;;
    *)
      echo "error: unsupported architecture: $(uname -m)" >&2
      exit 1
      ;;
  esac
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    return 1
  fi
}

need curl
need tar
need awk
need sed
need head
need mktemp
need find

os="${OS:-$(detect_os)}"
arch="${ARCH:-$(detect_arch)}"

if [ "$VERSION" = "latest" ]; then
  tag="$(api_curl "https://api.github.com/repos/$REPO/releases/latest" \
    | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    | head -n 1)"
  if [ -z "$tag" ]; then
    echo "error: could not resolve latest release for $REPO" >&2
    exit 1
  fi
else
  tag="$VERSION"
  case "$tag" in
    v*) ;;
    *) tag="v$tag" ;;
  esac
fi

asset="denote-mono-${tag}-${os}-${arch}.tar.gz"
base_url="https://github.com/${REPO}/releases/download/${tag}"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/denote-mono.XXXXXX")"
cleanup() {
  rm -rf "$tmp"
}
trap cleanup EXIT INT TERM

archive="$tmp/$asset"
echo "Downloading $asset from $REPO..."
download_curl -o "$archive" "$base_url/$asset"

checksum_file="$tmp/checksums.txt"
if download_curl -o "$checksum_file" "$base_url/checksums.txt" 2>/dev/null; then
  expected="$(awk -v asset="$asset" '$2 == asset {print $1; exit}' "$checksum_file")"
  if [ -n "$expected" ]; then
    if actual="$(sha256 "$archive")"; then
      if [ "$actual" != "$expected" ]; then
        echo "error: checksum mismatch for $asset" >&2
        echo "expected: $expected" >&2
        echo "actual:   $actual" >&2
        exit 1
      fi
      echo "Checksum verified."
    else
      echo "warning: sha256sum/shasum not found; skipping checksum verification" >&2
    fi
  else
    echo "warning: no checksum entry for $asset; skipping verification" >&2
  fi
else
  echo "warning: checksums.txt unavailable; skipping verification" >&2
fi

tar -xzf "$archive" -C "$tmp"
bin_path="$(find "$tmp" -type f -name denote | head -n 1)"
if [ -z "$bin_path" ]; then
  echo "error: archive did not contain denote binary" >&2
  exit 1
fi

mkdir -p "$BINDIR"
if command -v install >/dev/null 2>&1; then
  install -m 755 "$bin_path" "$BINDIR/$BIN_NAME"
else
  cp "$bin_path" "$BINDIR/$BIN_NAME"
  chmod 755 "$BINDIR/$BIN_NAME"
fi

echo "Installed $BINDIR/$BIN_NAME"
case ":$PATH:" in
  *":$BINDIR:"*) ;;
  *) echo "Add $BINDIR to PATH to run $BIN_NAME from any directory." ;;
esac
"$BINDIR/$BIN_NAME" --version || true
