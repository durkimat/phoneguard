#!/bin/bash
# Generate Phone Guard's own release signing keys (the set AOSP's build expects next to PRODUCT_DEFAULT_DEV_CERTIFICATE).
#   make-keys.sh OUTDIR        refuses to overwrite existing keys; OUTDIR is created mode 700
# Private keys are PKCS#8 DER (.pk8), unencrypted so the unattended build can sign; the certs (.x509.pem) are public.
# NEVER commit OUTDIR. Back it up off the NAS (encrypted). Losing the keys means every device must be wiped to change keys.
set -euo pipefail
out=${1:?usage: make-keys.sh OUTDIR}
mkdir -p "$out"; chmod 700 "$out"; cd "$out"
subj='/C=GB/O=Phone Guard/OU=Phone Guard/CN='
for name in releasekey platform shared media networkstack bluetooth nfc sdk_sandbox cts_uicc_2021 testkey; do
  if [ -e "$name.pk8" ] || [ -e "$name.x509.pem" ]; then echo "$name exists, skipping"; continue; fi
  umask 077
  openssl genrsa -f4 2048 2>/dev/null | tee "$name.key.tmp" | openssl pkcs8 -topk8 -outform DER -out "$name.pk8" -nocrypt
  openssl req -new -x509 -sha256 -key "$name.key.tmp" -out "$name.x509.pem" -days 10000 -subj "${subj}PhoneGuard-$name"
  rm -f "$name.key.tmp"
  echo "made $name"
done
chmod 600 *.pk8; chmod 644 *.x509.pem
ls -l
