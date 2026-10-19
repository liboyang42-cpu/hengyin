#!/usr/bin/env bash
set -euo pipefail
# Additive W19 gate: keep upstream suite registration intact. Call in the approved local MySQL job.
: "${CHENGYIN_FUNDS_MYSQL_IT:?explicit MySQL gate required}"
: "${CHENGYIN_TEST_MYSQL_HOST:?explicit localhost required}"
: "${CHENGYIN_TEST_MYSQL_PORT:?explicit scratch port required}"
: "${CHENGYIN_TEST_MYSQL_USER:?explicit scratch user required}"
[[ "${CHENGYIN_TEST_MYSQL_PASSWORD+x}" == x ]] || exit 2
[[ "$CHENGYIN_FUNDS_MYSQL_IT" == 1 && "$CHENGYIN_TEST_MYSQL_HOST" =~ ^(localhost|127\.0\.0\.1)$ ]] || exit 2
reports=chengyinhub-admin/target/surefire-reports
# Only remove these generated reports; stale output must not satisfy either suite.
rm -f "$reports/TEST-com.chengyinhub.business.home.PrivateHomeMySqlIT.xml" \
  "$reports/TEST-com.chengyinhub.business.home.PrivateHomeRetentionMySqlIT.xml"
mvn -B -pl chengyinhub-admin -am test -Dtest=PrivateHomeMySqlIT,PrivateHomeRetentionMySqlIT -Dsurefire.failIfNoSpecifiedTests=false
python3 ci/private_home_mysql_it_gate.py "$reports"
