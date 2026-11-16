#!/bin/sh
# Copy Moyna's events log from the phone to private/logs/events/ on this PC (private, never committed).
# Usage: sh tools/pull-logs.sh            (phone on USB, or wireless debugging connected)
mkdir -p private/logs/events
adb exec-out run-as app.moyna tar c -C files events | tar x -C private/logs
ls private/logs/events | tail -5
