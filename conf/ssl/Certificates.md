This folder contains keys and certificates suitable for running unit and integration tests but 
is not intended for production use.  The IZ Gateway private key is simply a self-signed 
certificate for dev.izgateway.org.  This certificate is both used and trusted by the 
implementation so that it can make web service calls to itself.

awsdev_keystore.bcfks 
- Key and Trust Store used for the IZ Gateway Server, and Key Store used for outbound connections.
- Trusts dev.izgateway.org certificate
- Trusts certificates signed by the IZ Gateway CA
- Contains self-signed dev.izgateway.org certificate

izgw_client_trust.bcfks
- Trust store used for outbound connections. 
- Trusts dev.izgateway.org certificate 
- Also trusts the various root CAs used for connections to IIS
