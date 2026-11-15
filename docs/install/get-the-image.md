# Get the image and check it (step 3)

The image is published as a **release on this repository's GitHub page** (releases whose name starts with `image-`). It is two files:

- `system.img.gz`, the Phone Guard system, compressed (the unpacked image is about 2.3 GB),
- `SHA256SUMS`, the checksums.

> **Please read:** the image contains files from Qin and MediaTek and parts of TrebleDroid whose licences are still being clarified (see [licensing](../licensing.md)). It is provided for owners of the phone, for personal use, as a convenience while that is sorted out. It may be withdrawn or replaced. Please do not re-upload it elsewhere.

## The easy way: let the installer fetch it

The installer downloads the newest image release, unpacks it, checks it and flashes it in one go (it asks before writing anything):

In the `phoneguard` folder from [step 1](computer-setup.md):

```bash
node pg-install.mjs download
```

That fetches the newest image (about 1 GB), unpacks it (about 2.4 GB) and checks it against its checksum, without touching the phone. *Worked when:* every line says `OK`. You need about 5 GB of free disk space. (To fetch and flash in one go: `node pg-install.mjs all --download`.)

## By hand

1. Open the repository's **Releases** page, find the newest release whose name starts with `image-` and download `system.img.gz` and `SHA256SUMS` into one folder.
2. Unpack it (Windows: 7-Zip; Linux and macOS: `gunzip -k system.img.gz`).
3. Check it:

```bash
sha256sum -c SHA256SUMS            # macOS: shasum -a 256 -c SHA256SUMS
```

Every line must say `OK`. If one does not, download again; do not flash a file that fails its check.

## Or build it yourself

For people comfortable with Linux and a very large build: [build-it-yourself](../build-it-yourself.md).

Next: [flash it](flash.md).
