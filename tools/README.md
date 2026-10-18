# Emulator fixtures

These scripts target only `emulator-5554` by default and use the marker
`LSB-FIXTURE-V1`. They add fictional contacts, SMS messages, and call-log rows
covering Unicode, multiline/long bodies, read/unread state, sent/inbox state,
incoming/outgoing/missed/rejected calls, and duplicate-restore keys.

```sh
./tools/seed_test_data.sh
./tools/verify_test_data.sh
./tools/clear_test_data.sh
```

Set `ANDROID_SERIAL` to another **disposable emulator** only when intentionally
changing the target. The cleanup script removes only rows belonging to this
fixture; it does not clear provider tables.

Restricted-message backup checks must use a disposable emulator: select
Restricted messages in the app, confirm that the schedule pauses, choose Libre
SMS Backup as the default SMS app when prompted, complete a manual backup, and
use the confirmation dialog to return to Android’s default-app settings.
