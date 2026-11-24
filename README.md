# Changhong TV + Xiaomi box: power on straight to HDMI, power off with the box remote

[中文说明](README.zh-CN.md)

A Changhong "hotel series" Android TV and a Xiaomi TV box connected over HDMI. The goal was to use only the
Xiaomi remote: powering on should land on the box's picture, and powering the box off should turn the TV off
too. This repository documents what the TV actually does, and holds one small Android app (`tv-app`) that
works around it. Nothing needs to be installed on the box.

**This is a device-specific hack, tested on one TV, one box and one firmware version.
Read [Risks](#risks) before you touch anything on your own TV.**

## Tested hardware

- TV: Changhong 50J5500UH, Android 9 (SDK 28), MediaTek/MStar platform, hotel-series launcher
  `com.changhong.hotelv3`. `adbd` runs as root once USB debugging is on, and SELinux is disabled.
- Box: Xiaomi TV Box `MiTV-AZFU0`, Android 14, Amlogic. No changes were left on the box.

## Status

| Feature | Status |
| --- | --- |
| Xiaomi remote powers the TV on (HDMI-CEC) | Works out of the box |
| TV remote powers the box off with the TV | Works out of the box |
| After power-on the TV shows the box instead of the Changhong launcher | Works (`tv-app`) |
| Xiaomi remote power key also turns the TV off | Works (`tv-app`): the TV switches off a few seconds after the box |

"Works" means observed on this one setup, by watching the TV and reading the app's log.

The TV ignores the CEC `<Standby>` message the box sends, both broadcast and addressed to the TV
(see [docs/findings.md](docs/findings.md)). The power-off feature therefore does not use CEC.

## How it works

`tv-app` is an accessibility service. The system binds enabled accessibility services on every boot, which makes
it a reliable boot trigger on this firmware.

1. **Power-on.** The box wakes the TV over CEC. The TV cold-boots, selects HDMI internally, and then the hotel
   launcher starts on top of the picture. In the first 90 seconds after boot, whenever the launcher window
   appears the app starts `com.changhong.tvap.atv.activity/.AtvMainActivity`, which brings the picture back to the
   front. Once the picture has stayed in front for 6 seconds the job is done and the app stops interfering, so you
   can go to the TV desktop afterwards.
2. **Power-off.** When the box sleeps, the TV's own `ITVPlayer.isSignalStable()` turns false within about a second.
   A watcher thread polls it. If a source that had a stable signal loses it for 3 seconds, the app calls the TV's
   own `SystemManager.systemStandby(false)` through the Changhong framework client, which is what the power key does.
   It only watches the HDMI source. On the TV desktop the TV's current source is `E_INPUT_SOURCE_OTHER`, so the
   watcher is idle there and the Xiaomi remote cannot switch the TV off from the desktop; use the TV remote as usual.
   A loss that starts within 3 seconds of a foreground change is ignored, so switching between the picture and the
   desktop cannot turn the TV off.

## Repository layout

```
tv-app/    Android app for the TV (package local.hdmiboot)
scripts/   build.sh, builds the app without Gradle
docs/      findings (what the firmware does, with evidence)
```

## Build

You need a JDK 17, Android build-tools (r34 was used) and `android.jar` of API 28 or newer.

```sh
export JAVA_HOME=/path/to/jdk17
export BUILD_TOOLS=/path/to/android-sdk/build-tools/34.0.0
export ANDROID_JAR=/path/to/android-sdk/platforms/android-28/android.jar
scripts/build.sh            # build/HdmiBoot.apk
```

The first run creates `debug.keystore` for signing. Do not commit it.

## Install

`$TV` is the TV's address. Turn on developer options and USB debugging on the TV and accept the authorization
prompt. On this TV the network ADB port closes again after a reboot or standby, so toggle USB debugging off and
on to reopen it.

```sh
adb connect $TV:5555
adb -s $TV:5555 install -r build/HdmiBoot.apk
adb -s $TV:5555 shell am start -n local.hdmiboot/.MainActivity   # leaves the "stopped" state
```

The firmware force-stops third-party apps in the background and keeps a list in
`/data/chuserdata/killbackground`. Adding the package to the vendor whitelist keeps it alive. This needs root and a
writable `/chperm`:

```sh
adb -s $TV:5555 pull /chperm/preapp/killbackgroundwhitelist .        # back it up first
adb -s $TV:5555 shell 'mount -o remount,rw /chperm &&
  echo local.hdmiboot >> /chperm/preapp/killbackgroundwhitelist; sync; mount -o remount,ro /chperm'
```

Reboot the TV, reopen ADB, run the `am start` line again, and check
`dumpsys package local.hdmiboot | grep stopped` shows `stopped=false`.

Enable the accessibility service by appending it to the existing value. Read it first and keep it:

```sh
adb -s $TV:5555 shell settings get secure enabled_accessibility_services   # back this up
adb -s $TV:5555 shell "settings put secure enabled_accessibility_services '<old value>:local.hdmiboot/local.hdmiboot.HdmiAccessibilityService'"
adb -s $TV:5555 shell settings get secure enabled_accessibility_services   # read it back and compare
```

When you build shell strings, write `${OLD}:local...` with braces. In zsh, `$OLD:local` is read as the `:l`
(lowercase) modifier and silently corrupts the value.

The app writes a log to `/data/data/local.hdmiboot/files/log.txt`. Look for `armed`, `signal lost` and
`standby (HDMI signal lost)`. `am startservice -n local.hdmiboot/.LaunchService --es cmd probe` logs a read-only
check of the framework client.

## Risks

- **Do not call the TV's system service with guessed binder transaction numbers.** While exploring, one such call on
  the running service crashed it and the TV restarted. The app only uses the framework's own client classes.
- `/chperm` holds the TV's vendor configuration. Remounting it read-write and editing it can break the TV if you
  make a mistake. Back up the file you change, and restore the read-only mount afterwards.
- Enabling accessibility services changes a secure system setting. Always keep the old value so you can restore it.
- The power-off rule can switch the TV off by mistake: if the box loses its signal for more than 3 seconds
  (a reboot, a long resolution change, an unplugged cable) the TV goes to standby.
- Everything here was found on one firmware. Another TV, another firmware or another box can behave differently.
- Do not use this to bypass any activation, licensing or access control. The hotel configuration app on this TV asks
  for an activation code and was deliberately left alone.

## Undo

Uninstall `local.hdmiboot`, restore the saved `enabled_accessibility_services` value, and restore the original
`killbackgroundwhitelist`.

## License

MIT, see [LICENSE](LICENSE). Changhong and Xiaomi are trademarks of their owners. This project is not
affiliated with them, and contains no vendor code or firmware.
