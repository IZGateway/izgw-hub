FROM ghcr.io/izgateway/alpine-node-openssl-fips:latest

RUN apk update \
    && apk upgrade --no-cache \
    && apk add --no-cache openjdk21-jre mariadb-client mariadb-connector-c-dev tini \ 
    && npm upgrade -g \
    && npm outdated -g

# Define arguments (set in izgateway pom.xml)
ARG JAR_FILENAME
ARG IZGW_VERSION

EXPOSE 443 9081 8000

COPY docker/data/*beat.yml /usr/share/izgateway/

# Install logrotate
COPY docker/data/logrotate.conf /etc/logrotate.conf
RUN (crontab -l 2>/dev/null; echo "*/15 * * * * /etc/periodic/daily/logrotate") | crontab -

WORKDIR /

# Install filebeat
RUN rm -f /filebeat/filebeat.yml && cp /usr/share/izgateway/filebeat.yml /filebeat/ \
    && rm -f /metricbeat/metricbeat.yml && cp /usr/share/izgateway/metricbeat.yml /metricbeat/ \
    && mv /etc/dnsmasq.conf /etc/dnsmasq.conf.bkup \
    && echo 'cache-size=10000' > /etc/dnsmasq.conf

# Define working directory
WORKDIR /usr/share/izgateway/

# Create lib and webapp directory
RUN mkdir lib webapp webapp/static webapp/static/images 

# Add AWS Aurora cert to java keystore and update java.security
COPY docker/data/java.security /usr/lib/jvm/java-17-openjdk/conf/security/
COPY docker/data/*.der /usr/lib/jvm/java-17-openjdk/jre/lib/security/

WORKDIR /usr/share/izgateway/
# Add jar and run script
COPY target/$JAR_FILENAME app.jar

# Ensure we only use NIST certified publicly available BC-FIPS packages
COPY docker/data/lib/bcfips/*.jar /usr/share/izgateway/lib/bcfips/

COPY docker/fatjar-run.sh run1.sh

# Remove carriage returns from batch file (for build on WinDoze).
RUN tr -d '\r' <run1.sh >run.sh && rm run1.sh && chmod u+r+x run.sh

# Update base keystore in cacerts by adding AWS Certificate and converting to BCFKS format
WORKDIR /usr/lib/jvm/java-17-openjdk/jre/lib/security
RUN keytool -keystore cacerts -storepass changeit -noprompt -trustcacerts -importcert -alias awscert -file certificate.der \ 
    && BC_FIPS_JAR=$(find /usr/share/izgateway/lib/bcfips/ -name "bc-fips-*.jar" -type f | head -n1) \
    && keytool -importkeystore -srckeystore cacerts -srcstoretype JKS -srcstorepass changeit \
      -destkeystore jssecacerts -deststorepass changeit -deststoretype BCFKS -providername BCFIPS \
      -provider org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider \
      -providerpath "$BC_FIPS_JAR"

WORKDIR /usr/share/izgateway/

ENV IZGW_VERSION=$IZGW_VERSION  
# run app on startup
ENTRYPOINT ["/sbin/tini", "--", "sh", "-c", "crond && exec bash run.sh app.jar"]
