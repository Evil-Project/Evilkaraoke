#!/bin/sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd /Users/xiaoyuan/Documents/Evilkaraoke || exit 1
./gradlew "$@" --no-daemon --console=plain > /tmp/ek-build.log 2>&1
echo "exit=$?"
tail -n 90 /tmp/ek-build.log
