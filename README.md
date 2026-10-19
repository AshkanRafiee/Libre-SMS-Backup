# Libre SMS Backup

Libre SMS Backup is a free, open-source Android app for keeping your SMS, MMS, RCS, and call records under your control. It supports Android 8.0 (API 26) and newer.

- **Local-first:** no account, cloud sync, analytics, tracker, or Internet permission.
- **Flexible backup scope:** choose SMS and available MMS/RCS records, call logs, or both. Restricted messages have a separate option for cases where Android permits access.
- **Automatic backups:** schedule daily, weekly, or monthly backups. They reuse your selected content and current encryption setting, and completion notifications report what each run saved.
- **Retention choices:** keep the newest N backup sets, keep all of them, or keep recent backups by daily, weekly, and monthly age tiers.
- **Portable archives:** records are stored together in a versioned archive instead of being flattened into a chat transcript; RCS capture is best effort when the device exposes a compatible provider.
- **Safe restore:** encrypted archives are authenticated before any phone data is changed, unencrypted archives are clearly identified, and existing records are skipped instead of duplicated.
- **Optional encryption:** enable encryption and choose a password to protect `.lsb.enc` archives with AES-256-GCM and PBKDF2-HMAC-SHA256; unencrypted archives use `.lsb`.
- **Comfortable to use:** choose the system, light, or dark theme and app language from the Display menu.
- **Controlled storage:** manual and scheduled backups share one Storage Access Framework location.
- **FLOSS:** released under GPL-3.0-only.

The app never stores your backup password as plain text. When encryption is enabled, the password is wrapped by Android Keystore so manual and scheduled backups can run without asking every time. If it is forgotten, an encrypted archive cannot be recovered.

## Important Android notes

- Exact schedule timing may require Android's exact-alarm access, and battery optimization can delay or skip background backups.
- Restricted messages are often hidden or encrypted RCS records. Android may require the app to become the default SMS app temporarily; selecting them turns off scheduled backups until they are deselected.
- SMS/MMS restore may also require the temporary default SMS role. While the app holds that role, avoid receiving new MMS because it does not handle incoming MMS delivery; the app guides you back to your previous SMS app afterward.

## Build

Requirements: Android SDK 36 and JDK 17.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The shipped app has no third-party runtime dependencies. AndroidX is used only by instrumented tests.

Run the instrumented test suite with the project’s Android test runner on a disposable emulator. It covers archive encryption, restore parsing, scheduling, retention, permissions, locales, and backup summaries.

## Privacy boundary

SMS, MMS, contacts, and call-log permissions expose extremely sensitive data. Libre SMS Backup processes that data locally and does not request `INTERNET`, upload it, or log message bodies, phone numbers, contacts, or call details. The app's own settings and password are excluded from Android's automatic device and cloud backup. A chosen Storage Access Framework destination may itself be cloud-backed; its syncing and retention are controlled by that provider. Read the [privacy policy](PRIVACY.md) for the full boundary.
