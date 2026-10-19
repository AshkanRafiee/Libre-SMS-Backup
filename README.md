# Libre SMS Backup

Libre SMS Backup is a free, open-source Android app for keeping SMS messages and call logs under your control.

- **Local-first:** no account, cloud sync, analytics, tracker, or Internet permission.
- **Optional encryption:** enable encryption and choose a password to protect `.lsb.enc` archives with AES-256-GCM and PBKDF2-HMAC-SHA256; unencrypted archives use `.lsb`.
- **Lossless enough to travel:** SMS, MMS, and call data are captured in a versioned JSON payload instead of being flattened into a chat transcript; RCS capture is best effort when the device exposes a compatible provider.
- **Safe restore:** encrypted archives are authenticated before any phone data is changed; explicitly unencrypted archives are clearly identified, and existing records are skipped instead of duplicated.
- **Comfortable to use:** choose the system, light, or dark theme and app language from the Display menu.
- **Scheduled visibility:** completion notifications show how many SMS and call records were saved, including MMS and RCS when present.
- **Controlled storage:** manual and scheduled backups share one SAF backup location with configurable retention.
- **FLOSS:** released under GPL-3.0-only.

The app never stores your backup password as plain text. When encryption is enabled, the password is wrapped by Android Keystore so manual and scheduled backups can run without asking every time. If it is forgotten, an encrypted archive cannot be recovered.

## Build

Requirements: Android SDK 36 and JDK 17.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

The shipped app has no third-party runtime dependencies. AndroidX is used only by instrumented tests.

Run the format tests with the project’s Android test runner. Tests should use a disposable emulator because provider tests will read and, later, restore phone records.

## Privacy boundary

SMS, MMS, contacts, and call-log permissions expose extremely sensitive data. Libre SMS Backup processes that data locally and does not request `INTERNET`, upload it, or log message bodies, phone numbers, contacts, or call details. A chosen Storage Access Framework destination may itself be cloud-backed; its syncing and retention are controlled by that provider. Read the [privacy policy](PRIVACY.md) for the full boundary.
