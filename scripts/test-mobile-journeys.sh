#!/usr/bin/env bash
# Isolated Android screen/HTTP/lifecycle regressions. Never a real device or PROD.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
JOURNEY_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$JOURNEY_SDK" ]] || { echo 'Android SDK with emulator and android-34 default x86_64 image required' >&2; exit 2; }
for executable in platform-tools/adb emulator/emulator cmdline-tools/latest/bin/avdmanager; do
    [[ -x "$JOURNEY_SDK/$executable" ]] || { echo "Missing SDK tool: $executable" >&2; exit 2; }
done
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo 'KVM access required; this script does not change host permissions' >&2; exit 2; }
[[ -d "$JOURNEY_SDK/system-images/android-34/default/x86_64" ]] || { echo 'Install the reviewed android-34 default x86_64 test image first' >&2; exit 2; }
python3 - <<'PY'
import socket
for port in (5098, 5588, 5589):
    with socket.socket() as listener:
        try:
            listener.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit('Private journey test port already in use; do not reuse an existing emulator or ADB server')
PY
if git -C "$REPO_DIR" show-ref --verify --quiet refs/remotes/github/main \
    && git -C "$REPO_DIR" show-ref --verify --quiet refs/remotes/origin/main; then
    git -C "$REPO_DIR" merge-base --is-ancestor origin/main github/main \
        || { echo 'Operational mirror diverges from github/main' >&2; exit 2; }
fi
mkdir -p "$REPO_DIR/target/mobile-journeys"
JOURNEY_RUNTIME="$(mktemp -d "$REPO_DIR/target/mobile-journeys/runtime.XXXXXX")"
export ANDROID_USER_HOME="$JOURNEY_RUNTIME/android-user"
export ANDROID_AVD_HOME="$JOURNEY_RUNTIME/avd"
export ANDROID_ADB_SERVER_PORT=5098
mkdir -m 700 "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"
JOURNEY_EMULATOR_PID=''
JOURNEY_ADB_STARTED=false
cleanup() {
    local result=$?
    trap - EXIT
    if [[ -n "$JOURNEY_EMULATOR_PID" ]]; then
        "$JOURNEY_SDK/platform-tools/adb" -P 5098 -s emulator-5588 emu kill >/dev/null 2>&1 || true
        for _ in {1..20}; do
            kill -0 "$JOURNEY_EMULATOR_PID" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "$JOURNEY_EMULATOR_PID" 2>/dev/null; then kill "$JOURNEY_EMULATOR_PID" 2>/dev/null || true; fi
        wait "$JOURNEY_EMULATOR_PID" 2>/dev/null || true
    fi
    if [[ "$JOURNEY_ADB_STARTED" == true ]]; then "$JOURNEY_SDK/platform-tools/adb" -P 5098 kill-server >/dev/null 2>&1 || true; fi
    "$JOURNEY_SDK/cmdline-tools/latest/bin/avdmanager" delete avd --name atenea-mobile-journeys >/dev/null 2>&1 || true
    exit "$result"
}
trap cleanup EXIT
cd "$REPO_DIR"
# Library tests never load signing, Firebase or update-channel configuration.
./scripts/android-build.sh :core-console:assembleDebugAndroidTest \
    :core-console:testDebugUnitTest
"$JOURNEY_SDK/platform-tools/adb" -P 5098 start-server
JOURNEY_ADB_STARTED=true
printf 'no\n' | "$JOURNEY_SDK/cmdline-tools/latest/bin/avdmanager" create avd \
    --name atenea-mobile-journeys --package 'system-images;android-34;default;x86_64' --device pixel_2
"$JOURNEY_SDK/emulator/emulator" -avd atenea-mobile-journeys -port 5588 \
    -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader_indirect \
    -accel on -cores 2 -memory 1536 > target/mobile-journeys/emulator.log 2>&1 &
JOURNEY_EMULATOR_PID=$!
journey_booted=false
for _ in {1..150}; do
    kill -0 "$JOURNEY_EMULATOR_PID" 2>/dev/null || { echo 'Private test emulator exited before boot' >&2; exit 1; }
    if [[ "$("$JOURNEY_SDK/platform-tools/adb" -P 5098 -s emulator-5588 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
        journey_booted=true; break
    fi
    sleep 2
done
[[ "$journey_booted" == true ]] || { echo 'Private test emulator boot deadline exceeded' >&2; exit 1; }
[[ "$("$JOURNEY_SDK/platform-tools/adb" -P 5098 -s emulator-5588 shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] \
    || { echo 'Refusing instrumentation on a non-emulator device' >&2; exit 2; }
"$JOURNEY_SDK/platform-tools/adb" -P 5098 -s emulator-5588 install -r \
    android/core-console/build/outputs/apk/androidTest/debug/core-console-debug-androidTest.apk
"$JOURNEY_SDK/platform-tools/adb" -P 5098 -s emulator-5588 shell am instrument -w \
    -e class com.atenea.android.coreconsole.ChangeWorkflowJourneyTest,com.atenea.android.coreconsole.ValidationAdmissionJourneyTest,com.atenea.android.coreconsole.NotificationNavigationJourneyTest,com.atenea.android.coreconsole.ReadCoordinationJourneyTest,com.atenea.android.coreconsole.RecoveryReadJourneyTest,com.atenea.android.coreconsole.WorkSessionReadJourneyTest \
    com.atenea.android.coreconsole.test/androidx.test.runner.AndroidJUnitRunner \
    | tee target/mobile-journeys/instrumentation.log
python3 - <<'PY'
import pathlib,re
report=pathlib.Path('target/mobile-journeys/instrumentation.log').read_text()
if not re.search(r'^OK \(12 tests\)\s*$',report,re.MULTILINE) or any(
        marker in report for marker in ('FAILURES!!!','INSTRUMENTATION_FAILED','FATAL EXCEPTION')):
    raise SystemExit('ANDROID_JOURNEYS_FAILED: require all twelve instrumented regressions, not only adb exit 0')
print('ANDROID_JOURNEYS_PASS: twelve synthetic-peer screen/HTTP/lifecycle tests; not real mobile acceptance')
PY
