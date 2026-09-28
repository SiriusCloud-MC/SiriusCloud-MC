#!/bin/sh
# Starts the SiriusCloud wrapper. Its state lives in ./wrapper/.
cd "$(dirname "$0")/wrapper" || exit 1

# Tells the wrapper it was started from here: this script installs downloaded
# updates, and starts it again when it exits with code 75 - to install one,
# or, for a node, to rejoin its cluster as a follower. Any other exit,
# including a normal 'shutdown', ends here.
export SIRIUSCLOUD_LAUNCHER=1

while true; do
    if [ -d local/updates/ready ]; then
        echo "Installing SiriusCloud $(cat local/updates/ready.version 2>/dev/null)..."
        cp -R local/updates/ready/. . && rm -rf local/updates/ready local/updates/ready.version
    fi
    java -Xms128M -Xmx256M -jar cloud-wrapper.jar
    code=$?
    [ "$code" -eq 75 ] || exit "$code"
    echo "Restarting..."
    sleep 1
done
