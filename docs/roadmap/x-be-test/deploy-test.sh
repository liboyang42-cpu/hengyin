#!/bin/bash
# x-be-test: put feature/r-x-be-test (integration/backend-app-r3 + roadmap/be-integ) on TEST.
#
#   ssh work 'bash -s' < deploy-test.sh
#
# TEST = the be-int stack on the work VPS: jar $B/app/app.jar on port 8120, database cy_e2e_r2 in be-int-mysql.
# Safe to run blind and safe to run twice:
#   - nothing is changed before the build, the jar size check and a verified database dump;
#   - every migration is idempotent, and the first failing one stops the run before the jar is swapped;
#   - the running jar is kept as a rollback copy (never overwritten), and is put back if the new jar does not start.
# It never prints environment values or secrets, never touches $B/hengyin (the backend integrator's checkout),
# other containers or other databases, and never runs a global cleanup.
#
# Space (2026-11-21): /data is ~97% full (~700 MB free), so the clone, the Maven build and the dump go under
# /var/tmp/x-be-test (root filesystem). Only the new jar, its rollback copy and the log go under /data.
#
# Optional environment (names only; values are never printed):
#   RUNNING_SHA=<short sha>   name for the rollback copy of the running jar, when it cannot be worked out
#   RUN_MYSQL_IT=1            also run the env-gated MySQL integration tests against be-int-mysql; needs the same
#                             CHENGYIN_* test-database variables the integrator exports for Wave 9
#
# The whole script is one function so that `bash -s` has read all of it before anything runs, and nothing
# below can swallow the rest of the script from stdin.

main() {
set -euo pipefail
exec </dev/null

# ---------------------------------------------------------------- pinned target (verified in the cloud run)
local SHA=PINNED_SHA_PLACEHOLDER
local BRANCH=feature/r-x-be-test
local REPO=https://github.com/liboyang42-cpu/hengyin

local B=/data/workspace/be-int
local W=$B/x-be-test               # log, install record (small; /data is nearly full)
local V=/var/tmp/x-be-test         # clone, Maven output, Maven fallback repo, database dump
local APP=$B/app
local PORT=8120
local DB=cy_e2e_r2
local MYSQL=be-int-mysql
local TS; TS=$(date -u +%Y%m%dT%H%M%SZ)
local MIN_ROOT_MB=1024
local MIN_APP_MB=300

mkdir -p "$W" "$V"
local LOG=$W/deploy-$TS.log
exec > >(tee -a "$LOG") 2>&1
say() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { say "STOP: $*"; say "RESULT: NOT DEPLOYED (the running jar was not replaced)"; exit 1; }
free_mb() { df -Pm "$1" | awk 'NR==2 {print $4}'; }
need_space() { # path min_mb what
  local f; f=$(free_mb "$1")
  say "free on $(df -P "$1" | awk 'NR==2 {print $6}') for $3: ${f} MB (need ${2} MB)"
  [ "$f" -ge "$2" ] || die "not enough space for $3: ${f} MB free under $1, need ${2} MB"
}
table_count() {
  docker exec "$MYSQL" mysql -uroot -N -e "select count(*) from information_schema.tables where table_schema='$DB'"
}
java_pids() { pgrep -f "[a]pplication-beint.properties" || true; }
port_bound() { ss -ltn | grep -q ":$PORT "; }

say "x-be-test deploy $TS, target $BRANCH @ $SHA, log $LOG"

# A second copy of this script must not run at the same time (the Maven and deploy locks are shared with others).
exec 8>"$V/.run.lock"
/usr/bin/flock -n 8 || die "another deploy-test.sh run holds $V/.run.lock"

# ---------------------------------------------------------------- preflight (read-only)
for tool in git docker mvn java ss pgrep curl gzip; do command -v "$tool" >/dev/null || die "missing tool: $tool"; done
[ -x /usr/bin/flock ] || die "missing /usr/bin/flock"
[ -d "$APP" ] && [ -f "$APP/app.jar" ] && [ -f "$APP/start.sh" ] || die "expected $APP/app.jar and $APP/start.sh"
docker exec "$MYSQL" mysql -uroot -N -e "select 1" >/dev/null || die "cannot reach $MYSQL as root"
say "java: $(java -version 2>&1 | head -1)"
say "maven: $(mvn -v 2>/dev/null | head -1)"
say "tables in $DB now: $(table_count)"

# ---------------------------------------------------------------- source: fresh blob-less clone, pinned commit
need_space "$V" "$MIN_ROOT_MB" "the clone and the build"
local SRC=$V/src
rm -rf "$SRC"
say "cloning $REPO ($BRANCH, blob-less) into $SRC"
git clone -q --filter=blob:none --no-checkout --branch "$BRANCH" "$REPO" "$SRC" \
  || die "git clone failed (network or GitHub credentials on this host)"
git -C "$SRC" checkout -q --detach "$SHA" || die "commit $SHA is not on $BRANCH"
say "source at $(git -C "$SRC" rev-parse HEAD) ($(git -C "$SRC" log -1 --format=%s | cut -c1-80))"

# ---------------------------------------------------------------- build (TEST profile with the e2e providers)
# -Pe2e-test-providers: the TEST stack signs in e2e-code-* test accounts (r3 B4); on this line those providers
# live in chengyinhub-e2e, which only this profile packages. Never use this profile for production.
local MVN_ARGS=(-B -q -DskipTests -Pe2e-test-providers package -pl chengyinhub-admin -am)
local BUILD_LOG=$V/build-$TS.log
build() { # extra maven args...
  ( cd "$SRC" && LANG=C.UTF-8 LC_ALL=C.UTF-8 MAVEN_OPTS="-Xmx1536m" \
      /usr/bin/flock /data/workspace/.mvn.lock mvn "$@" "${MVN_ARGS[@]}" ) >"$BUILD_LOG" 2>&1
}
say "building offline (waits for /data/workspace/.mvn.lock)"
if ! build -o; then
  if grep -qE "offline mode|has not been downloaded from it before|could not be resolved|Could not resolve dependencies" "$BUILD_LOG" \
     && ! grep -q "COMPILATION ERROR" "$BUILD_LOG"; then
    say "offline build is missing artifacts:"
    grep -oE "[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:(jar|pom):[A-Za-z0-9_.-]+" "$BUILD_LOG" | sort -u | head -20 || true
    # The online retry must not fill /data: it works on a copy of ~/.m2/repository under /var/tmp.
    local M2SRC=${HOME}/.m2/repository M2=$V/m2repo
    need_space "$V" $(( MIN_ROOT_MB + $(du -sm "$M2SRC" 2>/dev/null | awk '{print $1}') )) "a copy of ~/.m2 for the online retry"
    rm -rf "$M2"; mkdir -p "$M2"; cp -a "$M2SRC/." "$M2/"
    say "retrying the build online once (local repo $M2)"
    build "-Dmaven.repo.local=$M2" || { tail -40 "$BUILD_LOG"; die "online build failed (log $BUILD_LOG)"; }
  else
    grep -E "ERROR" "$BUILD_LOG" | head -40 || true
    die "build failed for a reason other than missing artifacts (log $BUILD_LOG)"
  fi
fi
local JAR=$SRC/chengyinhub-admin/target/chengyinhub-admin.jar
[ -f "$JAR" ] || die "no jar at $JAR"
local JAR_BYTES; JAR_BYTES=$(stat -c %s "$JAR")
say "jar: $JAR_BYTES bytes"
[ "$JAR_BYTES" -ge 104857600 ] || die "jar is smaller than 100 MB"
if command -v unzip >/dev/null; then
  unzip -l "$JAR" | grep -q "BOOT-INF/lib/chengyinhub-e2e" \
    || die "chengyinhub-e2e is not in the jar (the TEST e2e-code-* sign-in would stop working)"
  say "jar contains chengyinhub-e2e (TEST-only providers)"
fi

# ---------------------------------------------------------------- optional: MySQL integration tests
if [ "${RUN_MYSQL_IT:-0}" = "1" ]; then
  say "RUN_MYSQL_IT=1: City MySQL integration tests against be-int-mysql"
  ( cd "$SRC" && LANG=C.UTF-8 LC_ALL=C.UTF-8 CHENGYIN_CITY_GAME_MYSQL_IT=1 \
      /usr/bin/flock /data/workspace/.mvn.lock mvn -B -o -DargLine=-Xmx1536m -pl chengyinhub-admin -am test \
      -Dtest='CityGameMysqlIntegrationTest,AccountDeregistrationR3ErasureMyBatisTest' -Dsurefire.failIfNoSpecifiedTests=false ) \
      >"$V/mysql-it-$TS.log" 2>&1 || { grep -E "Tests run|FAIL" "$V/mysql-it-$TS.log" | tail -10; die "MySQL integration tests failed"; }
  grep -E "Tests run:" "$V/mysql-it-$TS.log" | tail -3
else
  say "skipping the MySQL integration tests (they need the integrator's test-database variables; set RUN_MYSQL_IT=1 to run them)"
fi

# ---------------------------------------------------------------- backup before any change
need_space "$V" "$MIN_ROOT_MB" "the database dump"
local DUMP=$V/${DB}-$TS.sql.gz
say "dumping $DB to $DUMP"
docker exec "$MYSQL" mysqldump -uroot --single-transaction --routines --triggers "$DB" | gzip > "$DUMP" \
  || die "mysqldump failed"
[ -s "$DUMP" ] || die "dump is empty"
gzip -t "$DUMP" || die "dump is not a valid gzip file"
zcat "$DUMP" | tail -1 | grep -q "Dump completed" || die "dump did not complete"
say "dump ok: $(stat -c %s "$DUMP") bytes"

# ---------------------------------------------------------------- deploy (holds the shared deploy lock)
exec 9>/data/workspace/.deploy.lock
say "waiting for /data/workspace/.deploy.lock"
/usr/bin/flock 9

local SQL=$SRC/chengyinhub-admin/sql
# Section 3 of RESULT.md: every migration in this line that integration/backend-app-r3 does not have, in the order
# of ci/migration-dependencies.json (ci/migration_ledger.py). All are idempotent.
local PRE=(
  migration_20261115_admin_consoles.sql
  migration_20261115_game_loop.sql
  migration_20261115_guest_walk.sql
  migration_20261115_location_trust.sql
  migration_20261115_marketplace.sql
  migration_20261115_measurement.sql
  migration_20261115_merchant_reach.sql
  migration_20261115_money_pass.sql
  migration_20261115_points_governance.sql
  migration_20261115_sponsored_quests.sql
  migration_20261115_stagger_all_sys_jobs.sql
  migration_20261115_store_account.sql
  migration_20261115_store_safety.sql
  migration_20261115_external_games_ops.sql
  migration_20261116_merchant_review_sla.sql
  migration_20261115_im_conversation_page.sql
  migration_20261115_referral_bounty_review.sql
  migration_20261115_creator_earnings.sql
  migration_20261115_discovery.sql
  migration_20261115_daily_loop.sql
  migration_20261116_x_come_back_place_stamp.sql
  migration_20261116_journey_presence.sql
  migration_20261115_game_mechanics.sql
  migration_20261114_missing_topic_pricing_rule.sql
  migration_20261115_club_ops.sql
  migration_20261115_links_push.sql
  # TEST copy (docs/roadmap/x-be-test/sql/): the original's backfill fails with error 1055 under MySQL 8's default
  # ONLY_FULL_GROUP_BY; the copy groups by one more derived column and writes the same rows.
  fog_map_20261115.test.sql.txt
  migration_20261115_privacy_erasure.sql
  migration_20261115_seed_tools.sql
  migration_20261115_maps_backend.sql
  migration_20261114_roam_poi_tags_json.sql
  migration_20261116_template_games.sql
  migration_20261116_life_games.sql
  migration_20261115_money_switch.sql
  migration_20261115_collection_model.sql
  migration_20261115_club_dues.sql
  migration_20261115_merchant_plans.sql
  migration_20261115_hardening.sql
  migration_20261115_money_payout.sql
  migration_20261115_shop_games.sql
  migration_20261116_channel_events.sql
  migration_20261116_external_game_result_key.sql
  migration_20261116_shop_games_judged.sql
  migration_20261116_x_disclosures_data_copy.sql
  migration_20261117_shop_game_runner.sql
  migration_20261126_reward_drop_perm_union.sql
  migration_20261130_money_on_operator.sql
  migration_20261130_money_switch_two_person.sql
  migration_20261130_payout_bind_match.sql
  migration_20261115_external_games_ops_menus.sql
  migration_20261127_city_monopoly_claim_flow.sql
  migration_20261128_city_economy.sql
  # Already applied on TEST in Wave 9; idempotent. Run again after city_economy, which gives district-less
  # MONOPOLY deeds district d1, so those deeds also get their deed serial and every district has a row.
  migration_20261126_city_gaps_r3.sql
  # Declared after 20261115_club_reach (post-deploy below), but it only adds and fills im_channel.notify_default,
  # which the new jar reads; club_reach only inserts a paused sys_job row. So it runs before the swap.
  migration_20261116_channel_notify_default.sql
  migration_20261116_channels_messaging.sql
  migration_20261129_channel_create_event_bit.sql
  migration_20261129_club_invite.sql
  migration_20261115_workshop_iap.sql
)
# POST_DEPLOY_MIGRATIONS (ci/migration_ledger.py): sys_job rows naming beans only the new jar has. They run only
# after the new jar is up, so a rollback never restarts the old jar with job rows it cannot resolve.
local POST=(
  migration_20261115_club_reach.sql
  migration_20261128_channel_purge_job.sql
  migration_20261128_stagger_channel_purge_job.sql
  migration_20261115_growth_loops.sql
  migration_20261115_stagger_club_growth_jobs.sql
  migration_20261127_city_monopoly_jobs.sql
  migration_20261127_stagger_city_monopoly_jobs.sql
  migration_20261116_channel_membership_backfill.sql
  migration_20261116_stagger_channel_membership_job.sql
)
local f
local TESTSQL=$SRC/docs/roadmap/x-be-test/sql
sqlpath() { case "$1" in *.test.sql.txt) echo "$TESTSQL/$1" ;; *) echo "$SQL/$1" ;; esac; }
for f in "${PRE[@]}" "${POST[@]}"; do [ -f "$(sqlpath "$f")" ] || die "migration file missing in the clone: $f"; done

apply() { # file
  say "apply $1"
  if ! docker exec -i "$MYSQL" mysql -uroot --default-character-set=utf8mb4 "$DB" < "$(sqlpath "$1")" > "$V/mig-$TS-$1.out" 2>&1; then
    sed -n '1,20p' "$V/mig-$TS-$1.out"
    return 1
  fi
}

say "1. tables before: $(table_count)"
for f in "${PRE[@]}"; do
  apply "$f" || die "migration $f failed. The jar was NOT swapped; TEST still runs the old jar. Migrations before it stay (additive). The dump $DUMP holds the schema and data from before this run."
done
say "3. tables after the pre-deploy migrations: $(table_count)"

# 4. rollback copy of the running jar (never overwritten)
local CUR_SUM; CUR_SUM=$(sha256sum "$APP/app.jar" | awk '{print $1}')
local RUNNING=""
[ -f "$W/installed.txt" ] && RUNNING=$(awk -v s="$CUR_SUM" '$1==s {print $2}' "$W/installed.txt" | tail -1)
[ -n "$RUNNING" ] || RUNNING=${RUNNING_SHA:-}
[ -n "$RUNNING" ] || RUNNING=$(git -C "$B/hengyin" rev-parse --short=9 HEAD 2>/dev/null || true)
[ -n "$RUNNING" ] || RUNNING=unknown-$TS
local PREV=$APP/app.jar.prev-$RUNNING
if [ -e "$PREV" ]; then
  if [ "$(sha256sum "$PREV" | awk '{print $1}')" != "$CUR_SUM" ]; then
    PREV=$APP/app.jar.prev-$RUNNING-$TS
    need_space "$APP" "$MIN_APP_MB" "the rollback copy"
    cp -p "$APP/app.jar" "$PREV"
  fi
  say "4. rollback jar: $(basename "$PREV") (kept)"
else
  need_space "$APP" "$MIN_APP_MB" "the rollback copy"
  cp -p "$APP/app.jar" "$PREV"
  say "4. rollback jar: $(basename "$PREV")"
fi
[ "$(sha256sum "$PREV" | awk '{print $1}')" = "$CUR_SUM" ] || die "rollback copy does not match the running jar"

stop_app() {
  local pids=() i
  mapfile -t pids < <(java_pids)
  if [ "${#pids[@]}" -gt 0 ]; then say "stopping java pid(s) ${pids[*]}"; kill "${pids[@]}" || true; else say "no running java for application-beint.properties"; fi
  for i in $(seq 1 30); do port_bound || break; sleep 1; done
  if port_bound; then
    mapfile -t pids < <(java_pids)
    if [ "${#pids[@]}" -gt 0 ]; then say "port $PORT still bound after 30 s, sending SIGKILL"; kill -9 "${pids[@]}" || true; sleep 5; fi
  fi
  ! port_bound
}
start_app() { # log suffix for the previous app.log
  [ -f "$APP/app.log" ] && mv "$APP/app.log" "$APP/app.log.prev-$1"
  ( cd "$APP" && setsid nohup bash start.sh > app.log 2>&1 < /dev/null & )
}
wait_up() { # seconds
  local i code
  for i in $(seq 1 "$1"); do
    sleep 1
    grep -q "APPLICATION FAILED TO START" "$APP/app.log" 2>/dev/null && return 1
    if grep -q "Started RuoYiApplication" "$APP/app.log" 2>/dev/null; then
      code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1:$PORT/" || true)
      [ "$code" = "200" ] && { say "up after ${i}s: $(grep -m1 -o 'Started RuoYiApplication in [0-9.]* seconds' "$APP/app.log"), GET / = 200"; return 0; }
    fi
  done
  return 1
}
causes() { grep -h "Caused by" "$APP/app.log" 2>/dev/null | sed 's/^[[:space:]]*//' | cut -c1-240 | sort | uniq -c | sort -rn | head -15 || true; }
pii_presence() {
  local pid; pid=$(java_pids | head -1)
  [ -n "$pid" ] || return 0
  if tr '\0' '\n' < "/proc/$pid/environ" 2>/dev/null | grep -q '^CHENGYIN_WITHDRAWAL_PII_KEY='; then
    say "CHENGYIN_WITHDRAWAL_PII_KEY: present in the process"
  else
    say "CHENGYIN_WITHDRAWAL_PII_KEY: NOT present in the process (merchant identity and payout PII need it)"
  fi
}

# the new jar goes next to app.jar while the old one still runs, so the swap below is a rename
need_space "$APP" "$MIN_APP_MB" "the new jar"
cp "$JAR" "$APP/app.jar.new-$TS"
cmp -s "$JAR" "$APP/app.jar.new-$TS" || { rm -f "$APP/app.jar.new-$TS"; die "copy of the new jar is incomplete"; }
# 5. stop
if ! stop_app; then
  rm -f "$APP/app.jar.new-$TS"
  say "STOP: port $PORT is still bound after stopping java; app.jar is unchanged."
  say "Start it again with: cd $APP && setsid nohup bash start.sh > app.log 2>&1 < /dev/null &"
  say "RESULT: NOT DEPLOYED (TEST may be down)"
  exit 1
fi
# 6. swap and start
mv -f "$APP/app.jar.new-$TS" "$APP/app.jar"
say "6. new jar in place ($(stat -c %s "$APP/app.jar") bytes); starting"
start_app "x-be-test-$TS"
# 7. health
if ! wait_up 120; then
  # 8. roll back
  say "the new jar did not start within 120 s"
  say "--- APPLICATION FAILED TO START block:"
  grep -A25 "APPLICATION FAILED TO START" "$APP/app.log" 2>/dev/null | head -40 || true
  say "--- Caused by lines:"
  causes
  stop_app || say "WARNING: port $PORT still bound while rolling back"
  cp -p "$PREV" "$APP/app.jar"
  mv "$APP/app.log" "$APP/app.log.failed-x-be-test-$TS"
  start_app "rollback-$TS"
  if wait_up 120; then
    pii_presence
    say "the pre-deploy migrations stay (additive); the post-deploy ones were not applied. Failed log: $APP/app.log.failed-x-be-test-$TS"
    say "RESULT: ROLLED BACK to $(basename "$PREV")"
  else
    causes
    say "RESULT: ROLLBACK FAILED: the old jar did not come up either. Check $APP/app.log"
  fi
  exit 1
fi
echo "$(sha256sum "$APP/app.jar" | awk '{print $1}') $(git -C "$SRC" rev-parse --short=9 HEAD) $TS" >> "$W/installed.txt"
pii_presence

# post-deploy migrations (new jar is up)
for f in "${POST[@]}"; do
  apply "$f" || { say "post-deploy migration $f failed; the new jar stays up. Fix and re-run that file (all are idempotent)."; say "RESULT: DEPLOYED $SHA, POST-DEPLOY MIGRATION FAILED ($f)"; exit 2; }
done
say "tables at the end: $(table_count)"

say "--- distinct Caused by lines in app.log (expected: the missing WeChat Pay apiclient_key.pem only):"
causes
say "rollback jar: $(basename "$PREV")  (rollback: stop java, cp $PREV $APP/app.jar, start start.sh; the migrations are additive and stay)"
say "dump: $DUMP"
find "$SRC" -name target -type d -prune -exec rm -rf {} +
rm -rf "$V/m2repo"
say "removed the clone's target/ directories"
say "RESULT: DEPLOYED $SHA"
}

main "$@"
