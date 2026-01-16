FROM ghcr.io/izgateway/alpine-node-openssl-fips:latest

RUN apk update
RUN apk upgrade --no-cache
RUN apk add --no-cache openjdk21-jre mariadb-client mariadb-connector-c-dev 
RUN npm upgrade -g
RUN npm outdated -g 

# Define arguments (set in izgateway pom.xml)
ARG JAR_FILENAME
ARG JAR_RUN_SCRIPT
ARG IZGW_VERSION

EXPOSE 443
EXPOSE 9081

# Enable Remote Debugging
EXPOSE 8000

COPY docker/data/filebeat.yml /usr/share/izgateway/
COPY docker/data/metricbeat.yml /usr/share/izgateway/

# Install logrotate
RUN rm /etc/logrotate.conf
COPY docker/data/logrotate.conf /etc/logrotate.conf
RUN (crontab -l 2>/dev/null; echo "*/15 * * * * /etc/periodic/daily/logrotate") | crontab -

WORKDIR /

# Install tini
RUN apk add --no-cache tini

# Install filebeat
RUN rm -f /filebeat/filebeat.yml && cp /usr/share/izgateway/filebeat.yml /filebeat/ 
RUN rm -f /metricbeat/metricbeat.yml && cp /usr/share/izgateway/metricbeat.yml /metricbeat/
    
#Rename default dnsmasq file to make sure dnsmasq does not read its entries
RUN mv /etc/dnsmasq.conf /etc/dnsmasq.conf.bkup
RUN echo 'cache-size=10000' > /etc/dnsmasq.conf

# Define working directory
WORKDIR /usr/share/izgateway/

# Create lib and webapp directory
RUN mkdir lib
RUN mkdir webapp
RUN mkdir webapp/static
RUN mkdir webapp/static/images

# Add AWS Aurora cert to java keystore and update java.security
COPY docker/data/java.security /usr/lib/jvm/java-17-openjdk/conf/security/
COPY docker/data/*.der /usr/lib/jvm/java-17-openjdk/jre/lib/security/

WORKDIR /usr/share/izgateway/
# Add jar and run script
ADD target/$JAR_FILENAME app.jar

# Ensure we only use NIST certified publicly available BC-FIPS packages
COPY docker/data/lib/bcfips/*.jar /usr/share/izgateway/lib/bcfips/

ADD docker/fatjar-run.sh run1.sh

# Remove carriage returns from batch file (for build on WinDoze).
RUN tr -d '\r' <run1.sh >run.sh
RUN rm run1.sh

# Make scripts executable
RUN ["chmod", "u+r+x", "run.sh"]

# Update base keystore in cacerts by adding AWS Certificate and converting to BCFKS format
WORKDIR /usr/lib/jvm/java-17-openjdk/jre/lib/security
RUN keytool -keystore cacerts -storepass changeit -noprompt -trustcacerts -importcert -alias awscert -file certificate.der 
RUN BC_FIPS_JAR=$(find /usr/share/izgateway/lib/bcfips/ -name "bc-fips-*.jar" -type f | head -n1) && \
    keytool -importkeystore -srckeystore cacerts -srcstoretype JKS -srcstorepass changeit \
      -destkeystore jssecacerts -deststorepass changeit -deststoretype BCFKS -providername BCFIPS \
      -provider org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider \
      -providerpath "$BC_FIPS_JAR"

WORKDIR /usr/share/izgateway/

ENV IZGW_VERSION=$IZGW_VERSION  
# run app on startup
ENTRYPOINT ["/sbin/tini", "--", "sh", "-c", "crond && exec bash run.sh app.jar"]
