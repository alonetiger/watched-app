# watched (Android)

A native Android shell for **https://watched.cx** that plays the site's video
inside the app and uses the site's own look.

## What it does

- Opens `https://watched.cx/home` in a full-screen WebView and keeps every
  `watched.cx` link inside the app (tapping a `watched.cx` link anywhere on the
  phone also opens the app).
- Plays the site's own player in-app. Tapping the player's fullscreen button
  switches to a native fullscreen surface: landscape, immersive (status/nav bars
  hidden), screen kept awake. Back exits fullscreen first, then walks history.
- Picture-in-picture: press Home while a video is fullscreen and it keeps
  playing in a floating window (Android 8+).
- Theme matches the site's `:root` tokens: background `#0a0a0a`, surfaces
  `#131313 / #1b1b1b`, text `#f5f5f5`, Netflix-red accent `#e50914 / #ff4a55`,
  pill buttons, glass cards. Status bar, navigation bar, splash screen, loading
  bar and launcher icon all use them. The launcher icon is the site's icon on
  the site's background.
- Offline page in the same style with a Retry button when the site can't be
  reached.
- Pop-ups are disabled and scripted redirects off the site are dropped. Links a
  user taps that lead off `watched.cx` open in the system browser instead.

## Project layout

```
app/src/main/java/cx/watched/app/MainActivity.kt   the whole app
app/src/main/res/values/colors.xml                 site colour tokens
app/src/main/res/values/themes.xml                 app + splash themes
app/src/main/assets/offline.html                   offline page
keystore/watched-release.jks + keystore.properties release signing key
build.ps1                                          one-click build
```

## Build

Toolchain (JDK 17, Gradle 8.11.1, Android SDK 35) lives in
`E:\YOUTUBE AUTOMATION\_tools`. From this folder:

```powershell
.\build.ps1
```

APKs land in `app\build\outputs\apk\release\app-release.apk` (signed, minified)
and `app\build\outputs\apk\debug\app-debug.apk`.

Install on a phone: copy the release APK over and open it (allow installs from
unknown sources), or with a USB cable:

```powershell
& "E:\YOUTUBE AUTOMATION\_tools\android-sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

Keep `keystore/` and `keystore.properties` safe: updates must be signed with the
same key to install over an existing copy.

## Not included

There is no video download / save-locally feature. The site streams third-party
films and shows without a licence to redistribute them, so a built-in ripper
for its streams was left out on purpose.
