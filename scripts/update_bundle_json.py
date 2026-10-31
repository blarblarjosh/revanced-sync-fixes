"""Rewrite bundles/stable.json and bundles/latest.json from this repo's GitHub releases.

ReVanced Manager adds a remote patch bundle from a small JSON file that points at the release's patches.rvp.
After publishing a release, run this and commit the result:

    python scripts/update_bundle_json.py

stable.json follows the newest regular release; latest.json also follows pre-releases.
Standard library only; GitHub's public API needs no token.
"""

import json
import pathlib
import urllib.request

REPO = "blarblarjosh/revanced-sync-fixes"
ASSET = "patches.rvp"
BUNDLES = pathlib.Path(__file__).resolve().parent.parent / "bundles"


def releases():
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/releases?per_page=100",
        headers={"Accept": "application/vnd.github+json", "User-Agent": REPO},
    )
    with urllib.request.urlopen(request) as response:
        found = json.load(response)
    found = [r for r in found if not r["draft"] and any(a["name"] == ASSET for a in r["assets"])]
    return sorted(found, key=lambda r: r["published_at"], reverse=True)


def entry(release):
    asset = next(a for a in release["assets"] if a["name"] == ASSET)
    return {
        "created_at": release["published_at"].rstrip("Z"),
        "description": release["body"] or "",
        "download_url": asset["browser_download_url"],
        "signature_download_url": "N/A",
        "version": release["tag_name"],
    }


def main():
    found = releases()
    channels = {
        "stable": next((r for r in found if not r["prerelease"]), None),
        "latest": found[0] if found else None,
    }
    BUNDLES.mkdir(exist_ok=True)
    for name, release in channels.items():
        if release is None:
            print(f"{name}: no release with {ASSET} yet, left unchanged")
            continue
        (BUNDLES / f"{name}.json").write_text(json.dumps(entry(release), indent=2) + "\n", encoding="utf-8")
        print(f"{name}: {release['tag_name']}")


if __name__ == "__main__":
    main()
