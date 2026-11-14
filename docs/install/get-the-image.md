# Get the image and check it (step 3)

The image kit is a folder with:

- `system.img`, the Phone Guard system (about 2.3 GB),
- optionally a helper script (`flash-system-only.sh` updates the system and keeps the user data),
- `SHA256SUMS`, a list of checksums.

## Where to get it

- **Today:** ask the project owner for the kit. The image is not a public download yet because it contains vendor files whose redistribution is still being checked (see [licensing](../licensing.md)).
- **Or build it yourself** from this repository: the recipe is in `build/` and [own-build-viability](../own-build-viability.md). You need a Linux machine with about 300 GB free disk and several hours.
- **Later:** a download link will be here once the licensing is settled.

## Check it

Put all the files in one folder, open a terminal there and run:

```bash
sha256sum -c SHA256SUMS            # macOS: shasum -a 256 -c SHA256SUMS
```

Every line must say `OK`. If one does not, download the kit again; do not flash a file that fails its check.

Next: [flash it](flash.md).
