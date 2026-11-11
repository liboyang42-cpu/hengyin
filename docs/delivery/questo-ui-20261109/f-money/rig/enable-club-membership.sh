#!/usr/bin/env bash
# [f-money rig] 只写 rig 测试库：打开既有功能闸 feature.flag.clubMembership（master 与 launch 两次运行用同一夹具），并清 Redis 里的配置缓存。
set -euo pipefail
docker exec -i "$(docker ps --format '{{.Names}}' | grep -i mysql | head -1)" mysql -uroot cy_e2e_51 -e \
 "INSERT INTO sys_config (config_name,config_key,config_value,config_type,create_by,create_time,remark)
  SELECT '俱乐部付费会员','feature.flag.clubMembership','true','N','rig',NOW(),'f-money rig fixture'
  WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='feature.flag.clubMembership');
  UPDATE sys_config SET config_value='true' WHERE config_key='feature.flag.clubMembership';"
R="$(docker ps --format '{{.Names}}' | grep -i redis | head -1)"
for k in $(docker exec "$R" redis-cli -n 9 --scan --pattern '*sys_config*'); do docker exec "$R" redis-cli -n 9 del "$k" >/dev/null; done
echo enabled
# 夹具缺主理人本人的成员行（club 7001 主理人 9003）；玩家 9009 先作为普通成员加入。只写 rig 测试库。
docker exec -i "$(docker ps --format '{{.Names}}' | grep -i mysql | head -1)" mysql -uroot cy_e2e_51 -e \
 "INSERT INTO club_member (club_id,member_id,role,status,join_time,del_flag,review_reason)
  SELECT 7001,9003,'1',1,NOW(),0,'' WHERE NOT EXISTS (SELECT 1 FROM club_member WHERE club_id=7001 AND member_id=9003);
  INSERT INTO club_member (club_id,member_id,role,status,join_time,del_flag,review_reason)
  SELECT 7001,9009,'0',1,NOW(),0,'' WHERE NOT EXISTS (SELECT 1 FROM club_member WHERE club_id=7001 AND member_id=9009);"
