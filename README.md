# Shekel Sense — Android Companion

Native Android shell for [Shekel Sense](https://ohrmich-png.github.io/shekel-sense/)
(the budgeting PWA). Two jobs:

1. **Runs the PWA** — a [Capacitor](https://capacitorjs.com/) WebView pointed at
   `https://ohrmich-png.github.io/shekel-sense/`. The GitHub Pages site stays the
   single working codebase; this repo holds only the native shell + plugin.
2. **Captures expenses from push notifications** — a `NotificationListenerService`
   watches notifications from Israeli banks / card companies, parses the amount
   and merchant, and files the expense automatically (or queues it for review).

## Privacy

- The listener **ignores every package outside the allowlist** before reading any
  text — WhatsApp, SMS, everything else is never touched.
- Parsing happens **on-device**. Nothing is uploaded anywhere, ever.
- Low-confidence parses are never auto-filed: they go to a review inbox
  ("תיבת קליטה") in the app's settings.

## Sideload install (personal use)

1. On your phone: Settings → Security → allow **Install unknown apps** for your
   browser / file manager.
2. Copy `app-debug.apk` to the phone and tap it to install.
3. Open **Shekel Sense** → Settings (הגדרות) → **קליטת התראות** →
   **מתן גישה להתראות**, then enable **Shekel Sense** in the system
   notification-access list.
4. Make a card purchase — the expense should appear automatically (toast:
   "נוספה הוצאה"). Anything the parser isn't sure about lands in the review
   inbox in the same settings section: one tap adds it as an expense, or dismiss.

## Building

Prerequisites: Node 20+, JDK 17, Android SDK (platform 34, build-tools).

```bash
npm install
npx cap sync android
cd android && ./gradlew assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your SDK path first.
The Gradle wrapper downloads itself on first run.

## Project layout

```
capacitor.config.json   # appId png.ohrmich.shekelsense; server.url -> GitHub Pages PWA
www/                    # placeholder web dir (remote URL is loaded instead)
android/                # Capacitor Android project
android/app/src/main/java/png/ohrmich/shekelsense/notificationcapture/
  NotificationCaptureService.java  # NotificationListenerService + allowlist filter
  NotificationCapturePlugin.java   # Capacitor bridge (events + settings intents)
  NotificationParser.java          # pure-logic parser: allowlist, per-issuer
                                   # patterns, amount/merchant/card extraction
tools/parser-test/      # node mirror of the parser pattern table + sample texts
```

## Parser notes

- Confidence policy: a notification is only auto-filed when **both** amount and
  merchant are extracted. Everything else → review inbox.
- Skipped outright (never filed): incoming transfers (קיבלת/זוכה…), refunds,
  voids, declined charges.
- Dedup: a detected expense is skipped if the same merchant + amount already
  exists within ±1 day (any source) — no double-counting with Gmail imports.
- The allowlist covers Bank Leumi, Hapoalim, Mizrahi-Tefahot, Discount (+Business),
  FIBI, Yahav, Pepper, ONE ZERO, Isracard (+Business), Cal, Max, bit, PayBox —
  package names verified against Play Store listings. Unknown packages are
  logged-and-skipped, never parsed.

## Signing a release

The debug APK is signed with the SDK debug key (fine for personal sideloading).
For wider distribution, generate a release keystore and add a `signingConfigs`
block in `android/app/build.gradle` — standard Gradle flow.
