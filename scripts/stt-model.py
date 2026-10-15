#!/usr/bin/env python3
"""Stage the walkie transcript's speech model for getknit.app, and check the app's pins against it.

The app downloads Moonshine v2 Medium Streaming (English, int8 `.ort`, MIT) file by file from getknit.app and checks every byte against the sizes and SHA-256s pinned in `SttModel`
(app/src/main/java/app/getknit/knit/call/SttModel.kt; docs/VOICE_CALLS.md C-3.11-9). This script is where those pins
come from, so nobody types a hash by hand:

  scripts/stt-model.py                 print the Kotlin pins for the upstream files
  scripts/stt-model.py --check         exit 1 unless SttModel.kt pins exactly those files
  scripts/stt-model.py --out DIR       also write the files to DIR/<model id>/, ready to upload under
                                       https://getknit.app/models/stt/ (immutable: a new model is a new id)

Upstream is Moonshine's own CDN, whose re-quantized weights go to a new dated directory rather than over the old ones
(docs/models/quantization.md in moonshine-ai/moonshine), so a model id names one directory and its files never move.
The licence comes from the library's repository at the tag the app's `moonshine-voice` AAR was built from, pinned.
Stdlib only.
"""

import argparse
import hashlib
import re
import sys
import urllib.request
from pathlib import Path

CDN = "https://download.moonshine.ai/model"
# The directory Moonshine published the per-channel quantization to (2026-08-21); the AAR's catalog names the same one.
QUANTIZED = "quantized_26_08_21"
# What a streaming model is, in the catalog's order (core/moonshine-model-catalog.cpp), and its licence last.
FILES = [
    "adapter.ort",
    "cross_kv.ort",
    "decoder_kv.ort",
    "encoder.ort",
    "frontend.model.ort",
    "frontend.weights.ort",
    "streaming_config.json",
    "tokenizer.bin",
]
# v0.1.5's tree; the MIT grant covers every streaming model (its SECTION 1).
LICENSE_URL = "https://raw.githubusercontent.com/moonshine-ai/moonshine/234f60faa0eb388b01cdf7e60aca232af37aefda/LICENSE"
LICENSE_SHA256 = "fa7d1174dd8af6a7cd280be20b80d10095ed4c19b5b20b61a7715c3ad790dc5f"
# name: (model id, the CDN's model directory). Medium alone: Tiny and Small were tried and dropped (ADR 2026-10.nz3g).
MODELS = {
    "medium": ("moonshine-medium-streaming-en-2026-08-21", "medium-streaming-en"),
}

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "app/src/main/java/app/getknit/knit/call/SttModel.kt"
# ktlint wraps a long pin over several lines, so any whitespace may sit between its parts.
PIN = re.compile(r'SttFile\(\s*"([^"]+)",\s*([0-9_]+),\s*"([0-9a-f]{64})",?\s*\)')


def fetch(url):
    # Moonshine's CDN answers 403 to urllib's own User-Agent.
    request = urllib.request.Request(url, headers={"User-Agent": "knit-stt-model/1"})
    with urllib.request.urlopen(request, timeout=600) as response:
        return response.read()


def licence():
    body = fetch(LICENSE_URL)
    digest = hashlib.sha256(body).hexdigest()
    if digest != LICENSE_SHA256:
        sys.exit(f"LICENSE from {LICENSE_URL} has sha256 {digest}, not the pinned {LICENSE_SHA256}")
    return body


def model_files(name, licence_body):
    """The files the app fetches, in its order: the model's, then the licence."""
    directory = MODELS[name][1]
    out = {}
    for file in FILES:
        out[file] = fetch(f"{CDN}/{directory}/{QUANTIZED}/{file}")
        print(f"  {name}: {file} {len(out[file]):,} B", file=sys.stderr)
    out["LICENSE"] = licence_body
    return out


def pins(files):
    return [(file, len(body), hashlib.sha256(body).hexdigest()) for file, body in files.items()]


def kotlin(pinned):
    return "\n".join(f'SttFile("{n}", {size:_}, "{sha}"),' for n, size, sha in pinned)


def pinned_in_kotlin(source, model_id):
    """The pins SttModel.kt gives the model whose id is [model_id]: the SttFile lines after its `id = "…"`."""
    at = source.find(f'id = "{model_id}"')
    if at < 0:
        return None
    following = source.find('id = "', at + 1)
    block = source[at : following if following >= 0 else len(source)]
    return [(n, int(size.replace("_", "")), sha) for n, size, sha in PIN.findall(block)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", choices=sorted(MODELS), help="one model only (default: all)")
    parser.add_argument("--check", action="store_true", help="fail unless SttModel.kt pins these files")
    parser.add_argument("--out", help="write the files under OUT/<model id>/ for upload")
    args = parser.parse_args()

    licence_body = licence()
    for name in [args.model] if args.model else sorted(MODELS):
        model_id = MODELS[name][0]
        files = model_files(name, licence_body)
        pinned = pins(files)
        print(f"// {name}: {model_id}")
        print(kotlin(pinned))

        if args.out:
            target = Path(args.out) / model_id
            target.mkdir(parents=True, exist_ok=True)
            for file, body in files.items():
                (target / file).write_bytes(body)
            print(f"wrote {len(files)} files to {target}: upload them to https://getknit.app/models/stt/{model_id}/")

        if args.check:
            found = pinned_in_kotlin(KOTLIN.read_text(), model_id)
            if found is None:
                sys.exit(f"{KOTLIN.relative_to(ROOT)} does not name the model id {model_id}")
            if found != pinned:
                sys.exit(f"{KOTLIN.relative_to(ROOT)} pins {found} for {model_id}, upstream has {pinned}")
            print(f"SttModel.kt pins for {model_id} match upstream")


if __name__ == "__main__":
    main()
