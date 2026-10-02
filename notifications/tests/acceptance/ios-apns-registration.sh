#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
plugin_root="$repo_root/plugins/notifications"
app="$plugin_root/tests/demo/app"
nexa="${NEXA_BIN:-$repo_root/target/debug/nexa}"
acceptance_out="$app/build/ios-apns-acceptance"
test_target=NexaAPNsAcceptanceTests

if [[ ! -x "$nexa" ]]; then
    echo "Nexa executable not found: $nexa" >&2
    exit 1
fi

simulator_id="${NEXA_IOS_SIMULATOR_ID:-}"
if [[ -z "$simulator_id" ]]; then
    simulator_id="$(xcrun simctl list devices booted -j | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
print(next((device["udid"] for group in devices.values() for device in group if device["state"] == "Booted"), ""))
')"
fi
if [[ -z "$simulator_id" ]]; then
    echo "no booted iOS Simulator was found" >&2
    exit 1
fi

run_id="$(date '+%Y%m%dT%H%M%S')"
project_out="$acceptance_out/project-$run_id"
dev_log="$acceptance_out/dev-$run_id.log"
test_log="$acceptance_out/xcodebuild-$run_id.log"
system_log="$acceptance_out/system-$run_id.log"
echo "Using iOS Simulator $simulator_id"

cd "$app"
"$nexa" check --ios
mkdir -p "$acceptance_out"
"$nexa" dev --ios --once --out "$project_out" >"$dev_log" 2>&1

ios_project="$project_out/ios/NotificationsDemo.xcodeproj"
test_source="$project_out/ios/${test_target}.swift"
cp "$script_dir/ios-apns-registration-ui.swift" "$test_source"
ruby "$repo_root/tests/add-ios-ui-test-target.rb" \
    "$ios_project" "${test_target}.swift" "$test_target" >/dev/null

if ! xcodebuild test -project "$ios_project" -scheme "$test_target" \
    -destination "platform=iOS Simulator,id=$simulator_id" \
    >"$test_log" 2>&1; then
    tail -n 100 "$test_log" >&2
    exit 1
fi
if ! grep -Fq '** TEST SUCCEEDED **' "$test_log"; then
    tail -n 100 "$test_log" >&2
    exit 1
fi

xcrun simctl spawn "$simulator_id" log show --style compact --last 5m \
    --predicate 'eventMessage CONTAINS "NEXA_NOTIFICATIONS_APNS_"' >"$system_log"
grep -Fq 'NEXA_NOTIFICATIONS_APNS_TOKEN_CHANGED' "$system_log"
grep -Fq 'NEXA_NOTIFICATIONS_APNS_TOKEN_REGISTERED' "$system_log"

echo "iOS Simulator APNs token registration passed; logs confirm a token event and completed registration."
