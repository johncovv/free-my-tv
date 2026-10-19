---
description: Set up Free My TV from scratch, step by step - tools, the TV connection, settings, keys, build and install
---

Set up Free My TV for the person in front of you, from nothing to the new home screen on their TV. Follow
[CLAUDE.md](../../CLAUDE.md) ("Installing it for someone", "Always"); this is the same flow, one step at a time.

How to work:

- Speak the person's language (they may write in Portuguese). They may not be technical: short steps, one at a time.
- Before anything that changes their computer or their TV, say what you're about to do. They do the taps on the TV.
- Check what's already done and skip it (tools installed, `config.properties` filled, TV already connected, app
  already installed). If they're re-running `/setup` to change one thing, just do that thing.
- Keep a short checklist of the steps below and say where you are.

Secrets:

- The TMDB key, the Spotify Client ID and the Spotify token are theirs. They paste the key and the Client ID into
  `config.properties` themselves (open the file in their editor for them). Don't ask for them in the chat; if they
  paste one anyway, put it only in `config.properties`. Never print, echo, log or commit any of them.
- To check a value is filled, look at its length, never its content.
- Never test the Spotify token from the computer: Spotify replaces it on every use, so a test logs the TV out.
- `config.properties` and `freemytv.keystore` are git-ignored: keep it that way.

## 1. Tools

A JDK 17+, the Android SDK command-line tools (build-tools 34, platforms android-34) and adb. Check `java -version`,
`$ANDROID_HOME` (or `/opt/homebrew/share/android-commandlinetools`) and `adb version`; install only what's missing
(see CLAUDE.md for macOS, Linux and Windows). On macOS, Homebrew's `openjdk@17` works well; point `JAVA_HOME` at it
for `build.sh` and `sdkmanager`.

## 2. The TV

1. Ask which TV (brand, model) and walk them through developer mode: Settings → System (or Device Preferences) →
   About → press *Build* 7 times; then Developer options → **USB debugging** on.
2. Ask for the TV's IP address (Settings → Network & Internet), or help them find it.
3. `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: they tick *Always allow* and accept. Wait for
   `adb -s <IP>:5555 get-state` to say `device`.
4. Read the TV, without changing anything: `getprop ro.product.brand`, `ro.product.model`,
   `ro.build.version.release` (needs Android 11+). If it isn't a TCL or a Google TV, see "Adapting it to another TV"
   in CLAUDE.md before installing.

## 3. Settings (`config.properties`)

Run `./build.sh` once if `config.properties` doesn't exist (it creates it). Then, asking as you go:

- **City:** `latitude`, `longitude` (decimal degrees; look them up for them) and `city` (the name on the weather card).
- **Country and units:** `tmdb_region` (two letters), `currency` (`BRL`, `USD`, `EUR`…), `temperature`, `language`.
- **Look:** `theme` (`auto` follows the sunset, `dark`, `light`).
- **Shelf:** which streaming apps they use, in order (`shelf`; the keys are listed in the file). Check which are
  installed: `adb shell pm list packages`.

## 3b. YouTube or SmartTube

Ask which they want on the shelf, and explain the choice plainly:

- **YouTube** (the official app, `com.google.android.youtube.tv`): what the TV already has; their account, their
  subscriptions, updated by the Play Store; ads included.
- **SmartTube** (shown as "YouTube lite"): an independent, open-source YouTube app for TVs. Lighter on old TVs, no
  ads, SponsorBlock built in; they can sign in to their YouTube account inside it. Not from Google and not in the Play
  Store: this installs F-Droid's build (made by F-Droid from SmartTube's public code), checking F-Droid's signature
  first. Updates: run the same script again (SmartTube's own update offer is a different build and won't install
  over it). If YouTube changes something, it can stop playing until an update.

If they choose SmartTube and say yes: `sh tools/install_smarttube.sh <IP>` (needs `gpg`: `brew install gnupg` on
macOS), then put `smarttube` first in `shelf` (and drop `youtube`, unless they want both). If they choose YouTube,
keep `youtube` in `shelf` and don't install anything.

## 4. TMDB key (the picks, Explore, the title pages)

Free: an account at themoviedb.org, then Settings → API → request a developer key ("Personal" use). They paste the
*API Read Access Token* (or the *API Key*) after `tmdb_key=` themselves. Without it everything else works, but there
are no picks, no Explore and no title pages.

## 5. Optional: Your Taste

Ask for a few movies or shows they love and the genres they like; fill `taste_titles` and `taste_genres`
(comma-separated; genres in Portuguese or English). This adds the "Your Taste" tab, which the home opens on, and the
screensaver shows it. Skip if they don't want it.

## 6. Optional: Spotify (Music screen and tab)

Only with Spotify Premium (Spotify's rule for personal apps). If they want it:

1. At developer.spotify.com/dashboard: **Create app**, name "Free My TV", Redirect URI
   `http://127.0.0.1:8888/callback` (click *Add*), tick **Web API**, save.
2. They copy the **Client ID** (not the secret) after `spotify_client_id=` in `config.properties`.
3. Run `python3 -I tools/spotify_login.py`. It opens the browser; they authorize (read-only). It writes
   `spotify_refresh_token` and prints nothing secret.

## 7. Build and install

1. `./build.sh` must end with `Built FreeMyTV.apk`. Tell them to keep `freemytv.keystore` (updates need it).
2. `adb install -r FreeMyTV.apk`.
3. **TCL TVs:** `adb shell appops set io.freemytv APP_AUTO_START allow` (otherwise the switch snaps back to Original).
4. Tell them what the switch does (CLAUDE.md, step 7), then they switch on the TV: *Free My TV* → *Home Screen* →
   *Free My TV*, and accept the debugging prompt once more (*Always allow*).
5. Check `adb logcat -s FreeMyTV` and that Home now opens Free My TV (`cmd package resolve-activity --brief -a
   android.intent.action.MAIN -c android.intent.category.HOME` ends in `io.freemytv/.HomeActivity`).

## 8. Screensaver (optional)

The screensaver starts with Back on the home screen. To have it start after a few idle minutes, ask how many, then
(with their OK): `adb shell settings put secure screensaver_enabled 1` and
`adb shell settings put system screen_off_timeout <minutes × 60000>`.

## 9. Wrap up

Say what's set up, how to go back (*Free My TV* → *Home Screen* → *Original*), and that `/setup` can be run again to
change anything. Ask them to keep USB debugging on: the app uses it to switch modes, to check things after the TV
boots and for its safety net (back to the original home screen if it ever crashes three times).
