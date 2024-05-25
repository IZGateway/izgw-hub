#!/bin/bash

# This script is to grant all privileges to "remoteHub" user in order for the DB migration to complete successfully
mysql -h localhost -u root -D phiz --password="root" < data/grant-access.sql


