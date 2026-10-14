#!/usr/bin/env python3
"""Stage the walkie transcript's speech models for getknit.app, and check the app's pins against them.

The app downloads Moonshine EN v2 tiny or base, or Whisper small.en — the user's pick, each sherpa-onnx's int8
package — file by file from getknit.app and checks every byte against the sizes and SHA-256s pinned in `SttModel`
(app/src/main/java/app/getknit/knit/call/SttModel.kt; docs/VOICE_CALLS.md C-3.11-9). This script is where
those pins come from, so nobody types a hash by hand:

  scripts/stt-model.py [--model M]     print the Kotlin pins for the upstream archive (default: every model)
  scripts/stt-model.py --check         exit 1 unless SttModel.kt pins exactly those files, for every model
  scripts/stt-model.py --out DIR       also write the files to DIR/<model id>/, ready to upload under
                                       https://getknit.app/models/stt/ (immutable: a new model is a new id)
  scripts/stt-model.py --archive F     use an archive already downloaded instead of fetching it (one --model)

Each archive is pinned below, so a changed upstream release is a loud failure, not new pins.
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

RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
# name: (model id, upstream package, the package archive's sha256, the files the app fetches in order)
MOONSHINE_FILES = ["encoder_model.ort", "decoder_model_merged.ort", "tokens.txt", "LICENSE"]
WHISPER_SMALL_FILES = ["small.en-encoder.int8.onnx", "small.en-decoder.int8.onnx", "small.en-tokens.txt", "LICENSE"]
MODELS = {
    "tiny": (
        "moonshine-tiny-en-2026-02-27",
        "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
        "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d",
        MOONSHINE_FILES,
    ),
    "base": (
        "moonshine-base-en-2026-02-27",
        "sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
        "43232c1d13013d37317163baec3135bd771a186a4356f28c889bab453bb0e891",
        MOONSHINE_FILES,
    ),
    "whisper-small": (
        "whisper-small-en-2024-07-13",
        "sherpa-onnx-whisper-small.en",
        "0cdba2b8aaab69e04847f3427cc9709574112e67913a1a84b7fec3a8729faa9a",
        WHISPER_SMALL_FILES,
    ),
}
# A file a package lacks, fetched from where it lives, pinned too: (url, sha256). The Whisper package carries no
# licence; OpenAI's repository holds it (MIT), at a pinned commit.
EXTRA = {
    "whisper-small": {
        "LICENSE": (
            "https://raw.githubusercontent.com/openai/whisper/6e3be77e1a105e59086e3e21ff5f609fd6fa89a5/LICENSE",
            "b5d65a59060e68c4ff940e1eddfa6f94b2d68fdf58ed7f4dd57721c997e35e9d",
        ),
    },
}

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "app/src/main/java/app/getknit/knit/call/SttModel.kt"
# ktlint wraps a long pin over several lines, so any whitespace may sit between its parts.
PIN = re.compile(r'SttFile\(\s*"([^"]+)",\s*([0-9_]+),\s*"([0-9a-f]{64})",?\s*\)')


def archive_bytes(name, path):
    _, package, pinned, _ = MODELS[name]
    if path:
        data = Path(path).read_bytes()
    else:
        with urllib.request.urlopen(f"{RELEASE}/{package}.tar.bz2", timeout=300) as response:
            data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != pinned:
        sys.exit(f"{name}: archive sha256 {digest} is not the pinned {pinned}")
    return data


def model_files(name, data):
    """The files the app fetches, read straight out of the archive (nothing is extracted to disk), and any it lacks."""
    wanted = MODELS[name][3]
    extra = EXTRA.get(name, {})
    out = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:bz2") as tar:
        for member in tar.getmembers():
            file = member.name.split("/", 1)[-1]
            if member.isfile() and file in wanted and file not in extra:
                out[file] = tar.extractfile(member).read()
    for file, (url, pinned) in extra.items():
        with urllib.request.urlopen(url, timeout=60) as response:
            body = response.read()
        digest = hashlib.sha256(body).hexdigest()
        if digest != pinned:
            sys.exit(f"{name}: {file} from {url} has sha256 {digest}, not the pinned {pinned}")
        out[file] = body
    missing = [f for f in wanted if f not in out]
    if missing:
        sys.exit(f"archive lacks {missing}")
    return out


def pins(name, files):
    return [(file, len(files[file]), hashlib.sha256(files[file]).hexdigest()) for file in MODELS[name][3]]


def kotlin(pinned):
    lines = [f'SttFile("{n}", {size:_}, "{sha}"),' for n, size, sha in pinned]
    return "\n".join(lines)


def pinned_in_kotlin(source, model_id):
    """The pins SttModel.kt gives the model whose id is [model_id]: the SttFile lines after its `id = "…"`."""
    at = source.find(f'id = "{model_id}"')
    if at < 0:
        return None
    following = source.find("id = \"", at + 1)
    block = source[at : following if following >= 0 else len(source)]
    return [(n, int(size.replace("_", "")), sha) for n, size, sha in PIN.findall(block)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", choices=sorted(MODELS), help="one model only (default: all)")
    parser.add_argument("--check", action="store_true", help="fail unless SttModel.kt pins these files")
    parser.add_argument("--out", help="write the files under OUT/<model id>/ for upload")
    parser.add_argument("--archive", help="a local copy of the upstream archive (needs --model)")
    args = parser.parse_args()
    if args.archive and not args.model:
        parser.error("--archive needs --model")

    for name in [args.model] if args.model else sorted(MODELS):
        model_id = MODELS[name][0]
        files = model_files(name, archive_bytes(name, args.archive))
        pinned = pins(name, files)
        print(f"// {name}: {model_id}")
        print(kotlin(pinned))

        if args.out:
            target = Path(args.out) / model_id
            target.mkdir(parents=True, exist_ok=True)
            for file in MODELS[name][3]:
                (target / file).write_bytes(files[file])
            print(f"wrote {len(MODELS[name][3])} files to {target}: upload them to https://getknit.app/models/stt/{model_id}/")

        if args.check:
            found = pinned_in_kotlin(KOTLIN.read_text(), model_id)
            if found is None:
                sys.exit(f"{KOTLIN.relative_to(ROOT)} does not name the model id {model_id}")
            if found != pinned:
                sys.exit(f"{KOTLIN.relative_to(ROOT)} pins {found} for {model_id}, the archive has {pinned}")
            print(f"SttModel.kt pins for {model_id} match the archive")


if __name__ == "__main__":
    main()
