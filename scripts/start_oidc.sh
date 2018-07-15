#!/bin/bash

set -xeu

export ISSUER_URI="https://oidc10.oms.identity.team"
export JBP_CONFIG_SPRING_AUTO_RECONFIGURATION="'[enabled: false]'"
export JBP_CONFIG_TOMCAT="{tomcat: { version: 8.0.+ }}"
export LOGIN_URL="http://oidc10.oms.identity.team"
export LOGIN_ENTITYBASEURL="https://oidc10.oms.identity.team"
export LOGIN_ENTITYID="https://oidc10.oms.identity.team"
export UAA_URL="http://oidc10.oms.identity.team"

