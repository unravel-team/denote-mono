# Installation

Every way to get `denote` onto your machine — install script, manual
release download, building from source — plus the optional runtime tools
and shell completions.

## Install script (macOS/Linux)

```sh
curl -fsSL https://raw.githubusercontent.com/unravel-team/denote-mono/main/install.sh | sh
```

The script downloads the latest release asset matching your OS and CPU,
verifies it against `checksums.txt` when available, and installs
`denote` to `~/.local/bin`. Release automation publishes archives named
`denote-mono-vX.Y.Z-{linux-{arm64,x64},macos-arm64}.tar.gz`. There is no
macos-x64 archive — GraalVM stopped shipping macOS Intel builds after
JDK 21 — so Intel Macs build from source instead.

Override the version or destination like this:

```sh
curl -fsSL https://raw.githubusercontent.com/unravel-team/denote-mono/main/install.sh \
  | VERSION=vX.Y.Z BINDIR=/usr/local/bin sh
```

## Homebrew (macOS/Linux) — future

A Homebrew tap is planned but not set up yet — use the install script
above instead. Once the tap exists, installation will be:

```sh
brew install unravel-team/tap/denote-mono
```

The tap formula will point at the same GitHub Release archives and
checksums as the install script.

## Manual GitHub Releases download

Download a native archive from
[GitHub Releases](https://github.com/unravel-team/denote-mono/releases/latest),
then install the binary inside it:

```sh
asset=denote-mono-vX.Y.Z-macos-arm64.tar.gz
tar -xzf "$asset"
install -m 755 "${asset%.tar.gz}/denote" ~/.local/bin/denote
denote --version
```

Release assets include `checksums.txt` for SHA-256 verification. Filter it
to the one archive you downloaded:

```sh
awk -v asset="$asset" '$2 == asset {print}' checksums.txt | shasum -a 256 -c -   # macOS
awk -v asset="$asset" '$2 == asset {print}' checksums.txt | sha256sum -c -       # Linux
```

macOS release binaries are not signed or notarized yet, so Gatekeeper may
show a warning until Apple Developer ID signing is added.

## Build from source

Requires Java 11+, the
[Clojure CLI](https://clojure.org/guides/install_clojure), and GNU make:

```sh
git clone https://github.com/unravel-team/denote-mono.git
cd denote-mono
make test
```

## Build a native binary from source

A self-contained ~20 MB executable with ~10 ms startup. You need GraalVM
with `native-image`:

```sh
brew install --cask graalvm-jdk    # or unpack a GraalVM under ~/.local/share/graalvm

make native
install -m 755 projects/denote-cli/target/denote ~/.local/bin/denote
denote --version
```

`make native` finds `native-image` on your `PATH` or under
`~/.local/share/graalvm`; override with `make native NATIVE_IMAGE=...`.

## Uberjar (alternative)

```sh
make build
alias denote='java -jar /path/to/denote-mono/projects/denote-cli/target/denote-cli-vX.Y.Z-standalone.jar'
```

## Runtime tools

Some features use external tools when available; PDF ingest requires one:

- `fzf` — **strongly recommended** (`brew install fzf`). Makes `find` and
  `grep` interactive on a terminal: fzf narrows the results, **Enter
  opens the selection in your editor, Ctrl-P prints it** for piping.
- `rg` (ripgrep) — accelerates `denote grep`.
- `fd` — accelerates the note listing behind `denote find`, `links`,
  and `backlinks`.
- `pdftotext` (Poppler) — required for PDF sources in `denote llm-wiki`
  ingest.
- `$VISUAL` / `$EDITOR` — the editor used to open notes (falls back to
  `vi`).

The install script checks for these at the end of a run and, when a
package manager is available (`brew`, `apt-get`, `dnf`, or `pacman`),
offers to install the missing ones (`Run brew install fzf? (y/n)`). Set
`NONINTERACTIVE=1` to skip the prompts; it then just prints the install
commands.

## Shell completions

Generated from the same command tables the CLI parses with, so they
never drift:

```sh
source <(denote completions bash)                          # bash, in ~/.bashrc
denote completions zsh > ~/.zsh/completions/_denote        # zsh, on your $fpath
denote completions fish > ~/.config/fish/completions/denote.fish
```

Next: [configure](configuration.md) your notes directory with
`denote init`, or take the [quickstart](quickstart.md).
