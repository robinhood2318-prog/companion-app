# FocusTask Companion — Native Android App

A personal productivity, smart alarm and step-tracking companion built with Kotlin and Android Jetpack. Everything stays on the device; the app has no network access.

## Features
Bottom tabs: **Home · Focus · Calendar · Alarms · More**

1. **Home dashboard**: greeting, today's primary focus, next deadline, next alarm, steps, tonight's dinner and ambient sound at a glance.
2. **Tasks & Focus**: Pomodoro timer (15–90 min) that runs the distraction blocker, plus a prioritised task list.
3. **Calendar & deadlines**: month view with coloured dots. Add a deadline (e.g. "Finish Anatomy syllabus", 12 chapters) and it shows countdowns ("7 days left"), progress and the daily pace needed. Reminders arrive at 9 AM the day before and on the due day.
4. **Screen reminder settings**: full-screen or banner style, pop-up frequency, grace period, snooze length, strict mode, custom message, opacity, vibration, and what "Back to task" opens.
5. **Smart alarms**: once, weekdays or every day; math or walking dismiss challenges; snooze; custom sound.
6. **Voice (text-to-speech) per section**: a speaker button on each tab (Focus, Calendar, Alarms, Fitness, Menu) turns its voice on or off; all switches are also under More.
7. **Walk & Fitness** (More): step ring with your own goal, distance, calories and a 7-day chart.
8. **Weekly Cooking Menu** (More): plan meals with cooking reminders; "Takeout tonight?" skips today's dinner reminder.
9. **Ambient Soundscapes** (More): beach waves, forest birds, gentle rain and white noise, all generated on the phone, or your own audio file.

**Not included:** Study Buddy rooms (they would need internet, accounts and a server; the app currently keeps everything on the phone).

## First launch
The app's **Setup** section lists the permissions it still needs and opens each settings page in turn: notifications, physical activity, exact alarms, full-screen alarms, display over other apps and usage access.

## Building
- **GitHub Actions**: `.github/workflows/build-apk.yml` builds a debug APK with JDK 17 and Gradle 8.14.4, runs Android Lint, and uploads the APK and lint report as artifacts.
- **Locally**: install JDK 17 and Android Studio. On first open, generate the Gradle wrapper JAR with `gradle wrapper --gradle-version 8.14.4` (or let Android Studio do it), then commit `gradle/wrapper/gradle-wrapper.jar`.

Requirements: Android 8.0+ (minSdk 26), targets Android 16 (API 36).

See `security/SecurityAudit.md` for the security and privacy notes.
