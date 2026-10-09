#!/usr/bin/env python3
"""Stage the walkie transcript's speech model for getknit.app, and check the app's pins against it.

The app downloads Moonshine tiny EN v2 (sherpa-onnx's int8 ORT package) file by file from getknit.app and
checks every byte against the sizes and SHA-256s pinned in `SttModel.MOONSHINE_TINY_EN_V2`
(app/src/main/java/app/getknit/knit/call/SttModel.kt; docs/VOICE_CALLS.md C-3.11-9). This script is where
those pins come from, so nobody types a hash by hand:

  scripts/stt-model.py                 print the Kotlin pins for the upstream archive
  scripts/stt-model.py --check         exit 1 unless SttModel.kt pins exactly those files
  scripts/stt-model.py --out DIR       also write the files to DIR/<model id>/, ready to upload under
                                       https://getknit.app/models/stt/ (immutable: a new model is a new id)
  scripts/stt-model.py --archive F     use an archive already downloaded instead of fetching it

The archive itself is pinned below, so a changed upstream release is a loud failure, not new pins.
Stdlib only.
"""

import argparse
import hashlib
import io
import re
import sys
import tarfile
import urllib.request
from pathlib import Path

MODEL_ID = "moonshine-tiny-en-2026-02-27"
PACKAGE = "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27"
ARCHIVE_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{PACKAGE}.tar.bz2"
ARCHIVE_SHA256 = "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d"
# What the app fetches, in the order it fetches them. LICENSE rides along: the English models are MIT.
FILES = ["encoder_model.ort", "decoder_model_merged.ort", "tokens.txt", "LICENSE"]

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "app/src/main/java/app/getknit/knit/call/SttModel.kt"
PIN = re.compile(r'SttFile\("([^"]+)", ([0-9_]+), "([0-9a-f]{64})"\)')


def archive_bytes(path):
    if path:
        data = Path(path).read_bytes()
    else:
        with urllib.request.urlopen(ARCHIVE_URL, timeout=120) as response:
            data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != ARCHIVE_SHA256:
        sys.exit(f"archive sha256 {digest} is not the pinned {ARCHIVE_SHA256}")
    return data


def model_files(data):
    """The files the app fetches, read straight out of the archive (nothing is extracted to disk)."""
    out = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:bz2") as tar:
        for member in tar.getmembers():
            name = member.name.split("/", 1)[-1]
            if member.isfile() and name in FILES:
                out[name] = tar.extractfile(member).read()
    missing = [f for f in FILES if f not in out]
    if missing:
        sys.exit(f"archive lacks {missing}")
    return out


def pins(files):
    return [(name, len(files[name]), hashlib.sha256(files[name]).hexdigest()) for name in FILES]


def kotlin(pinned):
    lines = [f'SttFile("{n}", {size:_}, "{sha}"),' for n, size, sha in pinned]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail unless SttModel.kt pins these files")
    parser.add_argument("--out", help="write the files under OUT/<model id>/ for upload")
    parser.add_argument("--archive", help="a local copy of the upstream archive")
    args = parser.parse_args()

    files = model_files(archive_bytes(args.archive))
    pinned = pins(files)
    print(kotlin(pinned))

    if args.out:
        target = Path(args.out) / MODEL_ID
        target.mkdir(parents=True, exist_ok=True)
        for name in FILES:
            (target / name).write_bytes(files[name])
        print(f"wrote {len(FILES)} files to {target}: upload them to https://getknit.app/models/stt/{MODEL_ID}/")

    if args.check:
        source = KOTLIN.read_text()
        found = [(n, int(size.replace("_", "")), sha) for n, size, sha in PIN.findall(source)]
        if found != pinned:
            sys.exit(f"{KOTLIN.relative_to(ROOT)} pins {found}, the archive has {pinned}")
        if f'"{MODEL_ID}"' not in source:
            sys.exit(f"{KOTLIN.relative_to(ROOT)} does not name the model id {MODEL_ID}")
        print("SttModel.kt pins match the archive")


if __name__ == "__main__":
    main()
