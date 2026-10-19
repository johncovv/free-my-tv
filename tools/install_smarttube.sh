#!/bin/sh
# Installs (or updates) SmartTube, a lighter YouTube app, on the TV: the F-Droid build (app.smarttube.fdroid), built by
# F-Droid from SmartTube's public source code. F-Droid's signature on the APK is checked before anything is installed.
#   sh tools/install_smarttube.sh <TV_IP>
# Needs adb, curl and gpg (macOS: brew install android-platform-tools gnupg), and USB debugging on the TV.
# Updates come the same way (run this again): SmartTube's own in-app update is a different build and won't install
# over the F-Droid one.
set -e

[ -n "$1" ] || { echo "Usage: sh tools/install_smarttube.sh <TV_IP>" >&2; exit 1; }
case "$1" in *:*) TV=$1 ;; *) TV=$1:5555 ;; esac
PKG=app.smarttube.fdroid
FDROID_KEY=37D2C98789D8311948394E3E41E7044E1DBA2E89   # F-Droid's official signing key

for tool in adb curl gpg; do
  command -v $tool >/dev/null || { echo "$tool is missing (macOS: brew install android-platform-tools gnupg)." >&2; exit 1; }
done
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

CODE=$(curl -fsSL "https://f-droid.org/api/v1/packages/$PKG" | sed -n 's/.*"suggestedVersionCode":\([0-9]*\).*/\1/p')
[ -n "$CODE" ] || { echo "Couldn't reach F-Droid. Try again later." >&2; exit 1; }

adb connect "$TV" >/dev/null 2>&1 || true
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || {
  echo "Can't reach $TV. Check the TV is on, on the same Wi-Fi, with USB debugging on (and the prompt accepted)." >&2; exit 1; }

CUR=$(adb -s "$TV" shell dumpsys package $PKG | tr -d '\r' | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
if [ "$CUR" = "$CODE" ]; then
  echo "SmartTube is already F-Droid's latest ($CODE)."
  exit 0
fi
echo "On the TV: ${CUR:-not installed} · on F-Droid: $CODE"

echo "Downloading and checking F-Droid's signature…"
curl -fsSL -o "$WORK/app.apk" "https://f-droid.org/repo/${PKG}_${CODE}.apk"
curl -fsSL -o "$WORK/app.apk.asc" "https://f-droid.org/repo/${PKG}_${CODE}.apk.asc"
curl -fsSL -o "$WORK/fdroid.asc" "https://keys.openpgp.org/vks/v1/by-fingerprint/$FDROID_KEY"
export GNUPGHOME="$WORK/gnupg"
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --import "$WORK/fdroid.asc" 2>/dev/null
gpg --batch --status-fd 1 --verify "$WORK/app.apk.asc" "$WORK/app.apk" 2>/dev/null | grep -q "VALIDSIG.* $FDROID_KEY\$" \
  || { echo "The signature does NOT match. Nothing was installed." >&2; exit 1; }
echo "F-Droid's signature matches."

adb -s "$TV" install -r "$WORK/app.apk" >/dev/null
echo "Done: SmartTube $CODE is on the TV. It shows on the shelf as \"YouTube lite\" (put smarttube in shelf=)."
