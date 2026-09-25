#!/usr/bin/env bash
# Script to stop any running Textbin API instance
fuser -k 8080/tcp 2>/dev/null || pkill -f 'textbin.*\.jar' 2>/dev/null || true
echo "Textbin API stopped and port 8080 cleared."
