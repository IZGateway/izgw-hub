# syntax=docker/dockerfile:1
FROM ghcr.io/izgateway/alpine-node-openssl-fips:latest

# Install dependencies and clean up in one layer
RUN apk update && \
    apk upgrade --no-cache && \
    apk add --no-cache openjdk21-jre mariadb-client mariadb-connector-c-dev tini && \
    npm upgrade -g && \
    npm outdated -g && \
    rm -rf /var/cache/apk/*

# Set up logrotate and crontab
COPY docker/data/logrotate.conf /etc/logrotate.conf
RUN (crontab -l 2>/dev/null; echo "*/15 * * * * /etc/periodic/daily/logrotate") | crontab -

# Expose ports
EXPOSE 443 9081 8000

# Copy beat configuration files
COPY docker/data/*beat.yml /usr/share/izgateway/

# Set up filebeat, metricbeat and dnsmasq configs
RUN rm -f /filebeat/filebeat.yml && cp /usr/share/izgateway/filebeat.yml /filebeat/ && \
    rm -f /metricbeat/metricbeat.yml && cp /usr/share/izgateway/metricbeat.yml /metricbeat/ && \
    mv /etc/dnsmasq.conf /etc/dnsmasq.conf.bkup && \
    echo 'cache-size=10000' > /etc/dnsmasq.conf && \
    mkdir -p /usr/share/izgateway/lib/bcfips /usr/share/izgateway/webapp/static/images

# Add AWS Aurora cert and update java.security
COPY docker/data/java.security /usr/lib/jvm/java-21-openjdk/conf/security/
COPY docker/data/*.der /usr/lib/jvm/java-21-openjdk/lib/security/

COPY docker/data/lib/bcfips/*.jar /usr/share/izgateway/lib/bcfips/

ADD docker/fatjar-run.sh /usr/share/izgateway/run1.sh

# Remove carriage returns and make script executable
RUN tr -d '\r' </usr/share/izgateway/run1.sh >/usr/share/izgateway/run.sh && \
    rm /usr/share/izgateway/run1.sh && \
    chmod u+r+x /usr/share/izgateway/run.sh

# Update keystore
WORKDIR /usr/lib/jvm/java-21-openjdk/lib/security/
RUN keytool -keystore cacerts -storepass changeit -noprompt -trustcacerts -importcert -alias awscert -file certificate.der && \
    BC_FIPS_JAR=$(find /usr/share/izgateway/lib/bcfips/ -name "bc-fips-*.jar" -type f | head -n1) && \
    keytool -importkeystore -srckeystore cacerts -srcstoretype JKS -srcstorepass changeit \
      -destkeystore jssecacerts -deststorepass changeit -deststoretype BCFKS -providername BCFIPS \
      -provider org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider \
      -providerpath "$BC_FIPS_JAR"

# Add jar and run script
ARG JAR_FILENAME
ADD target/$JAR_FILENAME /usr/share/izgateway/app.jar

WORKDIR /usr/share/izgateway/
ENV IZGW_VERSION=$IZGW_VERSION

ENTRYPOINT ["/sbin/tini", "--", "sh", "-c", "crond && exec bash run.sh app.jar"]
