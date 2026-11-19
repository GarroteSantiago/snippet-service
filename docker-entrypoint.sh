#!/bin/sh
# Loads the New Relic Java agent (APM, and the logs with their MDC) only with NEW_RELIC_ENABLED=true
# and a NEW_RELIC_LICENSE_KEY. Without them the service starts as always.
set -e
if [ "$NEW_RELIC_ENABLED" = "true" ]; then
    if [ -z "$NEW_RELIC_LICENSE_KEY" ]; then
        echo "NEW_RELIC_ENABLED=true needs NEW_RELIC_LICENSE_KEY" >&2
        exit 1
    fi
    set -- -javaagent:/app/newrelic/newrelic.jar "$@"
fi
exec java "$@" -jar /app/app.jar
