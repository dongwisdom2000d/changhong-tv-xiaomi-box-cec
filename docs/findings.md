# Findings

Everything below was observed on a Changhong 50J5500UH (Android 9) and a Xiaomi `MiTV-AZFU0` box (Android 14).
"Observed" means it was seen on the device. "Inferred" means it comes from reading the firmware, or from a guess
that fits the evidence. A command is given where you can check it yourself.

## 1. What happens when the Xiaomi remote powers the TV on

- Observed: the TV does not wake from a light sleep. `settings get global boot_count` increases by one,
  the TV is unreachable on the network while it is off and booting, and `adb` is closed again after boot.
- Observed: the box sends `<Text View On>` and `<Active Source>` over CEC. After the TV is up,
  `getprop switch.in.tv` is `E_INPUT_SOURCE_HDMI`, so the TV did select HDMI.
- Observed: the foreground activity at that moment is the hotel launcher `com.changhong.hotelv3/com.changhong.MainActivity`,
  not the HDMI player `com.changhong.tvap.atv.activity/.AtvMainActivity`. The launcher covers the picture.
- Observed: `am start -n com.changhong.tvap.atv.activity/.AtvMainActivity` brings the box picture back.
- Observed: `AtvMainActivity` has no LAUNCHER or LEANBACK category, so "launch an app on boot" apps cannot pick it.

## 2. HDMI-CEC behaviour

- The TV's CEC page (Changhong's own settings, not the Android TV settings) has four switches: HDMI CEC,
  TV auto power-on, device auto power-off, ARC. All were on. The box has a single "CEC control" switch.
- Observed: TV remote off -> the box goes to standby too. The TV's "device auto power-off" option is
  therefore the TV-to-device direction.
- Observed: box remote off -> the box sends `<Standby>` and the TV acknowledges it at the link level but does not
  switch off. On the box, `dumpsys hdmi_control` shows the CEC history:
  - default `power_control_mode = broadcast`: `[S] <Standby> 4F:36 (ACK)`
  - after `cmd hdmi_control cec_setting set power_control_mode to_tv`: `[S] <Standby> 40:36 (ACK)`
  The TV ignored both. The setting was set back to `broadcast` afterwards.
- Inferred: the Java layer of the TV's CEC code only surfaces two callbacks (source change, device name change).
  The standby handling is native, and the TV does not act on a standby request from a playback device.
- Inferred from the TV's own system app (not from a running trace): if the TV is in sleep (`sys.pm.sleep.flag=1`),
  a CEC source change is applied only when the CEC "auto power-on" option is enabled.

## 3. The firmware's background-app policy

- Observed: a third-party app is force-stopped shortly after it is launched. `dumpsys package <pkg>` then shows
  `stopped=true`, and `dumpsys activity broadcasts` shows `PACKAGE_RESTARTED` sent by `system_server`.
- Observed: launched apps are appended to `/data/chuserdata/killbackground`.
- Inferred from strings in `services.jar`: packages that are not system apps and not on
  `/chperm/preapp/killbackgroundwhitelist` are treated as "not protect app" and are killed and have their
  receivers prevented. After adding the package to the whitelist and rebooting, the process stayed alive and
  `stopped=false`.
- Observed, not explained: even then, manifest receivers were never delivered. An explicit broadcast to the
  receiver (with and without the package whitelisted), and the system's own `MY_PACKAGE_REPLACED`, never reached
  `onReceive`. In `dumpsys activity broadcasts` these records have `dispatchClockTime=1970-01-01`, which means no
  receiver was resolved. A `BOOT_COMPLETED` receiver was therefore not relied on.
- It was not tested whether the accessibility trigger also works without the whitelist entry.

## 4. Why an accessibility service is the boot trigger

- Observed: when an accessibility service is enabled in `enabled_accessibility_services`, the system binds it
  on every boot. The log shows it connecting about 4 seconds after boot, and the launcher window event about
  1 second later. That window event is what `tv-app` reacts to.
- Observed: the launcher window can appear several times within a few seconds of boot (five times in five
  seconds in one log), and its timing varies. A fixed schedule of retries was not enough: it pulled the user back
  from the TV desktop for up to about 50 seconds. The app therefore watches the window events and stops once the
  HDMI player has stayed in front for 6 seconds.
- A Xiaomi (Android 14) accessibility service installed the same way was removed from
  `enabled_accessibility_services` by the box after a sleep or reboot, so it is not a reliable trigger there.

## 5. Detecting that the box was switched off, and putting the TV into standby

- Observed: when the box is put to sleep, `ITVPlayer.isSignalStable()` changes from `true` (status 1) to `false`
  (status 0) within about a second. `TVManager.getSourceManager().getCurInputSourceAndScence()` names the current
  source (`E_INPUT_SOURCE_HDMI`).
- Both calls go through the Changhong framework client `com.changhong.tvos`, which is on the boot class path of
  this firmware (`echo $BOOTCLASSPATH`). An app reaches it by reflection:
  `TVManager.getInstance(ctx).getSystemManager().systemStandby(false)`. A read-only probe through the same client
  returned the real product name (`50J5500UH`), which shows the binder round trip works.
- Observed: `systemStandby(false)` puts the TV into standby the same way the power key does. The app logged
  `signal lost on E_INPUT_SOURCE_HDMI, waiting 8000 ms` (the grace was 8 s in that first test, now 3 s) followed 7 seconds later by `standby (HDMI signal lost)`,
  and the TV switched off.
- Observed: during boot the current source goes `E_INPUT_SOURCE_OTHER` (the TV's own desktop, no signal) -> `E_INPUT_SOURCE_HDMI`
  (no signal yet) -> `E_INPUT_SOURCE_HDMI` (stable). When the user goes to the TV desktop the source becomes
  `E_INPUT_SOURCE_OTHER` with `isSignalStable()` false. The watcher therefore only sees the box while HDMI is the
  current source. A box switched off from the desktop cannot be detected this way.
- Inferred from the firmware: `systemStandby(false)` ends in `sendPowerKey()`, and `systemStandby(true)` calls
  `enterPCSleep()`.
- Observed, and a warning: other system apps bundle client copies of the same interface with different transaction
  numbers (for example `chtvCommonApi` is 162 in one and 102 in another). Probing the running service by hand with
  `service call TVService <code>` using numbers taken from those copies returned `UNKNOWN_TRANSACTION`, and one
  call crashed the service, after which the TV restarted. Use the framework's own client instead of
  hand-written transaction numbers.

## 6. Things that did not work, or were left alone

- CEC `<Standby>` from the box (broadcast and addressed): ignored by the TV.
- Manifest `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` receivers: not delivered on this firmware.
- An accessibility app on the box, listening for the screen turning off and then asking the TV over the LAN to
  switch off: dropped, because the box removes it from the enabled list.
- The TV's hotel configuration app (`com.changhong.hotel.config`) has power-on source options but opens an
  activation-code page. It was not used and the activation was not bypassed.
- Android accessibility global actions (power dialog, lock screen) were not tried for power-off.

## 7. Handy commands

```sh
# TV
adb -s $TV:5555 shell settings get global boot_count
adb -s $TV:5555 shell getprop switch.in.tv
adb -s $TV:5555 shell dumpsys activity activities | grep mResumedActivity
adb -s $TV:5555 shell cat /data/chuserdata/killbackground
adb -s $TV:5555 shell cat /data/data/local.hdmiboot/files/log.txt
adb -s $TV:5555 shell am startservice -n local.hdmiboot/.LaunchService --es cmd probe
# Box (Android 14)
adb -s $BOX:5555 shell dumpsys hdmi_control          # CEC settings and message history
adb -s $BOX:5555 shell cmd hdmi_control cec_setting get power_control_mode
```
