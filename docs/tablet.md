# Ergatta tablet

Surveyed over adb on 2026-11-11.

## Hardware and OS

| | |
|---|---|
| Model | `Ergatta Rower` (product `ergatta_rower`, board `rk30sdk`) |
| SoC | Rockchip RK3399 |
| GPU | Mali-T860, OpenGL ES 3.2 |
| RAM | 2 GB (`MemTotal` 1.96 GB); ~850 MB available with a browser and NewPipe open |
| Storage | 10 GB `/data`, 6.7 GB free |
| Display | 1920×1080, density 160 (mdpi) |
| Android | 9 (API 28), `userdebug` build signed with test-keys |
| Java heap | 192 MB growth limit, 512 MB max (native memory, e.g. Godot, is separate) |
| WebView | `com.google.android.webview` 70.0.3538.110, from 2018 and not updated |

Hardware video decoders (`OMX.rk.*`): H.264, HEVC, VP9, VP8 and MPEG-2/4, up to 4096×2160.
There is no AV1 decoder.

## Software

| Package | Role |
|---|---|
| `com.ergatta.seaborn` | The Ergatta app, installed in `/data/app`; ~120 MB PSS |
| `com.ergatta.updatemanager` | **Device owner**; Ergatta's update manager |
| `com.ergatta.bort`, `com.ergatta.bort.ota` | Memfault Bort: device telemetry and OTA |
| `com.android.launcher3` | The default home app; Ergatta is not the launcher |

## Home app

The tablet's home app is chosen with `cmd package set-home-activity` (`just set-home` and
`just restore-home`). Three apps offer to be home: Android's launcher
(`com.android.launcher3/.Launcher`, the original default), Ergatta's
(`com.ergatta.seaborn/.LoginRegisterActivity`) and SuperTuxRow.

The tablet's launcher is a customised Launcher3 (the Quickstep build). When it starts, it opens
the activity named in the system property `persist.sys.bootapp`, which ships set to
`com.ergatta.seaborn/com.ergatta.seaborn.LoginRegisterActivity`: that's how the Ergatta app
appears at start-up. `just set-home` clears it, so opening Android's launcher from SuperTuxRow
doesn't bounce into Ergatta; `just restore-home` puts it back. (Ergatta's own boot receiver only
reschedules background work.)

## Updates

- `com.ergatta.bort.ota` and `com.ergatta.bort` (Memfault) handle firmware updates and
  telemetry. `just disable-updates` disables both; `just enable-updates` re-enables them. A
  firmware update could undo root, persistent adb and the home-app setup.
- `com.ergatta.updatemanager` can install app updates silently. It's the device owner, so
  `pm disable-user` refuses ("Cannot disable a protected package"). Disabling it would mean
  removing its device-owner registration as root first.
- The Ergatta app updates itself from `appupdates.ergatta.com`; only disabling the app stops that.

## Text-to-speech

The tablet ships with no text-to-speech engine, so Claude Rowed shows "no voice installed" and
Bob only types. Any engine works; the app uses the system default. Free ones from F-Droid:

- **RHVoice**: fairly natural, light, offline once a voice is downloaded. The best fit here.
- **SherpaTTS** (Piper voices): the most natural, but neural and heavy for 2 GB of RAM.
- **eSpeak NG**: robotic but tiny, and works straight away.

To install RHVoice:

1. Download its APK from f-droid.org and install it: `adb -s $TABLET install RHVoice.apk`.
2. Open RHVoice on the tablet and download an English voice (needs Wi-Fi).
3. Make it the default:
   `adb -s $TABLET shell settings put secure tts_default_synth com.github.olga_yakovleva.rhvoice.android`
   (or Settings → Accessibility → Text-to-speech output).
4. Restart SuperTuxRow.

## USB

- The tablet runs `adbd` with `sys.usb.config=adb`, and the RK3399 USB-C controller `fe800000.dwc3`
  supports device mode. adb over a cable was not made to work; see below for Wi-Fi.
- The rower sensor enumerates on the tablet's xHCI host (port `7-1`, `04d8:000a`) and the kernel
  binds it as `/dev/ttyACM0` (root-only). It answers the `USB` handshake with `_WR_` from a root
  shell. An app should use the Android USB host API instead, which detaches the kernel driver.
- When testing from a shell, put the tty in raw mode with echo off (`stty raw -echo < /dev/ttyACM0`;
  toybox `stty` has no `-F`). With echo on, the tty echoes the S4's replies back to it, and the
  two trade `ERROR`s for as long as the port is open.
- `com.ergatta.seaborn` (v2026.46.007) registers for `USB_DEVICE_ATTACHED`, so it competes for the
  sensor with any replacement app until it is disabled.

## adb access

`su` works for the terminal app (Termux). To start adb over Wi-Fi, run this on the tablet; it
lasts until reboot:

```sh
su -c 'setprop service.adb.tcp.port 5555; stop adbd; start adbd'
```

Give the tablet a fixed address on your network (a DHCP reservation on the router) and put it
in `.env` as `TABLET=host:5555`; `just connect` then connects to it. A `.local` name won't work:
Android 9 doesn't advertise its hostname over mDNS.

`just persist-adb` sets `persist.adb.tcp.port`, so adb over Wi-Fi comes back after a reboot;
`just unpersist-adb` undoes it.

**Security:** `ro.adb.secure=0`, so adbd accepts any client without key authorization. While
TCP adb is on, anyone on the network can get a shell, and with `su`, root. Persisting it keeps
that door open all the time, so only do it on a network you trust.
