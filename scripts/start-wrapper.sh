#!/bin/sh
# Starts the SiriusCloud wrapper. Its state lives in ./wrapper/.
cd "$(dirname "$0")/wrapper" || exit 1
exec java -Xms128M -Xmx256M -jar cloud-wrapper.jar
