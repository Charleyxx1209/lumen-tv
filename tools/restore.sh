#!/usr/bin/env bash
# Give Home back to the TV's own launcher.
#
#   TV=<tv-ip> tools/restore.sh              # stop taking over Home, keep Lumen installed
#   TV=<tv-ip> tools/restore.sh --uninstall  # and remove Lumen
# If you disabled the stock launcher by hand, also: adb shell pm enable <its package>
set -euo pipefail
: "${TV:?set TV to the TV IP address}"
PKG=app.lumen.tv
CLS=app.orchard.tv   # class names keep the upstream package; the app id is app.lumen.tv
GUARD="$PKG/$CLS.guard.HomeGuardService"
case "$TV" in *:*) SERIAL="$TV" ;; *) SERIAL="$TV:5555" ;; esac
export ANDROID_SERIAL="$SERIAL"
adb connect "$SERIAL" >/dev/null

# First take away what lets Lumen switch the guard back on, or it would simply do so.
adb shell am broadcast -a "$CLS.CONFIG" -n "$PKG/$CLS.guard.ConfigReceiver" --ez keep_adb false --ez own_screensaver false >/dev/null 2>&1 || true
adb shell cmd notification disallow_listener "$PKG/$CLS.guard.RevivalListener" >/dev/null 2>&1 || true
adb shell pm revoke "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW default >/dev/null 2>&1 || true
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES default >/dev/null 2>&1 || true
adb shell appops set "$PKG" APP_AUTO_START default >/dev/null 2>&1 || true   # TCL only
adb shell appops set "$PKG" APP_ASSOC_START default >/dev/null 2>&1 || true
adb shell am force-stop "$PKG"
case "$(adb shell settings get secure screensaver_components | tr -d '\r')" in
  "$PKG"/*) adb shell settings put secure screensaver_components "$(adb shell settings get secure screensaver_default_component | tr -d '\r')" ;;
esac

CURRENT=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
REST=$(printf '%s' "$CURRENT" | tr ':' '\n' | grep -v -x "$GUARD" | grep -v -x "$PKG/$CLS.guard.HomeGuardService" | paste -sd: - || true)
if [ -z "$REST" ] || [ "$REST" = null ]; then
  adb shell settings delete secure enabled_accessibility_services >/dev/null
else
  adb shell settings put secure enabled_accessibility_services "'$REST'"
fi
# Hisense VIDAA: put back the launcher install.sh replaced in persist.vt.use_home_pkg.
VT_HOME=$(adb shell settings get global lumen_vt_home_pkg | tr -d '\r')
if [ -n "$VT_HOME" ] && [ "$VT_HOME" != null ]; then
  adb shell setprop persist.vt.use_home_pkg "$VT_HOME"
  adb shell settings delete global lumen_vt_home_pkg >/dev/null
elif [ "$(adb shell getprop persist.vt.use_home_pkg | tr -d '\r')" = "$PKG" ] &&
     adb shell pm path com.jamdeo.tv.vod 2>/dev/null | grep -q package:; then
  # Set by hand before install.sh kept the old value (1.15 and earlier, issue #40): VIDAA's
  # launcher is 聚好看 on every VIDAA build reported so far.
  adb shell setprop persist.vt.use_home_pkg com.jamdeo.tv.vod
fi
adb shell cmd role remove-role-holder --user 0 android.app.role.HOME "$PKG" >/dev/null 2>&1 || true

if [ "${1:-}" = --uninstall ]; then
  adb uninstall "$PKG"
fi
adb shell input keyevent KEYCODE_HOME
echo "Home now: $(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r')"
