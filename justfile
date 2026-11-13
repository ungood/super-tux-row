# SuperTuxRow tasks. Run `just` to list them.

# Local settings, such as the tablet's address, come from .env (not checked in; see example.env).
set dotenv-load

# The tablet's adb address, host:port.
tablet := env_var_or_default("TABLET", "")
adb := "adb -s " + tablet
package := "name.onetrue.supertuxrower"
apk := "android/app/build/outputs/apk/debug/app-debug.apk"

# List recipes
default:
    @just --list

# Build the debug APK
[group('build')]
build:
    gradle -p android :app:assembleDebug

# Run the unit tests
[group('build')]
test:
    gradle -p android :app:testDebugUnitTest

# Run the tests, then build
[group('build')]
check: test build

# Remove build outputs
[group('build')]
clean:
    gradle -p android clean

# Connect to the tablet over Wi-Fi adb (turn it on on the tablet first; see docs/tablet.md)
[group('tablet')]
connect: _tablet
    adb connect {{tablet}}

# Install the app on the tablet
[group('tablet')]
install: _tablet build
    {{adb}} install -r {{apk}}

# Install and start the app, on the rower if it's plugged in
[group('tablet')]
run: _tablet install
    {{adb}} shell am start -S -n {{package}}/.MainActivity

# Install and start the app on the simulated rower
[group('tablet')]
sim: _tablet install
    {{adb}} shell am start -S -n {{package}}/.MainActivity --ez simulate true

# Stop the app
[group('tablet')]
stop: _tablet
    {{adb}} shell am force-stop {{package}}

# Follow the app's log
[group('tablet')]
logs: _tablet
    {{adb}} logcat --pid=$({{adb}} shell pidof {{package}})

# Save a screenshot from the tablet
[group('tablet')]
screenshot file="screenshot.png": _tablet
    {{adb}} exec-out screencap -p > {{file}}

# Make the app the tablet's home app, so it's what you see at start and on Home. Also clears the
# launcher's boot app, which otherwise opens Ergatta whenever Android's launcher starts.
[group('tablet')]
set-home: _tablet
    {{adb}} shell cmd package set-home-activity {{package}}/.MainActivity
    {{adb}} shell "su -c 'setprop persist.sys.bootapp \"\"'"
    {{adb}} shell input keyevent KEYCODE_HOME

# Give home back to Android's launcher, with Ergatta as its boot app as the tablet shipped
[group('tablet')]
restore-home launcher="com.android.launcher3/.Launcher" bootapp="com.ergatta.seaborn/com.ergatta.seaborn.LoginRegisterActivity": _tablet
    {{adb}} shell cmd package set-home-activity {{launcher}}
    {{adb}} shell "su -c 'setprop persist.sys.bootapp {{bootapp}}'"
    {{adb}} shell input keyevent KEYCODE_HOME

# Keep adb over Wi-Fi on across reboots. adb on the tablet accepts anyone, so this leaves a root
# shell open to the whole network; see docs/tablet.md.
[group('tablet')]
persist-adb: _tablet
    {{adb}} shell "su -c 'setprop persist.adb.tcp.port 5555'"
    @echo "persist.adb.tcp.port = $({{adb}} shell getprop persist.adb.tcp.port)"

# Stop adb over Wi-Fi coming back on after a reboot
[group('tablet')]
unpersist-adb: _tablet
    {{adb}} shell "su -c 'setprop persist.adb.tcp.port \"\"'"
    @echo "persist.adb.tcp.port = '$({{adb}} shell getprop persist.adb.tcp.port)'"

# Turn off Ergatta's firmware updates and telemetry (Memfault). Its update manager is the device
# owner, which Android won't let us disable; see docs/tablet.md.
[group('tablet')]
disable-updates: _tablet
    {{adb}} shell pm disable-user --user 0 com.ergatta.bort.ota
    {{adb}} shell pm disable-user --user 0 com.ergatta.bort

# Turn Ergatta's firmware updates and telemetry back on
[group('tablet')]
enable-updates: _tablet
    {{adb}} shell pm enable com.ergatta.bort.ota
    {{adb}} shell pm enable com.ergatta.bort

# Copy ANTHROPIC_API_KEY from .env into the app's private storage on the tablet (never into the APK)
[group('tablet')]
set-api-key: _tablet
    @test -n "${ANTHROPIC_API_KEY:-}" || (echo "Set ANTHROPIC_API_KEY in .env." >&2; exit 1)
    @printf '%s' "$ANTHROPIC_API_KEY" | {{adb}} shell "run-as {{package}} sh -c 'mkdir -p files && cat > files/anthropic_api_key'"
    @echo "API key installed for Brainpower."

# Record a session from a rower plugged into this computer, into captures/
[group('rower')]
capture file seconds="60":
    python3 tools/log.py captures/{{file}} {{seconds}}

# List which S4 memory addresses are readable, from a rower plugged into this computer
[group('rower')]
scan:
    python3 tools/scan.py captures/scan.json

# Stop with a helpful message if the tablet's address isn't set.
[private]
_tablet:
    @test -n "{{tablet}}" || (echo "Set TABLET=host:port in .env (see example.env)." >&2; exit 1)
