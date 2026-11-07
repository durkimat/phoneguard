# WhatsApp client size and resource spike (throwaway)

Measures a `whatsmeow` linked-device client on the Qin J36. Not part of the product.

```bash
export PATH=$HOME/.local/gotool/go/bin:$PATH GOPATH=$HOME/.local/gotool/gopath GOFLAGS=-mod=mod
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o wa-spike .
adb push wa-spike /data/local/tmp/ && adb shell 'cd /data/local/tmp && WA_DIR=/data/local/tmp/wa ./wa-spike'
# to link an account by code: WA_PHONE=<number with country code> ./wa-spike   (prints PAIRING CODE)
```

Android needs two workarounds for plain Go: a resolver that does not read `/etc/resolv.conf`, and
embedded root certificates (`golang.org/x/crypto/x509roots/fallback`). Both are in `main.go`.
