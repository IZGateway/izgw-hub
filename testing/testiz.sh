#!bash
ENV=env/local.postman_environment.json
IZGW_SSL_CLIENT_CERT=newman
newman run "collections/IZGW_Integration_Test.postman_collection.json" -n 1 --environment $ENV --ssl-extra-ca-certs ./certs/izgwroot.pem --ssl-client-cert $IZGW_SSL_CLIENT_CERT.pem --ssl-client-key $IZGW_SSL_CLIENT_CERT.key --ssl-client-passphrase $IZGW_SSL_CLIENT_PASSPHRASE --insecure --reporters cli,junitfull --reporter-junitfull-export logs/integration-test.xml
