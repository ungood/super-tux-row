# SuperTuxRower tasks. Run `just` to list them.

# The tablet's adb address; override with TABLET=host:port.
tablet := env_var_or_default("TABLET", "tablet.example:5555")
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
connect:
    adb connect {{tablet}}

# Install the app on the tablet
[group('tablet')]
install: build
    {{adb}} install -r {{apk}}

# Install and start the app, on the rower if it's plugged in
[group('tablet')]
run: install
    {{adb}} shell am start -S -n {{package}}/.MainActivity

# Install and start the app on the simulated rower
[group('tablet')]
sim: install
    {{adb}} shell am start -S -n {{package}}/.MainActivity --ez simulate true

# Stop the app
[group('tablet')]
stop:
    {{adb}} shell am force-stop {{package}}

# Follow the app's log
[group('tablet')]
logs:
    {{adb}} logcat --pid=$({{adb}} shell pidof {{package}})

# Save a screenshot from the tablet
[group('tablet')]
screenshot file="screenshot.png":
    {{adb}} exec-out screencap -p > {{file}}

# Record a session from a rower plugged into this computer, into captures/
[group('rower')]
capture file seconds="60":
    python3 tools/log.py captures/{{file}} {{seconds}}

# List which S4 memory addresses are readable, from a rower plugged into this computer
[group('rower')]
scan:
    python3 tools/scan.py captures/scan.json
