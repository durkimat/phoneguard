// Spike: how big and how heavy is a whatsmeow WhatsApp linked-device client on the J36?
// Not part of the product. Run on the phone from adb: /data/local/tmp/wa-spike
package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"runtime"
	"strings"
	"syscall"
	"time"

	_ "golang.org/x/crypto/x509roots/fallback" // Android has no CA bundle where Go looks for one
	_ "modernc.org/sqlite"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
)

func rssKB() string {
	b, err := os.ReadFile("/proc/self/status")
	if err != nil {
		return "?"
	}
	for _, l := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(l, "VmRSS:") {
			return strings.TrimSpace(strings.TrimPrefix(l, "VmRSS:"))
		}
	}
	return "?"
}

func cpuTicks() string {
	b, err := os.ReadFile("/proc/self/stat")
	if err != nil {
		return "?"
	}
	f := strings.Fields(string(b))
	if len(f) < 15 {
		return "?"
	}
	return f[13] + "+" + f[14] // utime+stime in clock ticks (100 per second)
}

func main() {
	// Android has no /etc/resolv.conf for Go's resolver
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, n, a string) (net.Conn, error) {
		d := net.Dialer{Timeout: 5 * time.Second}
		return d.DialContext(ctx, "udp", "1.1.1.1:53")
	}}
	dir := os.Getenv("WA_DIR")
	if dir == "" {
		dir = "/data/local/tmp"
	}
	ctx := context.Background()
	log := waLog.Stdout("wa", "WARN", false)
	container, err := sqlstore.New(ctx, "sqlite", "file:"+dir+"/wa.db?_pragma=foreign_keys(1)", log)
	if err != nil {
		fmt.Println("store error:", err)
		os.Exit(1)
	}
	store.SetOSInfo("PhoneGuard", [3]uint32{0, 1, 0})
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		fmt.Println("device error:", err)
		os.Exit(1)
	}
	cli := whatsmeow.NewClient(device, log)
	cli.AddEventHandler(func(evt interface{}) {
		switch v := evt.(type) {
		case *events.Connected:
			fmt.Println("EVENT connected")
		case *events.PairSuccess:
			fmt.Println("EVENT paired as", v.ID)
		case *events.Message:
			fmt.Println("EVENT message from", v.Info.Sender.User, "chars:", len(v.Message.GetConversation()))
		case *events.LoggedOut:
			fmt.Println("EVENT logged out")
		case *events.Disconnected:
			fmt.Println("EVENT disconnected")
		}
	})
	if cli.Store.ID == nil {
		qr, _ := cli.GetQRChannel(ctx)
		if err := cli.Connect(); err != nil {
			fmt.Println("connect error:", err)
			os.Exit(1)
		}
		fmt.Println("EVENT connected to WhatsApp, waiting to be linked (no account yet)")
		if phone := os.Getenv("WA_PHONE"); phone != "" {
			code, err := cli.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
			fmt.Println("PAIRING CODE:", code, "err:", err)
		}
		go func() {
			for e := range qr {
				fmt.Println("QR event:", e.Event)
			}
		}()
	} else if err := cli.Connect(); err != nil {
		fmt.Println("connect error:", err)
		os.Exit(1)
	}
	start := time.Now()
	t := time.NewTicker(10 * time.Second)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	for {
		select {
		case <-t.C:
			var m runtime.MemStats
			runtime.ReadMemStats(&m)
			fmt.Printf("t=%3ds RSS=%s heap=%dMB goroutines=%d cpu(ticks)=%s connected=%v\n",
				int(time.Since(start).Seconds()), rssKB(), m.HeapAlloc/1024/1024, runtime.NumGoroutine(), cpuTicks(), cli.IsConnected())
		case <-sig:
			cli.Disconnect()
			return
		}
	}
}
