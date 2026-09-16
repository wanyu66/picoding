#!/usr/bin/env python3
"""Bounded, read-only repository inventory for secure onboarding.

This script uses only the Python standard library. It does not follow symlinks,
invoke subprocesses, access the network, execute repository code, or write files.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter
from pathlib import Path

DEFAULT_MAX_FILES = 5000
DEFAULT_MAX_FILE_BYTES = 1_000_000
DEFAULT_MAX_TOTAL_BYTES = 20_000_000

SKIP_DIRS = {
    ".git", ".hg", ".svn", ".idea", ".vscode", ".gradle", ".cache",
    ".mypy_cache", ".pytest_cache", ".tox", ".venv", "venv", "node_modules",
    "vendor", "target", "build", "dist", "out", "coverage", "__pycache__",
}

SENSITIVE_NAMES = {
    ".env", ".env.local", ".env.production", ".npmrc", ".pypirc", ".netrc",
    "credentials", "credentials.json", "secrets.json", "id_rsa", "id_ed25519",
    "known_hosts", "cookies.txt", "keychain", "wallet.dat",
}

SENSITIVE_SUFFIXES = {
    ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx", ".sqlite",
    ".sqlite3", ".db", ".dump", ".bak",
}

TEXT_SUFFIXES = {
    ".py", ".js", ".jsx", ".ts", ".tsx", ".java", ".kt", ".go", ".rs",
    ".rb", ".php", ".cs", ".c", ".h", ".cpp", ".hpp", ".swift", ".scala",
    ".sh", ".bash", ".ps1", ".sql", ".xml", ".yml", ".yaml", ".json",
    ".toml", ".ini", ".cfg", ".conf", ".properties", ".md", ".rst", ".txt",
}

LANGUAGES = {
    ".py": "Python", ".js": "JavaScript", ".jsx": "JavaScript",
    ".ts": "TypeScript", ".tsx": "TypeScript", ".java": "Java",
    ".kt": "Kotlin", ".go": "Go", ".rs": "Rust", ".rb": "Ruby",
    ".php": "PHP", ".cs": "C#", ".c": "C", ".h": "C/C++",
    ".cpp": "C++", ".hpp": "C++", ".swift": "Swift", ".scala": "Scala",
    ".sh": "Shell", ".bash": "Shell", ".ps1": "PowerShell", ".sql": "SQL",
}

MANIFEST_NAMES = {
    "package.json", "pyproject.toml", "requirements.txt", "pom.xml", "build.gradle",
    "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "go.mod", "Cargo.toml",
    "Gemfile", "composer.json", "Dockerfile", "docker-compose.yml", "compose.yml",
}

GUIDANCE_NAMES = {
    "AGENTS.md", "CLAUDE.md", "CONTRIBUTING.md", "SECURITY.md", "README.md",
    "README.rst", "README.txt",
}

ENTRY_NAMES = {
    "main.py", "app.py", "manage.py", "index.js", "index.ts", "server.js", "server.ts",
    "main.go", "main.rs", "Program.cs", "Application.java", "Dockerfile",
}

SYMBOL_PATTERNS = {
    ".py": re.compile(r"^\s*(?:async\s+)?(?:def|class)\s+([A-Za-z_]\w*)", re.MULTILINE),
    ".js": re.compile(r"^\s*(?:export\s+)?(?:async\s+)?(?:function|class)\s+([A-Za-z_$][\w$]*)", re.MULTILINE),
    ".jsx": re.compile(r"^\s*(?:export\s+)?(?:async\s+)?(?:function|class)\s+([A-Za-z_$][\w$]*)", re.MULTILINE),
    ".ts": re.compile(r"^\s*(?:export\s+)?(?:async\s+)?(?:function|class|interface|type|enum)\s+([A-Za-z_$][\w$]*)", re.MULTILINE),
    ".tsx": re.compile(r"^\s*(?:export\s+)?(?:async\s+)?(?:function|class|interface|type|enum)\s+([A-Za-z_$][\w$]*)", re.MULTILINE),
    ".java": re.compile(r"^\s*(?:public\s+|protected\s+|private\s+|abstract\s+|final\s+)*(?:class|interface|enum|record)\s+([A-Za-z_]\w*)", re.MULTILINE),
    ".go": re.compile(r"^\s*(?:func|type)\s+(?:\([^)]*\)\s*)?([A-Za-z_]\w*)", re.MULTILINE),
    ".rs": re.compile(r"^\s*(?:pub\s+)?(?:fn|struct|enum|trait)\s+([A-Za-z_]\w*)", re.MULTILINE),
}


def is_sensitive(path: Path) -> bool:
    name = path.name.lower()
    if name in SENSITIVE_NAMES or path.suffix.lower() in SENSITIVE_SUFFIXES:
        return True
    return name.startswith(".env.") and name != ".env.example"


def safe_relative(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def extract_symbols(path: Path, suffix: str, max_bytes: int) -> list[dict]:
    pattern = SYMBOL_PATTERNS.get(suffix)
    if pattern is None:
        return []
    try:
        raw = path.read_bytes()
    except OSError:
        return []
    if len(raw) > max_bytes or b"\x00" in raw[:8192]:
        return []
    text = raw.decode("utf-8", errors="replace")
    symbols = []
    for match in pattern.finditer(text):
        line = text.count("\n", 0, match.start()) + 1
        symbols.append({"name": match.group(1), "line": line})
        if len(symbols) >= 50:
            break
    return symbols


def scan(root: Path, max_depth: int, max_files: int, max_file_bytes: int,
         max_total_bytes: int) -> dict:
    files = []
    symbols = []
    skipped = Counter()
    languages = Counter()
    manifests = []
    guidance = []
    entry_points = []
    total_bytes = 0
    truncated = False

    for dirpath, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        current = Path(dirpath)
        depth = len(current.relative_to(root).parts)
        kept_dirs = []
        for dirname in sorted(dirnames):
            candidate = current / dirname
            if dirname in SKIP_DIRS or dirname.startswith("."):
                skipped["excluded_directory"] += 1
            elif candidate.is_symlink():
                skipped["symlink"] += 1
            elif depth >= max_depth:
                skipped["depth_limit"] += 1
            else:
                kept_dirs.append(dirname)
        dirnames[:] = kept_dirs

        for filename in sorted(filenames):
            if len(files) >= max_files or total_bytes >= max_total_bytes:
                truncated = True
                break
            path = current / filename
            rel = safe_relative(path, root)
            if path.is_symlink():
                skipped["symlink"] += 1
                continue
            if is_sensitive(path):
                skipped["sensitive"] += 1
                continue
            try:
                stat = path.stat()
            except OSError:
                skipped["unreadable"] += 1
                continue
            size = stat.st_size
            suffix = path.suffix.lower()
            total_bytes += min(size, max_file_bytes)
            record = {"path": rel, "size": size, "suffix": suffix}
            files.append(record)
            if suffix in LANGUAGES:
                languages[LANGUAGES[suffix]] += 1
            if filename in MANIFEST_NAMES:
                manifests.append(rel)
            if filename in GUIDANCE_NAMES or filename.upper().startswith("README"):
                guidance.append(rel)
            if filename in ENTRY_NAMES or filename.endswith("Application.java"):
                entry_points.append(rel)
            if suffix in TEXT_SUFFIXES and size <= max_file_bytes:
                found = extract_symbols(path, suffix, max_file_bytes)
                if found:
                    symbols.append({"path": rel, "symbols": found})
        if truncated:
            break

    return {
        "repository": str(root),
        "limits": {
            "max_depth": max_depth,
            "max_files": max_files,
            "max_file_bytes": max_file_bytes,
            "max_total_bytes": max_total_bytes,
        },
        "summary": {
            "files_listed": len(files),
            "bytes_considered": total_bytes,
            "truncated": truncated,
            "skipped": dict(skipped),
        },
        "languages_by_file_count": dict(languages.most_common()),
        "guidance_files": guidance[:100],
        "manifests": manifests[:100],
        "entry_point_candidates": entry_points[:100],
        "symbol_index": symbols[:500],
        "file_tree": [item["path"] for item in files],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Safely inventory a local repository")
    parser.add_argument("repository", help="Local repository root")
    parser.add_argument("--max-depth", type=int, default=5)
    parser.add_argument("--max-files", type=int, default=DEFAULT_MAX_FILES)
    parser.add_argument("--max-file-bytes", type=int, default=DEFAULT_MAX_FILE_BYTES)
    parser.add_argument("--max-total-bytes", type=int, default=DEFAULT_MAX_TOTAL_BYTES)
    parser.add_argument("--pretty", action="store_true")
    args = parser.parse_args()

    root = Path(args.repository).expanduser().resolve()
    if not root.is_dir():
        parser.error("repository must be an existing directory")
    if args.max_depth < 0 or args.max_files < 1 or args.max_file_bytes < 1 or args.max_total_bytes < 1:
        parser.error("limits must be positive (max-depth may be zero)")

    report = scan(root, args.max_depth, args.max_files,
                  args.max_file_bytes, args.max_total_bytes)
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2 if args.pretty else None)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
