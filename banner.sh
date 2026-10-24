#!/bin/bash
# Shows a bouncing banner on the TV home screen. Usage: ./banner.sh "text" [ip:port]
[ -n "$2" ] && export ANDROID_SERIAL="$2"
adb shell "am start -n com.nabil.tvhome/.HomeActivity --es banner '${1//\'/}'" | tail -1
