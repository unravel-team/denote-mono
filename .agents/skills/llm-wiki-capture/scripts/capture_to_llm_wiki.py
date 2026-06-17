#!/usr/bin/env python3
"""Write stdin as a durable llm-wiki source packet and optionally ingest it."""

from __future__ import annotations

import argparse
import datetime as dt
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from typing import Iterable


DEFAULT_SOURCE_DIR = "~/.local/share/denote-mono/llm-wiki-captures"


def slugify(text: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return slug[:80] or "capture"


def expand_path(path: str) -> Path:
    return Path(os.path.expandvars(os.path.expanduser(path))).resolve()


def unique_path(directory: Path, stamp: str, slug: str) -> Path:
    candidate = directory / f"{stamp}--{slug}.md"
    if not candidate.exists():
        return candidate
    for i in range(2, 1000):
        candidate = directory / f"{stamp}--{slug}-{i}.md"
        if not candidate.exists():
            return candidate
    raise RuntimeError("could not choose a unique capture filename")


def bullet_lines(label: str, values: Iterable[str]) -> str:
    values = [v for v in values if v]
    if not values:
        return ""
    return "\n".join(f"- {label}: {v}" for v in values) + "\n"


def build_packet(args: argparse.Namespace, body: str, captured_at: str) -> str:
    topics = bullet_lines("Topic", args.topic)
    parent = f"- Preferred parent: {args.parent}\n" if args.parent else ""
    topology = args.topology.strip() if args.topology else ""
    if not topology:
        topology = (
            "- Update existing relevant pages if they exist.\n"
            "- Otherwise create focused pages at the closest suitable place in the wiki.\n"
            "- Prefer updating over duplicating topics.\n"
            "- Cross-link related pages with existing `denote:` identifiers."
        )

    return (
        f"# Conversation capture: {args.title}\n\n"
        f"- Captured: {captured_at}\n"
        f"- Origin: {args.origin}\n"
        f"- Intent: {args.intent}\n"
        f"{topics}"
        f"{parent}"
        "\n## Desired wiki topology\n\n"
        f"{topology}\n\n"
        "## Captured material\n\n"
        f"{body.rstrip()}\n"
    )


def denote_base_cmd(args: argparse.Namespace) -> list[str]:
    cmd = shlex.split(args.denote_cmd)
    if args.config:
        cmd.extend(["--config", args.config])
    if args.silo:
        cmd.extend(["--silo", args.silo])
    if args.root:
        cmd.extend(["--root", args.root])
    return cmd


def ingest_cmd(args: argparse.Namespace, source: Path) -> list[str]:
    cmd = denote_base_cmd(args) + ["llm-wiki", "ingest", str(source)]
    if args.model:
        cmd.extend(["--model", args.model])
    if args.max_rounds is not None:
        cmd.extend(["--max-rounds", str(args.max_rounds)])
    if args.fresh:
        cmd.append("--fresh")
    return cmd


def lint_cmd(args: argparse.Namespace) -> list[str]:
    return denote_base_cmd(args) + ["llm-wiki", "lint", "--fix"]


def run(cmd: list[str]) -> int:
    print("$ " + " ".join(shlex.quote(part) for part in cmd), file=sys.stderr)
    return subprocess.run(cmd).returncode


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=(
            "Create a durable source packet from stdin and ingest it with "
            "denote llm-wiki."
        )
    )
    p.add_argument("--title", required=True, help="Human title for capture source")
    p.add_argument(
        "--intent",
        default="Capture pi-coding-agent conversation material into the llm-wiki.",
        help="User intent to include in the source packet",
    )
    p.add_argument(
        "--topic",
        action="append",
        default=[],
        help="Topic/client/project label to include; repeatable",
    )
    p.add_argument(
        "--parent",
        help="Known preferred parent note title, Denote ID, or sequence",
    )
    p.add_argument(
        "--topology",
        help="Explicit desired wiki topology instructions",
    )
    p.add_argument(
        "--origin",
        default="pi-coding-agent conversation",
        help="Origin line for source packet",
    )
    p.add_argument(
        "--source-dir",
        default=DEFAULT_SOURCE_DIR,
        help="Durable directory for capture sources",
    )
    p.add_argument(
        "--denote-cmd",
        default=os.environ.get("DENOTE_CMD", "denote"),
        help="Command used to run denote; may include arguments",
    )
    p.add_argument("--config", help="Pass global denote --config PATH")
    p.add_argument("--silo", help="Pass global denote --silo NAME")
    p.add_argument("--root", help="Pass global denote --root PATH")
    p.add_argument("--model", help="Pass llm-wiki --model MODEL")
    p.add_argument("--max-rounds", type=int, help="Pass llm-wiki --max-rounds N")
    p.add_argument("--fresh", action="store_true", help="Pass llm-wiki --fresh")
    p.add_argument(
        "--no-ingest",
        action="store_true",
        help="Only write source packet; do not run denote",
    )
    p.add_argument(
        "--lint",
        action="store_true",
        help="Run `denote llm-wiki lint --fix` after successful ingest",
    )
    return p


def main() -> int:
    args = parser().parse_args()
    body = sys.stdin.read().strip()
    if not body:
        print("capture_to_llm_wiki.py: stdin had no capture material", file=sys.stderr)
        return 2

    now = dt.datetime.now().astimezone()
    stamp = now.strftime("%Y%m%dT%H%M%S")
    captured_at = now.isoformat(timespec="seconds")
    source_dir = expand_path(args.source_dir)
    source_dir.mkdir(parents=True, exist_ok=True)
    source_path = unique_path(source_dir, stamp, slugify(args.title))
    source_path.write_text(build_packet(args, body, captured_at), encoding="utf-8")
    print(f"Source: {source_path}")

    if args.no_ingest:
        return 0

    code = run(ingest_cmd(args, source_path))
    if code == 0 and args.lint:
        code = run(lint_cmd(args))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
