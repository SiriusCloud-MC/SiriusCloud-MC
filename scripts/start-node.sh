#!/bin/sh
# Starts the SiriusCloud node. Its state lives in ./node/.
cd "$(dirname "$0")/node" || exit 1

# Exit code 75: a cluster leader stepped down and asks to be started again as
# a follower. Anything else - including a normal 'shutdown' - ends here.
while true; do
    java -Xms256M -Xmx512M -jar cloud-node.jar
    code=$?
    [ "$code" -eq 75 ] || exit "$code"
    echo "Restarting as a cluster follower..."
    sleep 1
done
