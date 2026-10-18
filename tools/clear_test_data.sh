#!/bin/sh
set -eu

DEVICE="${ANDROID_SERIAL:-emulator-5554}"
ADB="adb -s $DEVICE"
MARKER="LSB-FIXTURE-V1"

adb_cmd() { $ADB shell "$*"; }

echo "Removing only $MARKER rows from $DEVICE"

adb_cmd cmd appops set com.android.shell WRITE_SMS allow
adb_cmd cmd appops set com.android.shell WRITE_CALL_LOG allow
adb_cmd cmd appops set com.android.shell WRITE_CONTACTS allow

# Delete SMS and call fixtures by marker/value, never by clearing a whole table.
$ADB shell "content delete --uri content://sms --where \"body LIKE '%$MARKER%'\""
$ADB shell "content delete --uri content://call_log/calls --where \"name LIKE '%$MARKER%'\""

# Remove fixture contact data first, then its raw contacts. The account name and
# type are unique to this fixture and do not match normal device contacts.
$ADB shell "content delete --uri content://com.android.contacts/data --where \"raw_contact_id IN (SELECT _id FROM raw_contacts WHERE account_name='lsb-fixture-v1' AND account_type='com.example.lsb.fixture')\""
$ADB shell "content delete --uri content://com.android.contacts/raw_contacts --where \"account_name='lsb-fixture-v1' AND account_type='com.example.lsb.fixture'\""

echo "Fixture cleanup complete"
