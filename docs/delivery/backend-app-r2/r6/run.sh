#!/bin/bash
# r6: after merging be-gap-merchant R3 (1ebc6e9a2); same suite and ITs as r5
cd /data/workspace/be-int/hengyin
export MAVEN_OPTS=-Xmx1536m LANG=C.UTF-8
L=/data/workspace/be-int/r6
ARGLINE="-Xmx1536m -Dspring.test.context.cache.maxSize=4"
git rev-parse HEAD > $L/SHA
{ python3 ci/migration_ledger.py audit | tail -2; bash ci/migration-layout-check.sh; echo layout=$?; bash ci/migration-safety-check.sh; echo safety=$?; } > $L/gates.log 2>&1
flock /data/workspace/.mvn.lock mvn -B -T 1 package -DskipTests > $L/build.log 2>&1; echo "EXIT=$?" >> $L/build.log
flock /data/workspace/.mvn.lock mvn -B -T 1 test -pl chengyinhub-framework,chengyinhub-system,chengyinhub-admin -am "-DargLine=$ARGLINE" -Dmaven.test.failure.ignore=true > $L/test-unit.log 2>&1
echo "EXIT=$?" >> $L/test-unit.log
docker exec be-int-mysql mysql -uroot -e "DROP DATABASE IF EXISTS cy_gaps_b_r2; CREATE DATABASE cy_gaps_b_r2 CHARACTER SET utf8mb4"
docker exec be-int-mysql sh -c "mysqldump -uroot --single-transaction --routines --triggers cy_e2e_r2 | mysql -uroot cy_gaps_b_r2"
echo "copy=$?" > $L/its.log
TESTS="BeGapMiscMysqlIntegrationTest,MerchantGapMySqlIT,RewardsWorkshopMysqlIntegrationTest,ExternalGameReviewMysqlIntegrationTest,WorkshopPurchaseMyBatisIT,WorkshopAppleIapMyBatisIT,ClubProxyEnrollMysqlIntegrationTest,ChannelsGapImMySqlIT,ChannelsWebhookDispatchMySqlIT,CreatorMaintenanceMysqlIntegrationTest,CityGameMysqlIntegrationTest,CityR491MysqlIntegrationTest,AppGapsPass2MysqlIntegrationTest,ApprovedTopicReleasesMySqlIT,PlayerGapsMysqlIntegrationTest,AppOriginalEnvelopeMySqlIT,AppOriginalRequestLedgerMySqlIT,ClubOriginalsMySqlIT,ClubSeriesOriginalResultsMySqlIT,CouponCommandReceiptsMySqlIT,SupportTicketReadsMySqlIT,UsageConsentRecordsMySqlIT,ChannelsPlatformMySqlIT,ChannelsSecondPassMySqlIT,ChannelsThirdPassMySqlIT,ChannelsFourthPassMySqlIT,ChainPersistenceMySqlIT,FriendFoundationSchemaMySqlIT"
env CHENGYIN_MERCHANT_GAP_MYSQL_IT=1 CHENGYIN_R2_MYSQL_IT=1 CHENGYIN_CITY_GAME_MYSQL_IT=1 CHENGYIN_GAPS_A_MYSQL_IT=1 CHENGYIN_TEST_MYSQL_PORT=3330 CHENGYIN_TEST_MYSQL_DATABASE_BASE=cy_r2 \
    APPGAPS_MYSQL_IT=1 APPGAPS_MYSQL_DATABASE=cy_gaps_b_r2 \
    GITHUB_ACTIONS=true CHENGYIN_MYBATIS_IT_PORT=3330 CHENGYIN_MYBATIS_IT_DB_SUFFIX=_r6 \
  flock /data/workspace/.mvn.lock mvn -B -T 1 test -pl chengyinhub-admin -am "-DargLine=$ARGLINE -Duser.timezone=Asia/Shanghai" -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false >> $L/its.log 2>&1
echo "EXIT=$?" >> $L/its.log
MB="AuditReportLaneMyBatisTest,ClubCrmCheckinDetailMyBatisTest,ClubDissolutionGateMyBatisTest,ClubTopicManageStatsMyBatisTest,ComplianceF7MyBatisTest,MerchantDashboardAggregationMyBatisTest,MerchantNumbersAgreeMyBatisTest,O08MemberOnlyTopicLocationMyBatisTest,PublicActivityScopeGateMyBatisTest,PublicTopicFeedGateMyBatisTest,PublicVisibilityMyBatisTest,RedemptionRewardClawbackMyBatisTest,TopicDraftVisibilityMyBatisTest,TopicSyncPostRestoreMyBatisTest"
env CHENGYIN_MYBATIS_IT_PORT=3330 CHENGYIN_MYBATIS_IT_DB_SUFFIX=_r6 flock /data/workspace/.mvn.lock mvn -B -T 1 test -pl chengyinhub-admin -am "-DargLine=$ARGLINE" -Dtest="$MB" -Dsurefire.failIfNoSpecifiedTests=false > $L/mybatis-rerun.log 2>&1
echo "EXIT=$?" >> $L/mybatis-rerun.log
env APPGAPS_MYSQL_IT=1 APPGAPS_MYSQL_DATABASE=cy_gaps_b_r2 flock /data/workspace/.mvn.lock mvn -B -T 1 test -pl chengyinhub-admin -am "-DargLine=$ARGLINE -Duser.timezone=Asia/Shanghai" -Dtest=MapPublicLayersMySqlIT -Dsurefire.failIfNoSpecifiedTests=false > $L/map-rerun.log 2>&1
echo "EXIT=$?" >> $L/map-rerun.log
echo ALLDONE > $L/DONE
