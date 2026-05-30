#!/usr/bin/env sh
set -eu

mkdir -p build/classes
javac -Xlint:all -d build/classes Accordinez.java
jar --create --file build/accordinez.jar --main-class Accordinez -C build/classes .

printf '%s\n' 'Built build/accordinez.jar'
printf '%s\n' 'Run with: java -jar build/accordinez.jar'
