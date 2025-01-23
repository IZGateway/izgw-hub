#!/bin/bash

# Create a shared network if it does not exist
NETWORK_NAME="izg-shared-network"

if ! docker network ls | grep -q "$NETWORK_NAME"; then
  echo "Network $NETWORK_NAME does not exist. Creating it..."
  docker network create "$NETWORK_NAME"
else
  echo "Network $NETWORK_NAME already exists."
fi

# Run docker-compose
docker-compose -f docker-compose.yaml up -d
