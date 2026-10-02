#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../../../.." && pwd)"
plugin_root="$repo_root/plugins/in-app-purchases"
app="$plugin_root/tests/demo/app"
nexa="${NEXA_BIN:-$repo_root/target/debug/nexa}"
acceptance_out="$app/build/ios-storekit-acceptance"
test_target=NexaInAppPurchasesAcceptanceTests

if [[ ! -x "$nexa" ]]; then
    echo "Nexa executable not found: $nexa" >&2
    exit 1
fi

simulator_id="$(xcrun simctl list devices booted -j | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
print(next((device["udid"] for group in devices.values() for device in group if device["state"] == "Booted"), ""))
')"
if [[ -n "${NEXA_IOS_SIMULATOR_ID:-}" ]]; then
    simulator_id="$NEXA_IOS_SIMULATOR_ID"
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

ios_project="$project_out/ios/InAppPurchasesDemo.xcodeproj"
storekit_config="$project_out/ios/NexaInAppPurchases.storekit"
test_source="$project_out/ios/${test_target}.swift"
cp "$script_dir/NexaInAppPurchases.storekit" "$storekit_config"
cp "$script_dir/ios-storekit-purchases-ui.swift" "$test_source"
ruby "$repo_root/tests/add-ios-ui-test-target.rb" \
    "$ios_project" "${test_target}.swift" "$test_target" >/dev/null
ruby "$script_dir/setup-storekit-schemes.rb" \
    "$project_out/ios/InAppPurchasesDemo.xcodeproj/xcshareddata/xcschemes/InAppPurchasesDemo.xcscheme" \
    "$ios_project/xcshareddata/xcschemes/${test_target}.xcscheme" \
    "$storekit_config"
grep -Fq "identifier='../NexaInAppPurchases.storekit'" \
    "$ios_project/xcshareddata/xcschemes/${test_target}.xcscheme"

log_since="$(date '+%Y-%m-%d %H:%M:%S')"
if ! xcodebuild test -project "$ios_project" -scheme "$test_target" \
    -destination "platform=iOS Simulator,id=$simulator_id" CODE_SIGNING_ALLOWED=NO \
    >"$test_log" 2>&1; then
    tail -n 100 "$test_log" >&2
    exit 1
fi
if ! grep -Fq '** TEST SUCCEEDED **' "$test_log"; then
    tail -n 100 "$test_log" >&2
    exit 1
fi

xcrun simctl spawn "$simulator_id" log show --style compact --start "$log_since" \
    --predicate 'eventMessage CONTAINS "NEXA_IAP_"' >"$system_log"
grep -Fq 'NEXA_IAP_PRODUCT_CATALOG_LOADED 2' "$system_log"
grep -Fq 'NEXA_IAP_PURCHASE_UPDATED' "$system_log"
grep -Fq 'NEXA_IAP_PURCHASE_RESULT_PURCHASED' "$system_log"
grep -Fq 'NEXA_IAP_CONSUMABLE_COMPLETED' "$system_log"
grep -Fq 'NEXA_IAP_RESTORE_COUNT 0' "$system_log"

echo "iOS StoreKit catalog, consumable purchase, transaction completion, and restore passed; Simulator logs contain all Nexa acceptance events."
