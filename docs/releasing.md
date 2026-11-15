# Releasing (for the project)

A release publishes the phone app and the companion together, so that parents can update either without reinstalling. The tool does the checks and the upload; you write the notes.

## Each release

1. **Build the APK** (the build session does this) and note its file name: `phoneguard-X.Y.Z-release.apk`.
2. **If the companion changed**, bump `COMPANION_VERSION` in `companion/worker/src/version.js`. If its database changed, add a step at the END of the list in `companion/worker/src/migrations.js` (see the rules at the top of that file). Run the Worker tests (`cd companion/worker && npm test`, with `npm run dev` running on a fresh state).
3. **Dry run**:

   ```
   cd companion/setup-tool
   node release.mjs --apk /path/to/phoneguard-X.Y.Z-release.apk --notes-file notes.md
   ```

   It rebuilds the companion bundle, compares it with the one in the newest published release, and stops if something is wrong (the code changed but the version did not, the version went backwards, the schema went down, the tag exists, the APK name does not match the tag).
4. **Publish**: add `--publish`. This creates the GitHub release `vX.Y.Z` with the APK, its `.sha256`, `worker.bundle.js` and `worker-version.json`. It becomes the newest release, which is what parents' pages and phones check.
5. **Tell parents**: nothing to do. Their parent page shows "A newer companion is available" the next time they open it (it checks at most every 12 hours), and "Update this phone" appears under Updates when the app is newer.

A release with no app change still needs an APK for the phone side to stay quiet (the same version is "up to date"). To publish only the companion, use `--companion-only --tag vX.Y.Z`.

## The rules that keep updates safe

- **The Worker accepts every phone app version and ignores fields it does not know. The app ignores config fields it does not know.** The sealed-secret slot names (`spotify.refresh`, `email.password`, `calendar.<id>`) and the shapes of the config's `email` and `calendars` are the compatibility surface: change them only by adding.
- **Database steps are additive and forward-only** (migrations.js). Never edit or reorder a released step, never drop a column or table in a step.
- **Old code never runs on newer data.** The Worker stores the schema number in the database and refuses to start if it is newer than its code; the setup tool also refuses to upload code with an older schema than the one the family is on.
- **Bumping the phone protocol** (`PROTOCOL` in version.js) is a breaking change: keep the old protocol working in the Worker for at least one release.
- **Updating is by version number**, `major.minor.patch`. `0.10.0` is newer than `0.9.0`.

## What a parent's update does

The setup tool reads the newest release, downloads `worker.bundle.js`, checks its size and sha256 against `worker-version.json`, uploads it to the parent's Worker with the existing secrets and Durable Object kept (`keep_bindings`), waits until `/api/version` reports the new version, and checks the parent key still works. See [updating.md](updating.md).
