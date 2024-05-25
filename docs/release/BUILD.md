# IZ Gateway 2.0 Build Process

IZ Gateway uses Maven to build, package and generate the Docker image.  IZ Gateway 2.0 requires the Java 17 JDK to be installed.  Set JAVA_HOME to the JDK image.  Unit tests load and test the IZ Gateway application. Provide deployment
specific content into the /conf/ssl folder in the project. 

## Download
IZ Gateway source code can be downloaded using the following command:

```
git clone https://github.com/IZGateway/izgateway.git
```

## Package
To build the IZ Gateway JAR file for debugging, run the following commands.

```
set JAVA_HOME=<location-of-java-17-jdk>
mvn package 
```

## Install
To build the IZ Gateway Docker Image for testing or deployment, run the following commands.

```
set JAVA_HOME=<location-of-java-17-jdk>
set COMMON_PASS=<password-of-server-key-store>
mvn install
```

## Configuration
The IZ Gateway application is configured with the following files:

(application.yml)[src/main/resources/application.yml] The IZ Gateway configuration file.
(logback-spring.xml)[src/main/resources/logback-spring.xml] The logging configuration file.
