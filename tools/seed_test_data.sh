#!/bin/sh
set -eu

# This fixture is intentionally restricted to the disposable emulator. It never
# clears provider tables and only creates rows carrying the LSB-FIXTURE-V1 marker.
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
ADB="adb -s $DEVICE"
MARKER="LSB-FIXTURE-V1"

adb_cmd() { $ADB shell "$*"; }
insert() { adb_cmd content insert "$@" 2>&1; }

echo "Seeding $MARKER on $DEVICE"

# The shell identity is used only on the disposable emulator for fixture setup.
adb_cmd cmd appops set com.android.shell WRITE_SMS allow
adb_cmd cmd appops set com.android.shell WRITE_CALL_LOG allow
adb_cmd cmd appops set com.android.shell WRITE_CONTACTS allow

# Two contacts share the numbers used below so SMS/call association behavior is
# visible in the provider and in the Contacts app.
raw_one=$(insert --uri content://com.android.contacts/raw_contacts \
    --bind account_name:s:lsb-fixture-v1 --bind account_type:s:com.example.lsb.fixture --bind deleted:i:0)
raw_one_id=$($ADB shell "content query --uri content://com.android.contacts/raw_contacts --projection _id --where \"account_name='lsb-fixture-v1' AND account_type='com.example.lsb.fixture' AND deleted=0\" --sort \"_id DESC\"" | sed -n '1s/.*_id=\([0-9][0-9]*\).*/\1/p')
raw_two=$(insert --uri content://com.android.contacts/raw_contacts \
    --bind account_name:s:lsb-fixture-v1 --bind account_type:s:com.example.lsb.fixture --bind deleted:i:0)
raw_two_id=$($ADB shell "content query --uri content://com.android.contacts/raw_contacts --projection _id --where \"account_name='lsb-fixture-v1' AND account_type='com.example.lsb.fixture' AND deleted=0\" --sort \"_id DESC\"" | sed -n '1s/.*_id=\([0-9][0-9]*\).*/\1/p')
if [ -z "$raw_one_id" ] || [ -z "$raw_two_id" ]; then
    echo "Could not create fixture raw contacts" >&2
    exit 1
fi

insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:l:"$raw_one_id" \
    --bind mimetype:s:vnd.android.cursor.item/name \
    --bind data1:s:"${MARKER}_Alice_Δ"
insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:l:"$raw_one_id" \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 \
    --bind data1:s:+15550100001
insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:l:"$raw_one_id" \
    --bind mimetype:s:vnd.android.cursor.item/email_v2 \
    --bind data1:s:alice.lsb.fixture@example.invalid
insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:l:"$raw_one_id" \
    --bind mimetype:s:vnd.android.cursor.item/note \
    --bind data1:s:"${MARKER}_unicode_café_日本語"

insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:l:"$raw_two_id" \
    --bind mimetype:s:vnd.android.cursor.item/name \
    --bind data1:s:"${MARKER}_Bob"
insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:l:"$raw_two_id" \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 \
    --bind data1:s:+15550100002

# SMS timestamps are fixed for deterministic duplicate and ordering tests.
insert --uri content://sms \
    --bind address:s:+15550100001 --bind date:l:1704067200000 \
    --bind body:s:"${MARKER}_inbox_read_unread" --bind type:i:1 --bind read:i:0 --bind seen:i:0
insert --uri content://sms \
    --bind address:s:+15550100002 --bind date:l:1704067201000 \
    --bind body:s:"${MARKER}_unicode_café_日本語_😀" --bind type:i:1 --bind read:i:1 --bind seen:i:1
insert --uri content://sms \
    --bind address:s:+15550100001 --bind date:l:1704067202000 \
    --bind body:s:"${MARKER}_sent_multiline_second_line" --bind type:i:2 --bind read:i:1 --bind seen:i:1
insert --uri content://sms \
    --bind address:s:+15550100002 --bind date:l:1704067203000 \
    --bind body:s:"${MARKER}_long_$(printf 'x%.0s' $(seq 1 240))" --bind type:i:1 --bind read:i:0 --bind seen:i:0

# Incoming, outgoing, missed, and rejected call-log cases.
insert --uri content://call_log/calls \
    --bind number:s:+15550100001 --bind date:l:1704067210000 --bind duration:l:42 --bind type:i:1 --bind new:i:1 \
    --bind name:s:"${MARKER}_Alice"
insert --uri content://call_log/calls \
    --bind number:s:+15550100002 --bind date:l:1704067220000 --bind duration:l:0 --bind type:i:2 --bind new:i:0 \
    --bind name:s:"${MARKER}_Bob"
insert --uri content://call_log/calls \
    --bind number:s:+15550100001 --bind date:l:1704067230000 --bind duration:l:0 --bind type:i:3 --bind new:i:1 \
    --bind name:s:"${MARKER}_Alice"
insert --uri content://call_log/calls \
    --bind number:s:+15550100002 --bind date:l:1704067240000 --bind duration:l:0 --bind type:i:5 --bind new:i:1 \
    --bind name:s:"${MARKER}_Bob"

echo "Seed complete. Marker: $MARKER"
