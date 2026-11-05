# FocusTask Companion — Native Android App

A personal productivity, smart alarm and step-tracking companion built with Kotlin and Android Jetpack. Everything stays on the device; the app has no network access.

## Features
1. **Study focus guardian**: during a focus session, a full-screen reminder appears over apps you haven't allowed. It re-appears after a delay if you keep using them. Phone, emergency, launcher and Settings are never blocked.
2. **Allowed apps**: choose the apps (for example a PDF reader or calculator) that never trigger the reminder.
3. **Smart alarms**: ring once, on weekdays or every day, with snooze. Use the system alarm sound or your own audio file, chosen through the Android file picker. Alarms survive reboots, app updates and time-zone changes.
4. **Voice callouts**: text-to-speech reads the alarm label aloud.
5. **Dismiss challenges**: solve a math problem or walk 15 steps (using the hardware step detector) to dismiss.
6. **Step tracker**: hardware step counter (`TYPE_STEP_COUNTER`) with daily steps, distance and calories, using very little battery.
7. **Ambient sounds**: generated ocean (brown noise), rain (pink noise) and white noise, or loop your own audio file.

**Not built yet:** the database already has tables for tasks and a weekly meal planner, but these have no screens. Study-buddy rooms are not implemented.

## First launch
The app's **Setup** section lists the permissions it still needs and opens each settings page in turn: notifications, physical activity, exact alarms, full-screen alarms, display over other apps and usage access.

## Building
- **GitHub Actions**: `.github/workflows/build-apk.yml` builds a debug APK with JDK 17 and Gradle 8.14.4, runs Android Lint, and uploads the APK and lint report as artifacts.
- **Locally**: install JDK 17 and Android Studio. On first open, generate the Gradle wrapper JAR with `gradle wrapper --gradle-version 8.14.4` (or let Android Studio do it), then commit `gradle/wrapper/gradle-wrapper.jar`.

Requirements: Android 8.0+ (minSdk 26), targets Android 16 (API 36).

See `security/SecurityAudit.md` for the security and privacy notes.
