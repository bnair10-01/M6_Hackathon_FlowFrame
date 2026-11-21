#!/bin/sh
# Copies the shared Worn Run sources into the MLDemo app (its folder is an
# Xcode synced group, so copied files compile automatically). Run after
# changing any of these files; edit them here, not in MLDemo.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/../../sdk/Microlumin_Ship/04_Demo_Apps/MLDemo/MLDemo/WornRun"
mkdir -p "$dest"
for f in RunProtocol RouteBuilder RunLink RunSession DemoSimulator SetupView; do
  { echo "// Copied from ios/WornRun/WornRun/$f.swift by sync-to-mldemo.sh. Edit the original."; cat "$here/WornRun/$f.swift"; } > "$dest/$f.swift"
done
echo "Synced to $dest"
