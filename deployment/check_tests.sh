#!/usr/bin/env bash

# replace dots with hyphens in APP_NAME
APP_NAME=$1
SEBASTOPOL_IP=$2

CURL_OUT=./curl_result
TIMEOUT=2m

touch $CURL_OUT

tail -f $CURL_OUT &

echo "Waiting for integration runner to be healthy"
HEALTHY=/bin/false
until HEALTHY; do
  HEALTH=$(timeout $TIMEOUT "curl -X GET http://$SEBASTOPOL_IP:9501/marathon/$APP_NAME/health -H \"$SEKRIT_HEADER: 123\"")
  if [ "$HEALTH" == "healthy" ]
  then
     HEALTHY=/bin/true
  fi
done

STATUS=$(timeout $TIMEOUT "curl -s -o $CURL_OUT -X GET http://$SEBASTOPOL_IP:9501/marathon/$APP_NAME/file -H \"$SEKRIT_HEADER: 123\"")

if [ "$STATUS" == "200" ]
then exit 0
else exit 1
fi



