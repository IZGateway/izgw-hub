FROM ghcr.io/izgateway/alpine-node-openssl-fips:latest

RUN apk update
RUN apk upgrade --no-cache
RUN apk add --no-cache openjdk17-jre mariadb-client mariadb-connector-c-dev 
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

# Install filebeat
RUN rm -f /filebeat/filebeat.yml && cp /usr/share/izgateway/filebeat.yml /filebeat/ 
RUN rm -f /metricbeat/metricbeat.yml && cp /usr/share/izgateway/metricbeat.yml /metricbeat/
    
# Install NPM
RUN npm install -g npm@8.19.2

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
# And essential JAR files for run script
COPY docker/data/lib/*.jar lib/

# Ensure we only use NIST certified publicly available BC-FIPS packages
ADD docker/data/bc-fips-1.0.2.5.jar bc-fips-1.0.2.5.jar
ADD docker/data/bcpkix-fips-1.0.7.jar bcpkix-fips-1.0.7.jar
ADD docker/data/bctls-fips-2.0.19.jar bctls-fips-2.0.19.jar

ADD docker/fatjar-run.sh run1.sh
ADD docker/izgwdb.sh izgwdb1.sh

# Remove carriage returns from batch file (for build on WinDoze).
RUN tr -d '\r' <run1.sh >run.sh
RUN rm run1.sh
RUN tr -d '\r' <izgwdb1.sh >izgwdb.sh
RUN rm izgwdb1.sh

# Make scripts executable
RUN ["chmod", "u+r+x", "run.sh"]
RUN ["chmod", "u+r+x", "izgwdb.sh"]

# Update base keystore in cacerts by adding AWS Certificate and converting to BCFKS format
WORKDIR /usr/lib/jvm/java-17-openjdk/jre/lib/security
RUN keytool -keystore cacerts -storepass changeit -noprompt -trustcacerts -importcert -alias awscert -file certificate.der 
RUN keytool -importkeystore -srckeystore cacerts -srcstoretype JKS -srcstorepass changeit \
      -destkeystore jssecacerts -deststorepass changeit -deststoretype BCFKS -providername BCFIPS \
      -provider org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider \
      -providerpath /usr/share/izgateway/bc-fips-1.0.2.4.jar

WORKDIR /usr/share/izgateway/

ENV IZGW_VERSION=$IZGW_VERSION  
# run app on startup
ENTRYPOINT ["sh","-c","crond && bash run.sh app.jar"]
