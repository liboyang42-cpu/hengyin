#!/bin/bash
# Rig-only: set cms_topic.look_json for topic 990017, then shoot the same screens per look.
cd /tmp/claude-0/k10
M="docker exec -i cy-chain-local-1-mysql mysql --default-character-set=utf8mb4 -uroot cy_e2e_51"
for style in STARDUST PAPER SURVIVAL CYBER; do
  echo "update cms_topic set look_json='{\"style\":\"$style\"}' where id=990017;" | $M 2>/dev/null
  ./r.sh -s '{"path":"pages/play/index?topicId=990017&activityId=9","identity":"e2e-openid-9009","agree":true}' >/dev/null
  ./r.sh "await sleep(7000); await w.shot('k10-look-$style-ending'); return (await w.data('look')) || (await w.data('lookView'))" | head -c 300; echo
  ./r.sh -s '{"path":"pages/play/index?topicId=990017&activityId=9","identity":"e2e-openid-9004","agree":true}' >/dev/null
  ./r.sh "await sleep(6000); await w.shot('k10-look-$style-a'); await h.tap('继续探索').catch(()=>{}); await sleep(2500); await w.shot('k10-look-$style-b'); return (await h.say()).slice(-160)" | head -c 400; echo
done
