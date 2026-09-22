#!/bin/sh
# Starts the SiriusCloud node. Its state lives in ./node/.
cd "$(dirname "$0")/node" || exit 1
exec java -Xms256M -Xmx512M -jar cloud-node.jar
