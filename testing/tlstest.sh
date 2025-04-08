#!/bin/bash
IZGW_BK=" -cert certs/unknown.pem -key certs/unknown.key "
IZGW_BC=" -cipher ECDHE-ECDSA-AES256-SHA384:ECDHE-RSA-AES256-SHA384:DHE-RSA-AES256-SHA256:ECDHE-ECDSA-AES128-SHA256:ECDHE-RSA-AES128-SHA256:DHE-RSA-AES128-SHA256:ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA:ECDHE-ECDSA-AES128-SHA:ECDHE-RSA-AES128-SHA:AES256-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305 "
IZGW_GC=" -cipher GCM:AESGCM:!PSK:!DSS:!RSA:!ADH"
IZGW_GK=" -cert newman.pem -key newman.key -pass env:IZGW_SSL_CLIENT_PASSPHRASE "
IZGW_CA=" -servername prod.phiz-project.org -CAfile certs/izgwroot.pem "
IZGW_EP=" -connect izgateway-dev-nlb-e9941b47428f1e12.elb.us-east-1.amazonaws.com:443 "
#IZGW_EP=" -connect localhost:443 "
CHECK="echo GET /IISHubService?wsdl "
FAILURES=0

#set -x
openssl -v > ./logs/openssl-version.txt 2>&1

echo
TEST="openssl s_client $IZGW_CA $IZGW_GK -tls1 $IZGW_EP" > ./logs/tls1.openssl.txt 2>&1
$CHECK | $TEST >> ./logs/tls1.openssl.txt 2>&1
ALERT="`grep -E 'SSL alert number 70|no protocols' ./logs/tls1.openssl.txt`"
if [ "$ALERT" = "" ]; then echo -n FAIL; FAILURES=$(( $FAILURES + 1)); else echo -n PASS; fi
echo -e ":\tTLS v1.0 Test Good Key, Good Cipher fails on TLS Version:\n\t  TLS 1.0 SHOULD be rejected with Alert 70 or no protocols available\n$TEST"

echo
TEST="openssl s_client $IZGW_CA $IZGW_GK -tls1_1 $IZGW_EP"
echo $TEST > ./logs/tls1_1.openssl.txt 2>&1
$CHECK | $TEST>> ./logs/tls1_1.openssl.txt 2>&1
ALERT="`grep -E 'SSL alert number 70|no protocols' ./logs/tls1_1.openssl.txt`"
if [ "$ALERT" = "" ]; then echo -n FAIL; FAILURES=$(( $FAILURES + 1)); else echo -n PASS; fi
echo -e ":\tTLS v1.1 Test Good Key, Good Cipher fails on TLS Version:\n\t  TLS 1.1 SHOULD be rejected with Alert 70 or no protocols available\n$TEST"

echo
TEST="openssl s_client -tls1_2 $IZGW_CA $IZGW_GK $IZGW_GC $IZGW_EP"
echo $TEST > ./logs/good.openssl.txt 2>&1
$CHECK | $TEST >> ./logs/good.openssl.txt 2>&1
PASS=$?
ALERT="`grep -e "SSL alert" ./logs/good.openssl.txt`"
if [ "$PASS" != "0" -o "$ALERT" != "" ]; then echo -n FAIL; FAILURES=$(( $FAILURES + 1)); else echo -n PASS; fi
echo -e ":\tTLS v1.2 Test Good Key, Good Cipher Succeeds:\n\t  Good Key and Cipher SHOULD succeed\n$TEST"

echo
TEST="openssl s_client -tls1_3 $IZGW_CA $IZGW_GK $IZGW_GC $IZGW_EP"
echo $TEST > ./logs/good1-3.openssl.txt 2>&1
$CHECK | $TEST >> ./logs/good1-3.openssl.txt 2>&1
PASS=$?
ALERT="`grep -e "SSL alert" ./logs/good1-3.openssl.txt`"
if [ "$PASS" != "0" -o "$ALERT" != "" ]; then echo -n FAIL; FAILURES=$(( $FAILURES + 1)); else echo -n PASS; fi
echo -e ":\tTLS v1.3 Test Good Key, Good Cipher Succeeds:\n\t  Good Key and Cipher SHOULD succeed\n$TEST"

echo
TEST="openssl s_client -tls1_2 $IZGW_CA $IZGW_BK $IZGW_BC $IZGW_EP"
echo $TEST > ./logs/badkey2.openssl.txt 2>&1
$CHECK | $TEST >> ./logs/badkey2.openssl.txt 2>&1
ALERT="`grep -e 'SSL alert number 40' ./logs/badkey2.openssl.txt`"
if [ "$ALERT" = "" ]; then echo -n FAIL; FAILURES=$(( $FAILURES + 1)); else echo -n PASS; fi
echo -e ":\tCIPHER Test Bad Key, Bad Cipher fails on Bad Cipher:\n\t  Bad Cipher SHOULD be rejected with Alert 40\n$TEST"

echo
TEST="openssl s_client -tls1_2 $IZGW_CA $IZGW_GK $IZGW_BC $IZGW_EP"
echo $TEST > ./logs/cipher1.openssl.txt 2>&1
$CHECK | $TEST >> ./logs/cipher1.openssl.txt 2>&1
ALERT="`grep -e 'SSL alert number 40' ./logs/cipher1.openssl.txt`"
if [ "$ALERT" = "" ]; then echo -n FAIL; FAILURES=$(( $FAILURES + 1)); else echo -n PASS; fi
echo -e ":\tCIPHER Test Good Key, Bad Cipher fails on Bad Cipher:\n\t  Bad Cipher SHOULD be rejected with Alert 40\n$TEST"

echo
echo "Failures: $FAILURES";

exit $FAILURES
