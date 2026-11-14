#!/bin/bash
# r6: TEST 8120 redeploy with the 0185418cf jar on cy_e2e_r2 — only the two new migrations, then the R3 TEST fixture.
B=/data/workspace/be-int; L=$B/r6/deploy.log; W=$B/hengyin/chengyinhub-admin/sql
{
echo "tables before: $(docker exec be-int-mysql mysql -uroot -N -e "select count(*) from information_schema.tables where table_schema='cy_e2e_r2'")"
for f in migration_20261120_merchant_authoring_candidate.sql migration_20261120_city_value_visit_feedback_at.sql; do
  docker exec -i be-int-mysql mysql -uroot --default-character-set=utf8mb4 cy_e2e_r2 < $W/$f; echo "$f EXIT $?"
done
echo "tables after: $(docker exec be-int-mysql mysql -uroot -N -e "select count(*) from information_schema.tables where table_schema='cy_e2e_r2'")"
docker exec -i be-int-mysql mysql -uroot --default-character-set=utf8mb4 cy_e2e_r2 < $B/r6/fixture-merchant-r3-8120.sql.txt; echo "fixture EXIT $?"
PID=$(pgrep -f "[a]pplication-beint.properties"); echo "stopping java pid $PID"
[ -n "$PID" ] && kill $PID
for i in $(seq 1 30); do ss -ltn | grep -q ":8120 " || break; sleep 1; done
ss -ltn | grep -q ":8120 " && echo "port still bound" || echo "port free"
cp $B/r6/app-0185418cf.jar $B/app/app.jar
cd $B/app && mv app.log app.log.prev-r5
setsid nohup bash start.sh > app.log 2>&1 < /dev/null &
echo "started"
} > $L 2>&1
