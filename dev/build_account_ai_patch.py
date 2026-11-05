#!/usr/bin/env python3
"""Build a scoped Dev hotfix from the running jar without deploying other workspace work."""

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import zipfile


ENTRIES = (
    "BOOT-INF/classes/xiaozhi/modules/externalidentity/ExternalIdentityService.class",
    "BOOT-INF/classes/xiaozhi/modules/chatarchive/ChatArchiveOutbox.class",
    "BOOT-INF/classes/xiaozhi/modules/chatarchive/ChatArchiveOutbox$Owner.class",
    "BOOT-INF/classes/db/changelog/chat-archive.changelog.yaml",
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.resolve() in (args.base.resolve(), args.candidate.resolve()):
        raise SystemExit("Output must be a new artifact")
    with zipfile.ZipFile(args.base) as base, zipfile.ZipFile(args.candidate) as candidate:
        if any(name.upper().startswith("META-INF/") and
               (name.upper().endswith((".RSA", ".DSA", ".EC")) or name.upper().startswith("META-INF/SIG-"))
               for name in base.namelist()):
            raise SystemExit("Refuse to alter a signed jar")
        if not all(name in base.namelist() and name in candidate.namelist() for name in ENTRIES):
            raise SystemExit("Required patch entry is missing")
        replacements = {name: candidate.read(name) for name in ENTRIES}
        manifest = {"base_sha256": digest(args.base.read_bytes()),
                    "candidate_sha256": digest(args.candidate.read_bytes()),
                    "entries": {name: digest(data) for name, data in replacements.items()}}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(prefix=".account-ai-", dir=args.output.parent, delete=False) as temp:
            temporary = Path(temp.name)
        try:
            with zipfile.ZipFile(temporary, "w") as output:
                for entry in base.infolist():
                    # Preserve nested-library storage modes and every unrelated byte.
                    output.writestr(copy.copy(entry), replacements.get(entry.filename, base.read(entry.filename)))
                output.writestr("META-INF/account-ai-patch.json", json.dumps(manifest, sort_keys=True, indent=2))
            with zipfile.ZipFile(temporary) as output:
                if output.testzip() is not None:
                    raise SystemExit("Artifact CRC verification failed")
                for name in base.namelist():
                    expected = replacements.get(name, base.read(name))
                    if output.read(name) != expected:
                        raise SystemExit("Unexpected artifact entry change")
            os.chmod(temporary, 0o600)
            os.replace(temporary, args.output)
        finally:
            temporary.unlink(missing_ok=True)
    print("Scoped account AI artifact:", args.output)
    print("SHA256:", digest(args.output.read_bytes()))


if __name__ == "__main__":
    main()
