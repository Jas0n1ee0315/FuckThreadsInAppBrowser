# FuckThreadsInAppBrowser

LSPosed module (libxposed API 102) for Threads (`com.instagram.barcelona`): links open in the device's default browser instead of the Threads in-app browser.

讓 Threads 點擊連結時改用裝置預設瀏覽器開啟，而不是 Threads 內建瀏覽器。

## How it works

Threads opens links in two ways; both are redirected:

1. **Immersive in-app browser** (current builds): a link tap calls a static helper that builds the Compose route `immersive_browser/{encodedUrl}?postId=...` and navigates to it inside the main Activity. The helper's obfuscated name changes per release, so it is located with [DexKit](https://github.com/LuckyPray/DexKit) by its route strings and the result is cached per Threads APK (`<threads data>/cache/ftiab_route_method.txt`). The hook opens the browser and skips the navigation.
2. **Classic BrowserLite Activity** (`ThreadsBrowserLiteActivity` etc.): `Instrumentation.execStartActivit*` is hooked and the launch intent is replaced with a VIEW intent to the default browser.

Link shims (`l.threads.com/?u=...`, `l.instagram.com`, `l.facebook.com`) are unwrapped first. Links to Meta-owned sites (threads.com, instagram.com, facebook.com, meta.com, ...) stay in the in-app browser because help-center / account flows need the app session. If no default browser is set, a chooser is shown.

## Usage

1. Install the APK, enable the module in LSPosed and select Threads.
2. Force-stop Threads once. Later module updates hot-reload (`autoHotReload=true`) without restarting Threads.

Logs: LSPosed module log, tag `FuckThreadsIAB`.

## Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (signed with the debug key unless you configure your own signing).

Tested on Threads 455.0.0.55.72, LSPosed 2.2.1 (API 102), Android 17.
