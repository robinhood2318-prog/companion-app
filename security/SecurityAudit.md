# Security & Privacy Notes

**App:** FocusTask Companion (native Android, Kotlin)
**Reference:** OWASP Mobile Top 10 and Google Play policy
**Last reviewed:** 2026-11-05

This file describes what the code actually does. Update it whenever a feature changes.

## Network and data sharing
- The app has **no network access**: it doesn't declare the `INTERNET` permission and contains no networking code, analytics or ads SDKs.
- No data leaves the device. There are no accounts, sync or shared study rooms.

## Data storage
- Tasks, alarms, the allowed-apps list, meals and daily step totals are stored in a private Room (SQLite) database (`focus_task_companion.db`) inside the app sandbox.
- Small state values (step counter baseline, focus session state) are stored in private SharedPreferences.
- The database is **not encrypted**. It relies on Android's app sandbox and file-based encryption.
- Backups: `android:allowBackup="false"`, and `data_extraction_rules.xml` excludes all data from cloud backup and device-to-device transfer on Android 12+.
- Debug builds (`.debug` suffix, built by CI) are debuggable, so `adb run-as` can read their data. Only distribute signed release builds.

## SQL injection
- All queries are Room `@Query` methods with bound parameters. There is no string-built SQL.

## Components and intents
- `AlarmAlertActivity`, `AlarmReceiver` and all services are `exported="false"`.
- `AlarmReceiver` has no intent filter and is only triggered by the app's own `PendingIntent`s.
- `BootReceiver` is exported for system broadcasts and requires senders to hold `RECEIVE_BOOT_COMPLETED`. Its only effect is re-scheduling the user's existing alarms.
- Every `PendingIntent` uses `FLAG_IMMUTABLE`.
- Alarm details are read from the database by alarm ID instead of being trusted from intent extras.

## User files (custom alarm and ambient sounds)
- Files are chosen with the Storage Access Framework (`ACTION_OPEN_DOCUMENT`). The app keeps read access with `takePersistableUriPermission` and releases it when no alarm uses the file any more.
- No broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`) are requested.
- If a custom sound can't be played, alarms fall back to the system alarm, ringtone or notification sound so they never ring silently.

## Safety of the focus blocker
- The overlay is never shown over phone, dialer, emergency, launcher, Settings, system UI or permission screens, so calls and emergency services stay reachable.
- The user can always close it with the overlay's own buttons or the notification's "End session" action.

## Sensitive permissions (need a justification in the Play Console)
| Permission | Why it's needed |
|---|---|
| `PACKAGE_USAGE_STATS` | Detects the foreground app during a focus session the user starts. |
| `SYSTEM_ALERT_WINDOW` | Shows the focus reminder over distracting apps. |
| `SCHEDULE_EXACT_ALARM` | User-granted; alarms ring on time. Falls back to inexact alarms if denied. |
| `USE_FULL_SCREEN_INTENT` | Shows the alarm screen. On Android 14+ the user may need to allow it. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Focus session service (subtype is declared in the manifest). |
| `FOREGROUND_SERVICE_HEALTH` + `ACTIVITY_RECOGNITION` | Step counting. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Ambient sounds and the ringing alarm. |

Removed: `USE_EXACT_ALARM` (Play limits it to dedicated alarm or calendar apps) and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (unused, and restricted by policy).

## Release checklist
- [ ] Commit `gradle/wrapper/gradle-wrapper.jar`, generated with `gradle wrapper` (CI generates it if it's missing).
- [ ] Add release signing from CI secrets; never commit the keystore.
- [ ] Fill in the Play data-safety form: no data collected or shared.
- [ ] Review the Android Lint report from CI.
