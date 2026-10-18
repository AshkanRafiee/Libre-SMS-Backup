# Libre SMS Backup privacy policy

**Effective date: 2026-10-18**

Libre SMS Backup is local-first software. It does not operate an account,
analytics service, advertising service, cloud backend, or network upload path.
The app does not request the Android `INTERNET` permission.

## Data handled on the device

When you start a backup or restore, Android may grant the app access to the
SMS, MMS, contacts, and call-log providers needed for the operation. The app
uses that data on the device to create or restore the archive you selected. It
does not send message bodies, phone numbers, contacts, call records, passwords,
or archive contents to the project or any third party.

You choose the backup destination through Android's Storage Access Framework.
That destination may be local storage or a cloud-backed document provider. Any
syncing, retention, or access provided by that provider is controlled by the
provider and your Android account, not by Libre SMS Backup.

## Passwords and encryption

Encryption is optional and off by default. When enabled, the password is used
to protect archives and an Android Keystore-wrapped value may be retained on
the device so scheduled backups can run. The password is not stored as plain
text. The project cannot recover a forgotten password or encrypted archive.

## Default SMS role

Android may require Libre SMS Backup to be the default SMS app temporarily for
restricted-message backups or SMS restore. The app explains this transition
and guides you back to your preferred SMS app. While the app temporarily holds
that role, incoming MMS delivery is not handled by Libre SMS Backup.

## Retention and deletion

Archives remain wherever you chose to save them until you delete them or the
configured retention policy removes older backup sets. The app does not
silently upload archives. You can remove app data through
Android Settings; this removes local app preferences and the locally retained
Keystore-wrapped password, but does not automatically delete files in a
Storage Access Framework destination.

## Contact

For privacy questions, contact `libresmsbackup.eject095@passmail.net`.
