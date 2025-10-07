#!/bin/bash

cd /usr/share/izgateway

# Get Service Name from AWS for log reporting.
SERVICE_NAME=unknown
if [[ $ECS_CONTAINER_METADATA_URI_V4 ]]
then
    SERVICE_NAME=`curl -s $ECS_CONTAINER_METADATA_URI_V4 | jq -r '.Labels["com.amazonaws.ecs.container-name"]'`
fi
export SERVICE_NAME

# Start filebeat and metricbeat if API key is provided
FILEBEAT_STARTED=false
METRICBEAT_STARTED=false

if [[ $ELASTIC_API_KEY ]]; then
    if command -v filebeat >/dev/null 2>&1; then
        filebeat -e &
        FILEBEAT_STARTED=true
        echo "Started Filebeat"
    else
        echo "WARNING: filebeat not found - skipping filebeat monitoring"
    fi
    
    if command -v metricbeat >/dev/null 2>&1; then
        metricbeat -e &
        METRICBEAT_STARTED=true
        echo "Started MetricBeat"
    else
        echo "WARNING: metricbeat not found - skipping metricbeat monitoring"
    fi
else
    echo "Elastic logging not enabled"
fi

# Monitor beats and exit container if they stop unexpectedly
monitor_beats() {
    local java_pid=$1  # Pass Java PID as parameter
    while true; do
        sleep 30
        if [[ $ELASTIC_API_KEY ]]; then
            # Only monitor filebeat if it was successfully started
            if [[ $FILEBEAT_STARTED == "true" ]] && ! pgrep -f "filebeat" > /dev/null; then
                echo "ALERT: Filebeat process has stopped unexpectedly. Stopping izgw-hub service."
                if [[ -n "$java_pid" ]]; then
                    kill "$java_pid" 2>/dev/null
                else
                    echo "ERROR: Java PID not available, cannot terminate gracefully"
                fi
                exit 1
            fi
            # Only monitor metricbeat if it was successfully started
            if [[ $METRICBEAT_STARTED == "true" ]] && ! pgrep -f "metricbeat" > /dev/null; then
                echo "ALERT: Metricbeat process has stopped unexpectedly. Stopping izgw-hub service."
                if [[ -n "$java_pid" ]]; then
                    kill "$java_pid" 2>/dev/null
                else
                    echo "ERROR: Java PID not available, cannot terminate gracefully"
                fi
                exit 1
            fi
        fi
    done
}


jarfilename=$1

#Save original nameserver in temp file and empty contents
echo nameserver 127.0.0.1 > /tmp/newresolv.conf
cat /etc/resolv.conf >> /tmp/newresolv.conf
truncate -s 0 /etc/resolv.conf
cat /tmp/newresolv.conf > /etc/resolv.conf

#Start dnsmasq as root
dnsmasq  --use-stale-cache=0 --log-queries=extra --user=root --log-facility=/var/log/dnsmasq.log

# Enable remote debugging if DEBUG is set in the environment
JAVA_TOOL_OPTS=
if [[ $DEBUG ]]
then
    # To enable remote debugging, set JAVA_OPTS=-Xdebug -Xrunjdwp:transport=dt_socket,address=*:8000,server=y,suspend=n
    #
    # The * below is important.  It enables your debugger to attach through whatever IP Address Docker is using
    # to recieve connections on port 8000.  If not used, then only connections from 127.0.0.1 are accepted, and while
    # you may think your IP Address of the host is 127.0.0.1, it's not inside the container.  Change suspend=n to suspend=y to
    # debug application startup, but revert to suspend=n before final checkin.
    JAVA_TOOL_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8000
fi

# Have to externalize bc-fips jars
exec java $JAVA_OPTS $JAVA_TOOL_OPTS \
   -XX:+CreateCoredumpOnCrash -cp "./lib/bcfips/*" \
   --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
   --add-opens=java.base/java.net=ALL-UNNAMED \
   --add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED \
   -Dorg.bouncycastle.fips.approved_only=true \
   -Dorg.bouncycastle.jsse.client.dh.unrestrictedGroups=true \
   -Djavax.net.ssl.trustStorePassword=changeit \
   -Xms4g \
   -Xmx8g \
   -Djava.library.path=lib \
   -jar $jarfilename &

JAVA_PID=$!
export JAVA_PID
echo "Started Java application with PID: $JAVA_PID"

# Start monitoring in background if logging is enabled (after Java starts)
if [[ $ELASTIC_API_KEY ]]; then
    monitor_beats "$JAVA_PID" &
    echo "Started beat monitoring with Java PID: $JAVA_PID"
fi

# Wait for the Java process to finish
wait $JAVA_PID

