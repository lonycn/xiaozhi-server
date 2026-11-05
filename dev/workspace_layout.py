#!/usr/bin/env python3
"""Create or check workspace category links without moving source or runtime data."""
import argparse
import os
from pathlib import Path
import sys
import tomllib


REPO = Path(__file__).resolve().parents[1]


def category_links(workspace):
    repository = workspace / "backend/xiaozhi-server/repo"
    if repository.resolve() != REPO:
        raise ValueError("Workspace must contain this checkout at backend/xiaozhi-server/repo")
    frontend = repository / "main/manager-web"
    if frontend.is_symlink() or not (frontend / "package.json").is_file() or (frontend / ".git").exists():
        raise ValueError("Vue 3 frontend must be part of this checkout at main/manager-web")
    links = {
        "frontend": frontend,
        "backend/xiaozhi-server/repo/main/manager-web-next": frontend,
        "backend/manager-api": repository / "main/manager-api",
        "backend/services": repository / "main/xiaozhi-server",
        "backend/dev": repository / "dev",
        "docs": repository / "docs",
        "releases": workspace / ".local/releases",
        "backups": workspace / ".local/backups",
        "archive/experiments": workspace / "work",
        "archive/frontend-vue2": workspace / "archive/server-web-vue2-20261105",
        "archive/legacy-console": workspace / "backend/xiaozhi-server/console",
        "archive/legacy-voice-source": workspace / "backend/xiaozhi-server/source",
        ".local/releases/README.md": repository / "releases/README.md",
        ".local/releases/catalog.toml": repository / "releases/catalog.toml",
        ".local/backups/README.md": repository / "docs/BACKUPS.md",
        ".local/backups/dev-database": repository / "dev/.runtime/backups",
        ".local/backups/legacy-console-database": workspace / "backend/xiaozhi-server/console/.runtime/backups",
    }
    catalog = tomllib.loads((repository / "releases/catalog.toml").read_text())
    for release in catalog["firmware"]:
        alias = Path(release["source_alias"])
        source = Path(release["source_snapshot"])
        if (alias.is_absolute() or source.is_absolute()
                or ".." in alias.parts or ".." in source.parts
                or alias.parts[:1] != ("firmware",)
                or source.parts[:1] != ("work",)):
            raise ValueError("Firmware aliases must stay in firmware/ and snapshots in work/")
        if str(alias) in links:
            raise ValueError("Duplicate workspace alias")
        links[str(alias)] = workspace / source
    return links


def inspect(workspace, links):
    missing, conflicts = [], []
    for name, target in links.items():
        link = workspace / name
        if not target.exists():
            conflicts.append(f"Missing target for {name}")
        elif link.is_symlink() and link.resolve() == target.resolve():
            continue
        elif os.path.lexists(link):
            conflicts.append(f"Existing path is not the expected link: {name}")
        else:
            for parent in link.parents:
                if parent == workspace:
                    break
                if os.path.lexists(parent) and not parent.is_dir():
                    conflicts.append(f"Parent is not a directory: {parent.relative_to(workspace)}")
                    break
            missing.append(name)
    return missing, conflicts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=REPO.parents[2])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="Create missing category links")
    mode.add_argument("--check", action="store_true", help="Check links without changing files (default)")
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    try:
        links = category_links(workspace)
        missing, conflicts = inspect(workspace, links)
        if conflicts:
            for conflict in conflicts:
                print(f"CONFLICT {conflict}", file=sys.stderr)
            return 1
        if args.apply:
            for name in missing:
                link = workspace / name
                link.parent.mkdir(parents=True, exist_ok=True)
                link.symlink_to(os.path.relpath(links[name], link.parent))
            missing, conflicts = inspect(workspace, links)
        for name, target in links.items():
            state = "MISSING" if name in missing else "OK"
            print(f"{state} {name} -> {target.relative_to(workspace)}")
        if conflicts:
            for conflict in conflicts:
                print(f"CONFLICT {conflict}", file=sys.stderr)
        return int(bool(missing or conflicts))
    except (OSError, ValueError, KeyError) as error:
        print(f"Workspace layout failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
