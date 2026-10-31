# revanced-sync-fixes

A small [ReVanced](https://revanced.app) patch bundle for **Sync for Reddit v23.06.30-13:39**, the last release of
the discontinued Reddit client (`com.laurencedawson.reddit_sync`, plus the `.pro` and `.dev` packages).

It fixes Reddit endpoints that broke in Oct 2026. Use it **alongside** the official ReVanced bundle's
**Spoof client** patch.

## Patches

| Patch | What it fixes |
|-------|---------------|
| **Fix Reddit galleries** | Galleries fail to open: since Oct 2026 Reddit answers Sync's `/api/info` requests with "Blocked". Sync now loads the post from `/comments` instead. |
| **Fix load more comments** | "Load more comments" fails: Reddit blocked `/api/morechildren` too. Sync now loads the missing comments from `/comments`. |

Only version v23.06.30-13:39 is supported.

### Caveats

- **Not yet tested on a device.** The patches apply cleanly with revanced-cli and the logic was tested against
  sample responses, but nobody has run the patched app on a phone yet. Please report what you find.
- Reddit returns at most 500 comments per `/comments` request, so deep in very large threads some of the
  missing comments may not load.
- Awards still use `/api/info`, so they may stay broken. This bundle doesn't touch them.

## Use with ReVanced Manager

You need your own copy of Sync for Reddit v23.06.30-13:39. This project doesn't provide APKs.

1. Open ReVanced Manager, go to **Patches**, tap the **pencil icon** (bottom right), then **Add patches** →
   **Enter URL**, and paste:

   ```
   https://raw.githubusercontent.com/blarblarjosh/revanced-sync-fixes/main/bundles/stable.json
   ```

   It always points at the newest release. (Manager needs this JSON link; a link to the `.rvp` itself
   doesn't work. You can also download `patches.rvp` from [Releases](../../releases) and add it as a local file.)
2. Create an "installed app" at https://www.reddit.com/prefs/apps with redirect URI `http://redditsync/auth`.
3. Patch your **original, unpatched** Sync APK, selecting **Spoof client** from the official ReVanced patches
   (set your client ID and the redirect URI `http://redditsync/auth`) and both patches from this bundle.
4. Install.

If a patch reports that it couldn't find what it expected, the input APK isn't an untouched Sync
v23.06.30-13:39. Each patch checks the code it changes and stops instead of patching the wrong place.

## Building from source

Requires JDK 17, the Android SDK, and a GitHub token with `read:packages` scope (ReVanced publishes its
Gradle plugin and patcher to GitHub Packages). Either put credentials in `~/.gradle/gradle.properties`:

```properties
gpr.user=<github-username>
gpr.key=<token>
githubPackagesUsername=<github-username>
githubPackagesPassword=<token>
```

or pass them as environment variables (`GITHUB_ACTOR`, `GITHUB_TOKEN`,
`ORG_GRADLE_PROJECT_githubPackagesUsername`, `ORG_GRADLE_PROJECT_githubPackagesPassword`). Then:

```bash
./gradlew buildAndroid
# output: patches/build/libs/patches-<version>.rvp
```

`buildAndroid` is required: plain `./gradlew build` makes a bundle the desktop CLI can load but ReVanced
Manager can't.

After publishing a release (asset named `patches.rvp`), run `python scripts/update_bundle_json.py` and commit
the updated `bundles/*.json`.

Notes:
- The patch code lives in `app.revanced.patches.syncfixes` and the extension in `extensions/syncfixes`.
- The Java extension holds the logic. The Kotlin patches only hook it into Sync's Volley requests.

## Disclaimer

Not affiliated with or endorsed by Laurence Dawson, Sync for Reddit, Reddit or ReVanced. This repository
contains only original patch code: no APKs, no code from Sync. You are responsible for using your own legally
obtained copy of the app and for following the terms of the services it connects to. Use at your own risk.

## License

GNU General Public License v3.0, see [LICENSE](LICENSE).
