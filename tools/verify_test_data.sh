#!/bin/sh
set -eu

DEVICE="${ANDROID_SERIAL:-emulator-5554}"
ADB="adb -s $DEVICE"
MARKER="LSB-FIXTURE-V1"

count() {
    uri=$1
    where=$2
    $ADB shell "content query --uri $uri --where \"$where\"" 2>/dev/null \
        | grep -c 'Row:' || true
}

sms=$(count content://sms "body LIKE '%$MARKER%'")
calls=$(count content://call_log/calls "name LIKE '%$MARKER%'")
contacts=$(count content://com.android.contacts/contacts "display_name LIKE '%$MARKER%'")

echo "fixture SMS=$sms calls=$calls contacts=$contacts"
[ "$sms" -eq 4 ]
[ "$calls" -eq 4 ]
[ "$contacts" -eq 2 ]
