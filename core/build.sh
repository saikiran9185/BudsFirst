#!/bin/zsh
# Builds the BudsFirst engine into build/budsfirst.dex, runnable as the shell user via app_process.
set -e
cd "${0:A:h}"

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
ANDROID_JAR=$SDK/platforms/android-36/android.jar
BUILD_TOOLS=$SDK/build-tools/36.1.0

rm -rf build && mkdir -p build/classes
$JAVA_HOME/bin/javac -source 8 -target 8 -Xlint:-options -bootclasspath $ANDROID_JAR \
    -d build/classes $(find src -name '*.java')
$BUILD_TOOLS/d8 --min-api 31 --output build $(find build/classes -name '*.class')
mv build/classes.dex build/budsfirst.dex
echo "built build/budsfirst.dex"
