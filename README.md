# FCM Keep Alive

Android app for auto-switching default IME:
- Screen off -> Gboard
- User present (unlock) -> WeChat IME

## Build
1. Open this folder in Android Studio (Jellyfish or newer).
2. Let Android Studio sync Gradle.
3. Build and install on your device.

## First-time setup on device
1. Enable both Gboard and WeChat keyboard in Android input settings.
2. In app, choose WeChat IME ID.
3. Gboard IME ID is fixed to `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`.

## Notes
- Auto switching default IME requires `WRITE_SECURE_SETTINGS`.
- Foreground notification is required for reliable background behavior.
- Service auto-restores after reboot.

## Whitelist
- Use the list from `adb shell dumpsys deviceidle whitelist` to set FCM apps to `No restrictions` in HyperOS battery saver settings.
- Use an Activity to launch the Stock Android battery settings and set FCM apps to `Unrestricted`

## Battery Saver
- No restrictions `adb shell dumpsys activity service com.miui.powerkeeper | findstr "scenario:8"`
- Apps in the list must be set to No restrictions

## adb
`adb shell settings get secure default_input_method`
`adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService | select -f 10`
