#!/bin/bash

cd /usr/share/izgateway

# Get Service Name from AWS for log reporting.
SERVICE_NAME=unknown
if [[ $ECS_CONTAINER_METADATA_URI_V4 ]]
then
    SERVICE_NAME=`curl -s $ECS_CONTAINER_METADATA_URI_V4 | jq -r '.Labels["com.amazonaws.ecs.container-name"]'`
fi
export SERVICE_NAME

# If an API Key has been given, then send data to elastic search.
if [[ $ELASTIC_API_KEY ]]
then
    filebeat -e &
    echo Started Filebeat
else
    echo Filebeat logging not enabled
fi

if [[ $ELASTIC_API_KEY ]]
then
    metricbeat -e &
    echo Started MetricBeat
else
    echo MetricBeat logging not enabled
fi


jarfilename=$1

#Save original nameserver in temp file and empty contents
echo nameserver 127.0.0.1 > /tmp/newresolv.conf
cat /etc/resolv.conf >> /tmp/newresolv.conf
truncate -s 0 /etc/resolv.conf
cat /tmp/newresolv.conf > /etc/resolv.conf

#Start dnsmasq as root
dnsmasq  --use-stale-cache=0 --log-queries=extra --user=root --log-facility=/var/log/dnsmasq.log

# Backup the database on upgrade if one does not already exist. IZGW_VERSION environment variable set in Docker image
BACKUP_PREFIX=pre-$IZGW_VERSION-upgrade-backup
if [[ ! $MYSQL_DB_NAME ]] 
then
   MYSQL_DB_NAME=phiz
fi

FILE=`ls -1 conf/backups/$BACKUP_PREFIX-$MYSQL_DB_NAME*.sql 2>/dev/null | head -1`
if [[ ! -e $FILE ]]
then
    ./izgwdb.sh backup $BACKUP_PREFIX || exit 1 
fi


# Enable remote debugging if DEBUG is set in the environment
JAVA_TOOL_OPTS=
if [[ $DEBUG ]]
then
    # To enable remote debugging, set JAVA_OPTS=-Xdebug -Xrunjdwp:transport=dt_socket,address=*:8000,server=y,suspend=n
    # NOTE: The * below is important.  It enables your debugger to attach through whatever IP Address Docker is using
    # to recieve connections on port 8000.  If not used, then only connections from 127.0.0.1 are accepted, and while
    # you may think your IP Address of the host is 127.0.0.1, it's not inside the container.  Change suspend=n to suspend=y to
    # debug application startup, but revert to suspend=n before final checkin.
    JAVA_TOOL_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8000
fi

# Have to externalize bc-fips jars
java $JAVA_OPTS $JAVA_TOOL_OPTS -javaagent:lib/aspectjweaver-1.9.6.jar -javaagent:lib/spring-instrument-5.3.8.jar \
   -XX:+CreateCoredumpOnCrash -cp ./bc-fips-1.0.2.4.jar:./bcpkix-fips-1.0.7.jar:./bctls-fips-1.0.16.jar \
   --add-opens jdk.management/com.sun.management.internal=ALL-UNNAMED \
   --add-opens java.base/java.lang=ALL-UNNAMED \
   --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
   --add-opens java.base/java.io=ALL-UNNAMED \
   --add-opens java.base/java.util=ALL-UNNAMED \
   --add-opens java.base/javax.net.ssl=ALL-UNNAMED \
   --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
   --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED \
   --add-opens jdk.management/com.sun.management.internal=ALL-UNNAMED \
   --add-opens java.base/java.io=ALL-UNNAMED \
   --add-opens java.base/sun.security.internal.spec=ALL-UNNAMED \
   --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
   --add-opens java.base/java.net=ALL-UNNAMED \
   --add-opens java.base/sun.reflect.annotation=ALL-UNNAMED \
   -Dorg.bouncycastle.fips.approved_only=true \
   -Dorg.bouncycastle.jsse.client.dh.unrestrictedGroups=true \
   -Djavax.net.ssl.trustStorePassword=changeit \
   -Xms4g \
   -Xmx8g \
   -Djava.library.path=lib \
   -jar $jarfilename

   