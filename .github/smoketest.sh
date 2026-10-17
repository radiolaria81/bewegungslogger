#!/bin/bash
# Startet die App im Emulator, sammelt Abstürze und Screenshots.
set -x
PKG=de.radiolaria.bewegungslogger
mkdir -p out
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 6
adb exec-out screencap -p > out/1_erststart.png
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACTIVITY_RECOGNITION POST_NOTIFICATIONS ACCESS_BACKGROUND_LOCATION; do
  adb shell pm grant $PKG android.permission.$p
done
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity
sleep 4
adb exec-out screencap -p > out/2_mit_rechten.png
adb shell am start-foreground-service -n $PKG/.LoggerService
sleep 8
adb shell am start -W -n $PKG/.MainActivity
sleep 4
adb exec-out screencap -p > out/3_laeuft.png
adb logcat -d -b crash > out/crash.txt
adb logcat -d '*:W' > out/warn.txt
echo "===== CRASH-BUFFER ====="
cat out/crash.txt
echo "===== AndroidRuntime ====="
grep -A40 "AndroidRuntime" out/warn.txt | head -120
echo "===== ENDE ====="
