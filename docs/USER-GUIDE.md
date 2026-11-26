# Libre SMS Backup user guide

## Make your first backup

1. Open **Backup content** and choose messages, call logs, or both. Android asks for access to the records you select. Libre SMS Backup does not ask for Contacts access.
2. Choose a **Backup location**. Manual and scheduled backups use the same folder. Pick local storage for offline use; a cloud-backed folder can sync through its provider even though the app has no internet permission.
3. For password protection, turn on **Encryption** before making a backup. Tap **Back up now** and check the result.

### What gets saved

- **Messages:** SMS and any MMS the device exposes. Attachments the device lists but the app cannot read, or that are larger than 64 MiB, are left out and counted in the backup result. MMS with missing attachments may not restore. The app cannot count attachments the device does not list.
- **Call logs:** Call-history entries, not audio recordings.
- **RCS:** Only records exposed by a compatible provider. This is not a full backup of your messaging app. Compatible RCS text can be restored **as SMS**, not as native RCS conversations; RCS attachments are not restored.
- **Restricted messages:** Android may hide some messages unless the backup app temporarily holds the default SMS role. This is a separate option for manual backups, not a guarantee that all RCS records are readable.

## Schedule backups and manage old copies

Choose a daily, weekly, or monthly schedule. Each run uses your current content and encryption settings. Android's **Alarms & reminders** access can help backups run at the chosen time. Battery and background restrictions can delay or skip a run. Optional notifications show what each scheduled backup saved.

Allow notifications for Libre SMS Backup in Android settings to see scheduled failures and attachment omissions without opening the app. You can also see the latest backup result on the app’s home screen.

Selecting **Restricted messages** turns off scheduled backups. When you deselect it, the app can restore the previous schedule. Check the next run after changing this setting or moving the backup folder.

**Backup retention** controls what the app keeps in your selected folder. Keep the newest sets (five by default), keep all sets, or choose separate day, week, and month windows. With a 7-day, 4-week, 3-month policy, the app keeps the newest available backup in each covered day, week, and month. These periods can overlap and empty periods don't create copies. After a successful backup, the app tries to remove matching archives outside the policy. Keep any archive you want to preserve separately in another folder.

## Passwords and archives

Each backup is a versioned Libre SMS Backup archive. An unencrypted `.lsb` can be read by anyone with access to it. An encrypted `.lsb.enc` uses AES-256-GCM with a key derived from your password. Android Keystore protects the password saved on the device for unattended backups.

Encryption is off by default. Turning it on or off only affects future backups. Keep the original password separately from the archive: a different phone cannot retrieve it for you, and the project cannot recover it. Clearing app data removes the app's settings and saved password but does not remove the archives from your chosen folder.

Very large backups can exceed the app's size limit. The memory available to the app limits creation of password-protected backups and, on some Android versions, also limits restoring them. Restoring a large backup needs free storage for the temporary data used during the check and import. If a backup is too large to create, try backing up messages and call logs separately. If an existing archive is too large to restore, keep the original file.

An unencrypted backup can contain up to 2 GiB of backup data. For encrypted backup creation the limit is one quarter of the memory Android allows the app to use, up to 512 MiB. A backup also fails if a single field exceeds 4 MiB once encoded, a record exceeds 8 MiB or 1,024 fields, or an MMS has more than 8 MiB of information apart from its attachment files or more than 4,096 parts and address entries combined. These limits do not cap the total number of messages or calls. If a record exceeds a limit, no new backup is saved.

## Restore or move to a new phone

1. Before resetting or replacing your phone, copy a recent archive somewhere you can reach without it. Keep the password if the archive is encrypted.
2. Install Libre SMS Backup on the destination phone, open **Restore from backup**, and choose the archive. Enter its password if prompted. The app authenticates an encrypted archive before writing records and warns you if the archive is not encrypted.
3. Review the preview and choose messages, calls, or both. Android may require the default SMS role for message restore and write access for restoring call logs. Matching records are skipped; check the final summary for anything that could not be restored.

An MMS is reported as unrestored if its attachment data is missing or its incoming group conversation cannot be recreated. Backups can preserve group conversation information when the source phone makes it available. Keep the original archive if any messages could not be restored.

**Switch back to your regular SMS app when done.** Libre SMS Backup does not handle incoming MMS while it is the default SMS app. Avoid the temporary switch when you're expecting MMS. The app guides you back to Android's default-app settings.

## Display, help, and privacy

Use **Display** to choose the app language and system, light, or dark theme. From **About**, you can create a problem report and choose whether to add technical diagnostics. Diagnostics do not include messages or call details but can include device and build information. Review the report before sharing; nothing is sent automatically. The [privacy policy](../PRIVACY.md) explains what stays on the device and what a provider or sharing app can access.
