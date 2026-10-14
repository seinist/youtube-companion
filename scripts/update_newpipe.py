#!/usr/bin/env python3
"""Pin the latest stable NewPipeExtractor release after checking its JitPack POM."""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
LATEST = "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest"
COORDINATE = re.compile(r'(implementation\("com\.github\.TeamNewPipe:NewPipeExtractor:)([^"\s]+)("\))')


def fetch(url):
    request = Request(url, headers={"User-Agent": "youtube-aa-companion-updater"})
    with urlopen(request, timeout=30) as response:
        return response.read()


def version(tag):
    if not isinstance(tag, str) or not re.fullmatch(r"v?\d+\.\d+\.\d+", tag):
        raise ValueError("Expected a stable release tag such as v0.26.5.")
    return tuple(map(int, tag.lstrip("v").split(".")))


def update(check=False, root=ROOT):
    path = root / "car/build.gradle.kts"
    original = path.read_bytes()
    source = original.decode("utf-8")
    matches = list(COORDINATE.finditer(source))
    if len(matches) != 1:
        raise ValueError("Expected exactly one NewPipeExtractor dependency; no files changed.")
    match = matches[0]
    current = match.group(2)
    release = json.loads(fetch(LATEST))
    if not isinstance(release, dict) or release.get("draft") is not False or release.get("prerelease") is not False:
        raise ValueError("GitHub did not return a stable published release.")
    latest = release.get("tag_name")
    latest_version, current_version = version(latest), version(current)
    print(f"NewPipeExtractor: configured {current}; latest stable {latest}")
    print(f"Release notes: https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/{latest}")
    if latest_version <= current_version:
        print("No update needed (automatic downgrades are disabled).")
        return
    if check:
        print("Update available. Run ./build.sh update-newpipe to apply it.")
        return

    print("Checking JitPack availability (timeout: 30 seconds)...", flush=True)
    pom_url = f"https://jitpack.io/com/github/TeamNewPipe/NewPipeExtractor/{latest}/NewPipeExtractor-{latest}.pom"
    pom = ET.fromstring(fetch(pom_url))
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    if pom.tag != namespace + "project" or any(
        pom.findtext(namespace + key) != value for key, value in {
            "groupId": "com.github.TeamNewPipe",
            "artifactId": "NewPipeExtractor",
            "version": latest,
        }.items()
    ):
        raise ValueError("JitPack returned an unexpected POM; no files changed.")

    updated = source[:match.start(2)] + latest + source[match.end(2):]
    # Replace only after all remote checks succeed. Preserve permissions and edits.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(updated.encode("utf-8"))
        temporary.chmod(path.stat().st_mode)
        if path.read_bytes() != original:
            raise ValueError("Gradle file changed during the check; retry the update.")
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    print(f"Updated car/build.gradle.kts: {current} -> {latest}")
    print("Next: ./gradlew :car:testDebugUnitTest :app:assembleDebug")
    print("Then test search and playback on a device and review dependency license changes.")
    print("POM availability does not prove API compatibility or a successful build.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check GitHub only; do not modify files")
    args = parser.parse_args()
    try:
        update(args.check)
    except (OSError, ValueError, HTTPError, URLError, ET.ParseError) as error:
        print(f"ERROR: NewPipeExtractor update failed: {error}", file=sys.stderr)
        print("Check network access, GitHub rate limits and JitPack availability, then retry.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
