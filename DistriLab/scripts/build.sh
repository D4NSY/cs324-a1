#!/usr/bin/env sh
# Builds distrilab.jar (needs JDK 9+ to build; the jar runs on Java 8+). Usage: scripts/build.sh
set -e
cd "$(dirname "$0")/.."
rm -rf out
mkdir -p out/classes
# Compiling the launcher with -sourcepath compiles every class it (transitively) uses.
javac --release 8 -encoding UTF-8 -Xlint:all,-serial,-options -d out/classes -sourcepath src/main/java src/main/java/distrilab/Launcher.java
cp -r src/main/resources/. out/classes/
jar --create --file distrilab.jar --main-class distrilab.Launcher -C out/classes .
echo "Built distrilab.jar"
