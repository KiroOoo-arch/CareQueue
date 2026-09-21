#!/usr/bin/env bash
# =============================================================================
# CareQueue+ end-to-end test — customer + admin, driven via adb on a live
# Android emulator, with Firestore REST used for seed data and ground truth.
#
# Usage:
#   ./scripts/e2e-test.sh                # full flow (rebuilds APK by default)
#   ./scripts/e2e-test.sh --no-build     # reuse the existing debug APK
#   ./scripts/e2e-test.sh --no-reset     # skip Firestore seed/cleanup
#
# Prerequisites:
#   - A running Android emulator (adb devices must show one)
#   - JAVA_HOME pointing at a JDK (e.g. Android Studio's jbr)
#   - curl (for the Firestore REST calls)
#
# Exit code 0 = all checks passed.
# =============================================================================
set -uo pipefail

# ---------------------------------------------------------------- config ----
API_KEY="AIzaSyAtaM571K12iRKb_nyDzdWJplWArqnsx4o"
PROJECT_ID="carequeue-dbfeb"
FIRESTORE="https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"
PACKAGE="com.carequeue.plus"
ACTIVITY="$PACKAGE/.MainActivity"
CUSTOMER_EMAIL="buffy.e2e.test@carequeue-test.com"
CUSTOMER_PASS="Test1234!"
ADMIN_EMAIL="buffy.admin@carequeue-test.com"
ADMIN_PASS="Test1234!"
SHOT_DIR="e2e-artifacts"

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
GRADLE_JAVA_HOME="${GRADLE_JAVA_HOME:-E:\\New folder\\jbr}"
mkdir -p "$SHOT_DIR"

# ---------------------------------------------------------------- output ----
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ✘ $1"; }
step() { echo; echo "=== $1 ==="; }

# ---------------------------------------------------------------- helpers ---
# Fresh UI dump before every read — button positions shift as errors appear.
dump() { "$ADB" shell "uiautomator dump /sdcard/ui.xml && cat /sdcard/ui.xml" > /tmp/e2e-ui.xml 2>/dev/null; }

# Center-tap the element whose text= attribute equals $1.
tap_text() {
  dump
  local bounds
  bounds=$(grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" /tmp/e2e-ui.xml \
            | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  if [[ -z $bounds ]]; then echo "tap_text: '$1' not found on screen" >&2; return 1; fi
  local x1 y1 x2 y2
  read -r x1 y1 x2 y2 <<< "$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')"
  "$ADB" shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
}

tap_text_quiet() { tap_text "$1" >/dev/null 2>&1; }

# Same as tap_text but matches content-desc= (icon buttons have no text).
tap_desc() {
  dump
  local bounds
  bounds=$(grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" /tmp/e2e-ui.xml \
            | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  if [[ -z $bounds ]]; then echo "tap_desc: '$1' not found on screen" >&2; return 1; fi
  local x1 y1 x2 y2
  read -r x1 y1 x2 y2 <<< "$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')"
  "$ADB" shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
}

# Type into the Nth EditText (1-based, top to bottom), optionally clearing first.
type_into_field() {
  local index=$1 text=$2
  dump
  local bounds
  bounds=$(grep -oE 'class="android.widget.EditText"[^>]*bounds="[^"]*"' /tmp/e2e-ui.xml \
           | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | sed -n "${index}p")
  if [[ -z $bounds ]]; then echo "type_into_field: field $index not found" >&2; return 1; fi
  local x1 y1 x2 y2
  read -r x1 y1 x2 y2 <<< "$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')"
  "$ADB" shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
  sleep 1
  if [[ $3 == "--clear" ]]; then "$ADB" shell input keyevent 123; for _ in {1..40}; do "$ADB" shell input keyevent 67; done; fi
  # %s becomes a space inside `input text`; @ must be escaped.
  "$ADB" shell input text "${text//@/\\@}"
}

# All visible non-empty texts, sorted+deduped, for assertions.
screen_texts() { dump; grep -oE 'text="[^"]+"' /tmp/e2e-ui.xml | grep -vE 'text=""' | sed 's/text="//;s/"$//' | sort -u; }

assert_on_screen() {
  if screen_texts | grep -qxF "$1"; then ok "screen shows '$1'"; else bad "screen shows '$1' (got: $(screen_texts | tr '\n' '|'))"; fi
}

assert_not_on_screen() {
  if screen_texts | grep -qxF "$1"; then bad "screen must NOT show '$1'"; else ok "screen free of '$1'"; fi
}

screenshot() { "$ADB" shell "screencap -p /sdcard/e2e_shot.png"; MSYS_NO_PATHCONV=1 "$ADB" pull /sdcard/e2e_shot.png "$SHOT_DIR/$1.png" >/dev/null 2>&1; }

# Dismiss the Android 13+ POST_NOTIFICATIONS dialog if it is showing.
dismiss_permission_dialog() {
  dump
  if grep -q 'Allow CareQueue+ to send you notifications' /tmp/e2e-ui.xml; then
    tap_text_quiet "Don’t allow" || "$ADB" shell input keyevent 4
    sleep 2
    ok "notification permission dialog dismissed"
  fi
}

no_crashes() {
  local n; n=$("$ADB" logcat -d 2>/dev/null | grep -cE "FATAL EXCEPTION")
  if [[ $n -eq 0 ]]; then ok "no FATAL EXCEPTION in logcat"; else bad "$n FATAL EXCEPTION(s) in logcat"; fi
}

# ------------------------------------------------------- firestore helpers --
fs_signin() { # $1=email $2=password -> echoes "uid token" (empty token on failure)
  local res
  res=$(curl -s -X POST "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\",\"password\":\"$2\",\"returnSecureToken\":true}")
  echo "$(echo "$res" | grep -o '"localId": "[^"]*"' | cut -d'"' -f4) $(echo "$res" | grep -o '"idToken": "[^"]*"' | cut -d'"' -f4)"
}

fs_get()  { curl -s "$FIRESTORE/$1" -H "Authorization: Bearer $TOKEN"; }
fs_delete(){ curl -s -X DELETE "$FIRESTORE/$1" -H "Authorization: Bearer $TOKEN" >/dev/null; }

# Field value from a REST document JSON: fs_field <doc-path> <field>
fs_field() {
  fs_get "$1" | sed 's/,"/\n"/g' | grep -A1 "\"$2\"" | grep -oE '"(stringValue|integerValue)": "[^"]*"' | head -1 | cut -d'"' -f4
}

# ------------------------------------------------------------- cleanup ------
cleanup_entries() {
  local token=$1
  local ids
  ids=$(curl -s "$FIRESTORE/queueEntries" -H "Authorization: Bearer $token" \
        | grep -oE '"name": "[^"]*"' | awk -F/ '{print $NF}' | tr -d '"' | grep -E 'e2e-entry-|smoke-')
  for id in $ids; do fs_delete "queueEntries/$id"; done
  [[ -n $ids ]] && echo "  (removed seeded entries: $(echo $ids | tr '\n' ' '))" || echo "  (no seeded entries to remove)"
}

# =================================================================== main ====
step "Environment"
command -v curl >/dev/null || { echo "curl is required"; exit 2; }
[[ -f $ADB ]] || ADB=$(command -v adb) || { echo "adb not found"; exit 2; }
"$ADB" devices | grep -q "emulator.*device$" || { echo "No emulator running — start one first."; exit 2; }
ok "emulator connected"

read -r CUSTOMER_UID CUSTOMER_TOKEN <<< "$(fs_signin "$CUSTOMER_EMAIL" "$CUSTOMER_PASS")"
read -r ADMIN_UID ADMIN_TOKEN <<< "$(fs_signin "$ADMIN_EMAIL" "$ADMIN_PASS")"
[[ -n $CUSTOMER_TOKEN ]] || { echo "Customer sign-in failed (account missing? register once via the app)"; exit 2; }
[[ -n $ADMIN_TOKEN ]]    || { echo "Admin sign-in failed"; exit 2; }
ok "Firebase Auth reachable (customer + admin tokens minted)"
TOKEN=$CUSTOMER_TOKEN

# ------------------------------------------------------- optional build ----
if [[ ${1:-} != "--no-build" ]]; then
  step "Build"
  JAVA_HOME="$GRADLE_JAVA_HOME" ./gradlew assembleDebug --console=plain -q \
    && ok "assembleDebug" || bad "assembleDebug"
fi

# ------------------------------------------------- reset seed data ----------
if [[ ${1:-} != "--no-reset" ]]; then
  step "Seed reset"
  QDOC="queues/s9qEvUvHRiE4raeix1jF"
  QID=$(fs_field "$QDOC" queueId); [[ $QID == s9qEvUvHRiE4raeix1jF ]] && ok "queue doc ID == queueId field" || bad "queue ID mismatch ($QID)"
  BID=$(fs_field "businesses/xn2sM877TjLDpKFAJavZ" businessId); [[ $BID == xn2sM877TjLDpKFAJavZ ]] && ok "business doc ID == businessId field" || bad "business ID mismatch ($BID)"
  cleanup_entries "$CUSTOMER_TOKEN"
  curl -s -X PATCH "$FIRESTORE/$QDOC?updateMask.fieldPaths=currentNumber" \
    -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" \
    -d '{"fields":{"currentNumber":{"integerValue":"0"}}}' >/dev/null
  ok "queue currentNumber reset to 0"
fi

# ================================================================= CUSTOMER =
step "Customer: fresh install state"
"$ADB" shell pm clear "$PACKAGE" >/dev/null 2>&1 && ok "app data cleared (deterministic start)"
"$ADB" logcat -c 2>/dev/null
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1 && ok "APK installed" || { bad "APK install"; exit 2; }
"$ADB" shell am start -W -n "$ACTIVITY" >/dev/null 2>&1; sleep 9
dismiss_permission_dialog
assert_on_screen "Sign in to CareQueue+"
no_crashes

step "Customer: sign in"
tap_text_quiet "Email"; sleep 1; "$ADB" shell input text "${CUSTOMER_EMAIL//@/\\@}"
tap_text_quiet "Password"; sleep 1; "$ADB" shell input text "$CUSTOMER_PASS"
tap_text "Sign In" || exit 2
sleep 10
assert_on_screen "Hello, Buffy Test!"
assert_on_screen "City Clinic"
screenshot "01_customer_home"

step "Customer: open business → Service Details"
tap_text "City Clinic" || exit 2
sleep 8
assert_on_screen "Queue Open"
assert_on_screen "Join Queue"
screenshot "02_service_details"

step "Customer: join queue (transaction + numbering)"
tap_text "Join Queue" || exit 2
sleep 9
assert_on_screen "My Queue"
assert_on_screen "Status: WAITING"
assert_on_screen "SmartReturn Estimate"
screenshot "03_my_queue"
# Find the customer's active entry in this queue (match on userId, not doc order).
ENTRY_ID=""
for id in $(curl -s "$FIRESTORE/queueEntries?pageSize=100" -H "Authorization: Bearer $CUSTOMER_TOKEN" \
             | grep -oE '"name": "[^"]*"' | awk -F/ '{print $NF}' | tr -d '"'); do
  [[ $(fs_field "queueEntries/$id" userId) == "$CUSTOMER_UID" ]] && { ENTRY_ID=$id; break; }
done
[[ -n $ENTRY_ID ]] && ok "queue entry document created in Firestore" || bad "no entry doc found for customer uid"
no_crashes

step "Customer: cancel entry"
tap_text "Cancel Queue Entry" || exit 2
sleep 8
assert_on_screen "Join Queue"
[[ -n ${ENTRY_ID:-} ]] && {
  ST=$(fs_field "queueEntries/$ENTRY_ID" status)
  [[ $ST == CANCELLED ]] && ok "Firestore entry status = CANCELLED" || bad "entry status = $ST (expected CANCELLED)"
}

# ==================================================================== ADMIN =
step "Admin: sign out and sign in"
"$ADB" shell input keyevent 4; sleep 2   # ServiceDetails -> Customer Home
tap_desc "Logout" || { echo "Logout icon not found"; exit 2; }
sleep 5
assert_on_screen "Sign in to CareQueue+"
tap_text_quiet "Email"; sleep 1
"$ADB" shell input text "${ADMIN_EMAIL//@/\\@}"
tap_text_quiet "Password"; sleep 1; "$ADB" shell input text "$ADMIN_PASS"
tap_text "Sign In" || exit 2
sleep 10
assert_on_screen "Welcome, Buffy Admin!"
assert_on_screen "Admin Dashboard"
screenshot "04_admin_dashboard"

step "Admin: open Queue Management"
tap_text "General Consultation" || exit 2
sleep 7
assert_on_screen "Queue Controls"
assert_on_screen "Call Next"
screenshot "05_queue_management"

step "Admin: seed a waiting customer, then Call Next"
NOW=$(( $(date +%s) * 1000 ))
CUID=$CUSTOMER_UID   # seeded entry belongs to the test customer
curl -s -X POST "$FIRESTORE/queueEntries?documentId=e2e-entry-callnext" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"fields":{"entryId":{"stringValue":"e2e-entry-callnext"},"queueId":{"stringValue":"s9qEvUvHRiE4raeix1jF"},"userId":{"stringValue":"'"$CUID"'"},"queueNumber":{"integerValue":"90"},"status":{"stringValue":"WAITING"},"joinedAt":{"integerValue":"'"$NOW"'"}}}' >/dev/null
sleep 4
assert_on_screen "WAITING"
tap_text "Call Next" || exit 2
sleep 6
assert_on_screen "CALLED"
assert_on_screen "Mark Served"
screenshot "06_called"

step "Admin: Skip the called entry"
tap_text "Skip" || exit 2
sleep 6
ST=$(fs_field "queueEntries/e2e-entry-callnext" status)
[[ $ST == SKIPPED ]] && ok "Firestore entry status = SKIPPED" || bad "entry status = $ST (expected SKIPPED)"

step "Admin: seed another, Call Next, Mark Served"
NOW=$(( $(date +%s) * 1000 ))
curl -s -X POST "$FIRESTORE/queueEntries?documentId=e2e-entry-serve" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"fields":{"entryId":{"stringValue":"e2e-entry-serve"},"queueId":{"stringValue":"s9qEvUvHRiE4raeix1jF"},"userId":{"stringValue":"'"$CUID"'"},"queueNumber":{"integerValue":"91"},"status":{"stringValue":"WAITING"},"joinedAt":{"integerValue":"'"$NOW"'"}}}' >/dev/null
sleep 4
tap_text "Call Next" || exit 2; sleep 6
tap_text "Mark Served" || exit 2; sleep 6
ST=$(fs_field "queueEntries/e2e-entry-serve" status)
[[ $ST == SERVED ]] && ok "Firestore entry status = SERVED" || bad "entry status = $ST (expected SERVED)"
screenshot "07_after_serve"
no_crashes

# ==================================================================== CLEANUP
step "Cleanup"
cleanup_entries "$ADMIN_TOKEN"
echo "  (screenshots kept in $SHOT_DIR/)"

# =================================================================== summary =
step "Summary"
echo "  Passed: $PASS   Failed: $FAIL"
if [[ $FAIL -eq 0 ]]; then echo "  ✅ E2E PASSED"; exit 0; else echo "  ❌ E2E FAILED"; exit 1; fi
