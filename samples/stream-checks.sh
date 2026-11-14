#!/usr/bin/env bash
# On-device checks of the Binder byte streams (files@1.0 between two apps), on an emulator or a test phone.
#
#   ./gradlew :samples:provider:assembleDebug :samples:consumer:assembleDebug
#   samples/stream-checks.sh <adb-serial> [check ...]
#
# Installs both samples, runs each check in the consumer (`am start --es check <name>`) and reads the outcome from
# logcat (tag KoraSample). Two checks kill a process half way through a stream: download-slow stops the provider and
# upload-slow stops the consumer. Exits non-zero when any check fails. Never point it at someone's personal phone:
# it force-stops apps and clears logcat.
set -euo pipefail

serial="${1:?usage: $0 <adb-serial> [check ...]}"
shift
checks=("$@")
if [ ${#checks[@]} -eq 0 ]; then
    checks=(roundtrip upload-stall read-idle read-close unread download-slow upload-slow)
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
provider=eus.bihurri.kora.sample.provider
consumer=eus.bihurri.kora.sample.consumer
adb=(adb -s "$serial")

apk() {
    find "$root/samples/$1/build/outputs/apk/debug" -name '*.apk' -print -quit 2>/dev/null
}

for module in provider consumer; do
    file="$(apk "$module")"
    [ -n "$file" ] || { echo "No debug APK for samples/$module: build it first" >&2; exit 2; }
    "${adb[@]}" install -r "$file" >/dev/null
done

log() { "${adb[@]}" logcat -d -s KoraSample:I; }

# Waits up to $2 seconds for a logcat line matching $1 and prints it; fails when none came.
await_line() {
    local pattern="$1" seconds="$2" line
    for _ in $(seq "$seconds"); do
        line="$(log | grep -E "$pattern" | head -n 1 || true)"
        if [ -n "$line" ]; then echo "$line"; return 0; fi
        sleep 1
    done
    return 1
}

# The last outcome and audit lines, to show why a check that kills a process never got that far.
last_lines() { log | grep -E 'CHECK|audit' | tail -n 3 | tr '\n' ' '; }

start_check() {
    "${adb[@]}" logcat -c
    "${adb[@]}" shell am force-stop "$consumer"
    "${adb[@]}" shell am start -n "$consumer/.MainActivity" --es check "$1" >/dev/null
}

failed=0
report() { # name, verdict, detail
    echo "$1: $2 $3"
    [ "$2" = PASS ] || failed=1
}

for check in "${checks[@]}"; do
    start_check "$check"
    case "$check" in
        download-slow)
            await_line 'PROGRESS download-slow' 30 >/dev/null || { report "$check" FAIL "no progress: $(last_lines)"; continue; }
            sleep 2
            "${adb[@]}" shell am force-stop "$provider"
            ;;
        upload-slow)
            await_line 'upload slow-upload started' 30 >/dev/null || { report "$check" FAIL "the provider saw no upload: $(last_lines)"; continue; }
            sleep 2
            "${adb[@]}" shell am force-stop "$consumer"
            if line="$(await_line 'upload slow-upload failed' 20)"; then
                report "$check" PASS "${line#*: }"
            else
                report "$check" FAIL "the provider's read did not fail within 20 s of killing the consumer"
            fi
            continue
            ;;
    esac
    if line="$(await_line "CHECK $check (PASS|FAIL)" 60)"; then
        verdict="$(sed -E "s/.*CHECK $check (PASS|FAIL).*/\1/" <<<"$line")"
        report "$check" "$verdict" "${line#*CHECK $check $verdict }"
    else
        report "$check" FAIL "no outcome within 60 s"
    fi
    # What the provider saw of this check (its uploads and the closing of its sources).
    log | grep -E 'upload |source |download ' | sed 's/^/    provider: /' || true
done

exit "$failed"
