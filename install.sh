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

verify_checksum() {
  archive=$1
  asset=$2
  checksum_url=$3
  checksum_file=$4

  if ! curl -fsSL -o "$checksum_file" "$checksum_url" 2>/dev/null; then
    echo "warning: checksums.txt unavailable; skipping verification" >&2
    return 0
  fi

  expected="$(awk -v asset="$asset" '$2 == asset {print $1; exit}' "$checksum_file")"
  if [ -z "$expected" ]; then
    echo "warning: no checksum entry for $asset; skipping verification" >&2
    return 0
  fi

  if ! actual="$(sha256 "$archive")"; then
    echo "warning: sha256sum/shasum not found; skipping checksum verification" >&2
    return 0
  fi

  if [ "$actual" != "$expected" ]; then
    echo "error: checksum mismatch for $asset" >&2
    echo "expected: $expected" >&2
    echo "actual:   $actual" >&2
    exit 1
  fi

  echo "Checksum verified."
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

# Optional runtime tools: denote works without them, but they unlock
# interactive selection (fzf), faster search (rg, fd), and PDF ingest
# (pdftotext). See "Runtime tools" in the README.
tool_hint() {
  case "$1" in
    fzf) echo "interactive selection for 'denote find' and 'denote grep'" ;;
    rg) echo "faster 'denote grep' (ripgrep)" ;;
    fd) echo "faster file listing" ;;
    pdftotext) echo "PDF ingest for 'denote llm-wiki' (poppler)" ;;
  esac
}

tool_present() {
  case "$1" in
    # Debian/Ubuntu package fd-find installs the binary as fdfind
    fd) command -v fd >/dev/null 2>&1 || command -v fdfind >/dev/null 2>&1 ;;
    *) command -v "$1" >/dev/null 2>&1 ;;
  esac
}

detect_pkg_manager() {
  if command -v brew >/dev/null 2>&1; then
    echo brew
  elif command -v apt-get >/dev/null 2>&1; then
    echo apt-get
  elif command -v dnf >/dev/null 2>&1; then
    echo dnf
  elif command -v pacman >/dev/null 2>&1; then
    echo pacman
  fi
}

pkg_name() {
  tool=$1
  manager=$2
  case "$tool" in
    fzf) echo fzf ;;
    rg) echo ripgrep ;;
    fd)
      case "$manager" in
        apt-get|dnf) echo fd-find ;;
        *) echo fd ;;
      esac
      ;;
    pdftotext)
      case "$manager" in
        apt-get|dnf) echo poppler-utils ;;
        *) echo poppler ;;
      esac
      ;;
  esac
}

install_cmd() {
  manager=$1
  pkg=$2
  sudo_prefix="sudo "
  if [ "$(id -u)" = "0" ]; then
    sudo_prefix=""
  fi
  case "$manager" in
    brew) echo "brew install $pkg" ;;
    apt-get) echo "${sudo_prefix}apt-get install -y $pkg" ;;
    dnf) echo "${sudo_prefix}dnf install -y $pkg" ;;
    pacman) echo "${sudo_prefix}pacman -S --noconfirm $pkg" ;;
  esac
}

offer_optional_tools() {
  missing=""
  for tool in fzf rg fd pdftotext; do
    tool_present "$tool" || missing="$missing $tool"
  done
  if [ -z "$missing" ]; then
    return 0
  fi

  echo ""
  echo "Optional tools that improve denote are missing:"
  for tool in $missing; do
    echo "  $tool - $(tool_hint "$tool")"
  done

  manager="$(detect_pkg_manager)"
  if [ -z "$manager" ]; then
    echo "No supported package manager found (brew/apt-get/dnf/pacman);" \
      "install them manually if you want them."
    return 0
  fi

  # stdin is the script itself under `curl | sh`, so prompt via /dev/tty.
  if [ -n "${NONINTERACTIVE:-}" ] || ! (exec </dev/tty) 2>/dev/null; then
    echo "To install them:"
    for tool in $missing; do
      echo "  $(install_cmd "$manager" "$(pkg_name "$tool" "$manager")")"
    done
    return 0
  fi

  for tool in $missing; do
    cmd="$(install_cmd "$manager" "$(pkg_name "$tool" "$manager")")"
    printf 'Run %s? (y/n) ' "$cmd"
    read -r answer </dev/tty || answer=n
    case "$answer" in
      y|Y|yes|YES) $cmd || echo "warning: failed to install $tool; continuing" >&2 ;;
      *) echo "Skipped $tool." ;;
    esac
  done
}

need curl
need tar
need awk
need mktemp

os="${OS:-$(detect_os)}"
arch="${ARCH:-$(detect_arch)}"

if [ "$VERSION" = "latest" ]; then
  tag="$(api_curl "https://api.github.com/repos/$REPO/releases/latest" \
    | awk -F '"' '/"tag_name"[[:space:]]*:/ {print $4; exit}')"
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
curl -fsSL -o "$archive" "$base_url/$asset"

checksum_file="$tmp/checksums.txt"
verify_checksum "$archive" "$asset" "$base_url/checksums.txt" "$checksum_file"

tar -xzf "$archive" -C "$tmp"
bin_path="$tmp/${asset%.tar.gz}/denote"
if [ ! -f "$bin_path" ]; then
  echo "error: archive did not contain $bin_path" >&2
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

offer_optional_tools
