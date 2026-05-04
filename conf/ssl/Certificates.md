# SSL Certificates for Unit and Integration Testing

This folder contains keystores suitable **only** for running unit and integration tests
locally or in CI/CD. These files **cannot** be used to connect to any deployed environment
(dev, test, mock, demo, or production).

## Why these certificates cannot reach deployed environments

IZ Gateway deployments in AWS do not use the certificates stored here. Instead:

- **Keystores** are provisioned at deploy time onto an **AWS EFS volume** mounted at
  `/usr/share/phiz-web-ws/conf/ssl` inside each ECS container. The `security.ssl-path`
  environment variable in every task definition points to that EFS mount, not to
  anything in this repository.
- **TLS certificates** for `dev.izgateway.org` and all other hostnames are managed by
  **AWS Certificate Manager (ACM)** and attached to the Application Load Balancer.
- The self-signed certificate in `awsdev_keystore.bcfks` (SHA-256 fingerprint
  `FDE6:…:11EF`) is **entirely different** from the certificate presented by the running
  `dev.izgateway.org` service (`5763a73c…502`). The private key in this store cannot
  impersonate or connect to any live IZ Gateway endpoint.

## Keystore files

### awsdev_keystore.bcfks
Key and Trust Store used by the IZ Gateway server during unit/integration tests, and as
the Key Store for outbound connections under test.

- Contains a self-signed `dev.izgateway.org` certificate (test use only)
- Trusts the self-signed `dev.izgateway.org` certificate
- Trusts certificates signed by the IZ Gateway CA

### izgw_client_trust.bcfks
Trust store used for outbound connections during unit/integration tests.

- Trusts the self-signed `dev.izgateway.org` certificate
- Trusts the various root CAs used for connections to IIS endpoints

> **Note:** The `az_root` CA certificate (Arizona IIS) in `izgw_client_trust.bcfks`
> expired May 2025. Connections to Arizona IIS will fail until this is renewed on the
> EFS volume used by deployed services.
