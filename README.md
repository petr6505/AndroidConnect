# AndroidConnect

Turn an Android tablet into a second monitor for Ubuntu (GNOME on Wayland), over Wi-Fi.

This isn't screen mirroring. The laptop creates a real virtual monitor through Mutter, so the
tablet shows up in *Settings → Displays*, where you can move, scale and rotate it like a plugged-in screen.
The picture is H.264 encoded on the GPU and sent over TLS. Touch, stylus and mouse input on the tablet go back to the laptop.

> **Note:** I developed this project together with AI tools (I used an AI coding assistant to write and
> review large parts of the code). I've tested it and use it daily, but treat it as a hobby project
> and expect some rough edges.

## Features

- Runs at the tablet's native resolution, 30 or 60 fps, with roughly 10 ms from capture to encoded frame
- Zero-copy VA-API encoding on Intel/AMD, with x264 as a fallback
- TLS 1.3, and the tablet pins the laptop's certificate after pairing
- One-time pairing with a 6-digit code shown on both devices
- Reconnects on its own. After a Wi-Fi hiccup the monitor stays alive for 30 s, so your windows don't get shuffled around
- Multi-touch, stylus (barrel button = right click) and mouse input
- Remembers which side of the laptop you put the tablet on, and its scale
- Runs as a systemd user service, so closing the window doesn't disconnect anything

## Requirements

- **Laptop:** Ubuntu 24.04 or newer, GNOME on Wayland (it relies on Mutter's D-Bus APIs)
- **Tablet:** Android 10 or newer
- Both devices on the same network

## Installing

Grab the `.deb` and `.apk` from the [Releases](https://github.com/petr6505/AndroidConnect/releases) page,
or build them yourself (see below).

On the laptop:

```sh
sudo apt install ./androidconnect_1.2.0_all.deb
```

The service starts right away and then at every login. The **Android Connect** app in the app grid shows
the status, your IP address and the paired tablets.

If you use `ufw`, open the ports:

```sh
sudo ufw allow 47100/tcp
sudo ufw allow 5353/udp   # mDNS discovery
```

On the tablet, sideload the APK (`adb install AndroidConnect-1.2.0.apk`, or copy it over and open it).

To uninstall, run `sudo apt remove androidconnect`. Pairings and keys are kept in `~/.config/androidconnect`; delete that folder to reset everything.

## Building

```sh
./build.sh
```

This produces `dist/androidconnect_<version>_all.deb` and `dist/AndroidConnect-<version>.apk`.

- The `.deb` only needs `dpkg-deb` (and `python3-gi` for rendering the icons).
- The APK needs JDK 17 and the Android SDK. Create `android/local.properties` with `sdk.dir=/path/to/Android/Sdk`.
  The release build is signed with the debug key, which is fine for sideloading. Use your own keystore if you want to distribute it.

## Usage

1. Open the app on the tablet. Your laptop should appear under *Laptops nearby*. Tap it.
2. The laptop shows a notification with a code. If it matches the one on the tablet, click **Accept**.
3. The tablet is now a screen. Arrange it in *Settings → Displays* if needed.
4. Next time, just tap **Connect display**.

Press Back on the tablet to disconnect. If mDNS is blocked on your network (common on guest or office Wi-Fi), use *Connect by IP address*.

### Settings

On the tablet (gear icon): frame rate, quality (Low / Balanced / High), resolution (native or 1080p),
whether one finger acts as a touchscreen or as a mouse, and a performance overlay. A four-finger tap also toggles the overlay.

On the laptop (*Streaming* section): encoder choice, a frame rate cap, a fixed bitrate (5 to 50 Mbit/s) instead of
automatic, and whether to draw the mouse pointer into the video. These apply the next time a tablet connects.

## How it works

```
Tablet app                                Laptop: androidconnectd (systemd user service)
───────────                               ───────────────────────────────────────────────
NsdManager (mDNS) ── discovery ─────────► Avahi  _androidconnect._tcp
TLS 1.3 (pinned) ◄── H.264 frames ─────── pipewiresrc (DMA-BUF) → vapostproc → vacompositor → vah264enc
MediaCodec → SurfaceView                    ▲
touch/pen/mouse ──── JSON events ───────► Mutter RemoteDesktop (input injection)
                                            Mutter ScreenCast.RecordVirtual → virtual monitor
```

| Path | What's in it |
| --- | --- |
| `linux/androidconnect/daemon.py` | The service: TLS server, pairing, sessions, D-Bus API for the GUI |
| `linux/androidconnect/mutter.py` | Virtual monitor, input injection, layout handling |
| `linux/androidconnect/encoder.py` | GStreamer pipeline and encoder detection |
| `linux/androidconnect/gui.py` | GTK4 / libadwaita control window |
| `linux/packaging/` | Debian packaging, systemd unit, autostart entry |
| `android/.../Link.kt` | Connection, pairing and reconnect logic |
| `android/.../VideoDecoder.kt` | Low-latency MediaCodec decoder with fallback |
| `android/.../SpsRewriter.kt` | Patches the SPS so decoders don't buffer frames |

### Latency notes

Most of the work went into keeping latency low. Here are the less obvious problems I ran into:

- **Mutter goes stale after layout changes.** After any display reconfiguration, Mutter keeps feeding the
  virtual monitor's stream from an old view at about 5 fps. The daemon restarts the capture (not the
  monitor or the connection) whenever the layout changes.
- **Damage-only frames and the encoder's buffering.** Mutter only sends frames when something changes, and `vah264enc` holds
  back two frames at variable frame rate. A single keystroke could sit there until the next change.
  `vacompositor` turns the capture into a constant-rate stream on the GPU. With a minimum QP, the
  repeated frames cost about 100 bytes each.
- **Decoder buffering on the tablet.** Many hardware decoders (Exynos, for example) buffer up to 16 frames unless the SPS says
  the stream never reorders frames. The app patches that flag in.
- **Congestion.** Once about 150 ms of video is queued, the laptop drops frames up to the next keyframe
  instead of letting the delay build up.

### Mouse pointer

Mutter uses either a hardware or a software cursor for all monitors at once. With a hardware cursor, the
pointer never gets drawn on the virtual monitor. The workaround is a throwaway 1 fps screencast of the tablet's
monitor with an embedded cursor. That forces a software cursor while the pointer is on the tablet.

### Pairing

The tablet commits to a random nonce before it sees the laptop's nonce. The 6-digit code is derived from both nonces and
the certificate fingerprint as each side sees it, so a man-in-the-middle would have to get lucky with
a 1-in-a-million guess. After you accept, the laptop issues a 256-bit token and stores only its hash.

## Troubleshooting

- Logs: `journalctl --user -u androidconnectd -f` (run `androidconnectd -v` for debug output)
- State and keys: `~/.config/androidconnect/`
- If no hardware encoder is detected, install `gstreamer1.0-plugins-bad`, or `gstreamer1.0-plugins-ugly` for x264.

## License

MIT, see [LICENSE](LICENSE).
