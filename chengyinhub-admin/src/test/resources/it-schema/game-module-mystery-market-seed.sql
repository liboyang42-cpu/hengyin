-- 商圈谜案季 P0 首发 TEST_FIXTURE（可安全重复执行）。
-- PRODUCTION: DO NOT EXECUTE。仅临时 IT / 预演内容库使用，不得进入发布清单的可执行 migrations 列表。
-- 前置：migration_20260823_game_session_runtime.sql，且预演成员 ums_member.id=100000/100001/100002 存在。

INSERT INTO cms_topic (
    member_id, topic_type, product_type, name, subtitle, description, address_name,
    total_time, location_count, is_template, status, user_status, merchant_status,
    lifecycle, del_flag, create_by, create_time, game_module_config_json)
SELECT 100000, 1, 1, '商圈谜案季', '三站协作解开失踪菜单之谜',
       '玩家分成调查员、联络员、记录员，在三家站点完成公开任务并共同决定故事结局。样板不含现金权益，排行榜默认隐藏。',
       '样板商圈（预演环境）', 3600, 3, 1, 0, 0, 0, 0, 0,
       'seed_game_module_mystery_market_20260823', NOW(),
       JSON_OBJECT(
         'schemaVersion',1,'templateCode','MYSTERY_MARKET_SEASON_V1','leaderboardVisible',FALSE,
         'rewards',JSON_OBJECT('cashEnabled',FALSE,'couponIds',JSON_ARRAY()),
         'chapters',JSON_ARRAY(
           JSON_OBJECT('chapterId',1,'title','搜集三站线索'),
           JSON_OBJECT('chapterId',2,'title','共同推理结局')),
         'roles',JSON_ARRAY(
           JSON_OBJECT('code','INVESTIGATOR','name','调查员','publicBrief','观察异常并提出推断','clues',JSON_OBJECT('ARCHIVE','注意菜单年份','COUNTER','留意店员用词','ALLEY','寻找重复符号')),
           JSON_OBJECT('code','LIAISON','name','联络员','publicBrief','向站点确认公开信息','clues',JSON_OBJECT('ARCHIVE','询问旧菜单来源','COUNTER','确认今日暗号','ALLEY','核对营业时间')),
           JSON_OBJECT('code','SCRIBE','name','记录员','publicBrief','整理证据并保持队伍同步','clues',JSON_OBJECT('ARCHIVE','记下三处日期','COUNTER','记下三句证词','ALLEY','绘制符号顺序'))),
         'stations',JSON_ARRAY(
           JSON_OBJECT('code','ARCHIVE','nodeName','旧菜单档案馆','preparationChecklist',JSON_ARRAY(JSON_OBJECT('code','STAFF','label','值班人员到位'),JSON_OBJECT('code','PROP','label','公开菜单卡已摆放')),'playerTask',JSON_OBJECT('taskCode','ARCHIVE_STAMP','prompt','输入公开菜单卡上的年份','inputType','TEXT','verificationRequired',FALSE,'completionPolicy','AUTO_HASH','expectedHash','d54123de468bd42ea00dafbd777f85fe5fa1ff6404d9838c007953c25c92a1c5','hintPolicy',JSON_OBJECT('levels',JSON_ARRAY(JSON_OBJECT('level',1,'text','观察菜单卡的印刷年份','impactLabel','会记录一级提示'),JSON_OBJECT('level',2,'text','答案是四位数字','impactLabel','会记录二级提示')),'reveal',JSON_OBJECT('text','答案是1998','impactLabel','将记为兜底完成，不计正常榜单分'))),'merchantInstruction','保持公开菜单卡清晰可见，无需人工核验玩家答案','hiddenInfoReminder','不得主动说出年份或展示后台哈希'),
           JSON_OBJECT('code','COUNTER','nodeName','暗号柜台','preparationChecklist',JSON_ARRAY(JSON_OBJECT('code','STAFF','label','值班人员到位')),'playerTask',JSON_OBJECT('taskCode','COUNTER_PHRASE','prompt','扫描柜台公开二维码','inputType','SCAN','verificationRequired',FALSE,'completionPolicy','AUTO_HASH','expectedHash','41380f64cee57819f882c19b05601729aa4f573d1234299b53774e6bf0d6e376','hintPolicy',JSON_OBJECT('levels',JSON_ARRAY(JSON_OBJECT('level',1,'text','寻找柜台正面的公开二维码','impactLabel','会记录一级提示'),JSON_OBJECT('level',2,'text','扫码值以 MYSTERY 开头','impactLabel','会记录二级提示')),'reveal',JSON_OBJECT('text','扫码值是 MYSTERY-MARKET-COUNTER-V1','impactLabel','将记为兜底完成，不计正常榜单分'))),'merchantInstruction','保持公开二维码可扫描，无需人工核验扫码结果','hiddenInfoReminder','不得代替玩家扫码或透露后台哈希'),
           JSON_OBJECT('code','ALLEY','nodeName','线索巷口','preparationChecklist',JSON_ARRAY(JSON_OBJECT('code','SIGN','label','线索牌完好')),'playerTask',JSON_OBJECT('taskCode','ALLEY_SYMBOL','prompt','提交线索牌三枚符号的现场照片','inputType','PHOTO','verificationRequired',FALSE,'completionPolicy','EVIDENCE_ONLY','hintPolicy',JSON_OBJECT('levels',JSON_ARRAY(JSON_OBJECT('level',1,'text','让三枚符号完整进入画面','impactLabel','会记录一级提示'),JSON_OBJECT('level',2,'text','使用现场线索牌作为照片主体','impactLabel','会记录二级提示')),'reveal',JSON_OBJECT('text','按现场线索牌示例继续剧情','impactLabel','将记为兜底完成，不计正常榜单分'))),'merchantInstruction','保持公开线索牌完好，照片仅作现场证据留档','hiddenInfoReminder','不得指出符号正确顺序或透露剧情答案')),
         'fallbackPlans',JSON_ARRAY(JSON_OBJECT('planCode','ARCHIVE_TO_COUNTER','version',1,'status','APPROVED','sourceStationCode','ARCHIVE','targetStationCode','COUNTER','playerMessage','档案馆暂停服务，请前往暗号柜台完成替代任务')),
         'story',JSON_OBJECT(
           'variables',JSON_OBJECT('trust',0,'evidence',0),
           'publicVariables',JSON_ARRAY('trust','evidence'),
           'choices',JSON_ARRAY(
             JSON_OBJECT('id','SHARE','label','公开共享证据','stationCode','ARCHIVE','allowedRoles',JSON_ARRAY('INVESTIGATOR'),'chapterId',2,'choiceGroup','ARCHIVE_DECISION','required',TRUE,'effects',JSON_OBJECT('trust',1)),
             JSON_OBJECT('id','VERIFY','label','向站点核验证据','stationCode','COUNTER','allowedRoles',JSON_ARRAY('LIAISON'),'chapterId',2,'choiceGroup','COUNTER_DECISION','required',TRUE,'effects',JSON_OBJECT('evidence',1)),
             JSON_OBJECT('id','SKIP_VERIFY','label','保留疑点继续推理','stationCode','COUNTER','allowedRoles',JSON_ARRAY('LIAISON'),'chapterId',2,'choiceGroup','COUNTER_DECISION','required',TRUE,'effects',JSON_OBJECT()),
             JSON_OBJECT('id','RECORD','label','整理三站记录','stationCode','ALLEY','allowedRoles',JSON_ARRAY('SCRIBE'),'chapterId',2,'choiceGroup','ALLEY_DECISION','required',TRUE,'effects',JSON_OBJECT('trust',1))),
           'endings',JSON_ARRAY(
             JSON_OBJECT('code','TEAM_TRUTH','title','共同真相','text','你们用共享和核验还原了完整故事。','conditions',JSON_OBJECT('trust',JSON_OBJECT('gte',1),'evidence',JSON_OBJECT('gte',1))),
             JSON_OBJECT('code','OPEN_CASE','title','未完待续','text','关键线索仍待下一场补齐。','fallback',TRUE))))
  FROM ums_member m
 WHERE m.id=100000
   AND NOT EXISTS (SELECT 1 FROM cms_topic
                    WHERE member_id=100000 AND create_by='seed_game_module_mystery_market_20260823'
                      AND del_flag=0);

UPDATE cms_topic
   SET game_module_config_json=JSON_OBJECT(
         'schemaVersion',1,'templateCode','MYSTERY_MARKET_SEASON_V1','leaderboardVisible',FALSE,
         'rewards',JSON_OBJECT('cashEnabled',FALSE,'couponIds',JSON_ARRAY()),
         'chapters',JSON_ARRAY(
           JSON_OBJECT('chapterId',1,'title','搜集三站线索'),
           JSON_OBJECT('chapterId',2,'title','共同推理结局')),
         'roles',JSON_ARRAY(
           JSON_OBJECT('code','INVESTIGATOR','name','调查员','publicBrief','观察异常并提出推断','clues',JSON_OBJECT('ARCHIVE','注意菜单年份','COUNTER','留意店员用词','ALLEY','寻找重复符号')),
           JSON_OBJECT('code','LIAISON','name','联络员','publicBrief','向站点确认公开信息','clues',JSON_OBJECT('ARCHIVE','询问旧菜单来源','COUNTER','确认今日暗号','ALLEY','核对营业时间')),
           JSON_OBJECT('code','SCRIBE','name','记录员','publicBrief','整理证据并保持队伍同步','clues',JSON_OBJECT('ARCHIVE','记下三处日期','COUNTER','记下三句证词','ALLEY','绘制符号顺序'))),
         'stations',JSON_ARRAY(
           JSON_OBJECT('code','ARCHIVE','nodeName','旧菜单档案馆','preparationChecklist',JSON_ARRAY(JSON_OBJECT('code','STAFF','label','值班人员到位'),JSON_OBJECT('code','PROP','label','公开菜单卡已摆放')),'playerTask',JSON_OBJECT('taskCode','ARCHIVE_STAMP','prompt','输入公开菜单卡上的年份','inputType','TEXT','verificationRequired',FALSE,'completionPolicy','AUTO_HASH','expectedHash','d54123de468bd42ea00dafbd777f85fe5fa1ff6404d9838c007953c25c92a1c5','hintPolicy',JSON_OBJECT('levels',JSON_ARRAY(JSON_OBJECT('level',1,'text','观察菜单卡的印刷年份','impactLabel','会记录一级提示'),JSON_OBJECT('level',2,'text','答案是四位数字','impactLabel','会记录二级提示')),'reveal',JSON_OBJECT('text','答案是1998','impactLabel','将记为兜底完成，不计正常榜单分'))),'merchantInstruction','保持公开菜单卡清晰可见，无需人工核验玩家答案','hiddenInfoReminder','不得主动说出年份或展示后台哈希'),
           JSON_OBJECT('code','COUNTER','nodeName','暗号柜台','preparationChecklist',JSON_ARRAY(JSON_OBJECT('code','STAFF','label','值班人员到位')),'playerTask',JSON_OBJECT('taskCode','COUNTER_PHRASE','prompt','扫描柜台公开二维码','inputType','SCAN','verificationRequired',FALSE,'completionPolicy','AUTO_HASH','expectedHash','41380f64cee57819f882c19b05601729aa4f573d1234299b53774e6bf0d6e376','hintPolicy',JSON_OBJECT('levels',JSON_ARRAY(JSON_OBJECT('level',1,'text','寻找柜台正面的公开二维码','impactLabel','会记录一级提示'),JSON_OBJECT('level',2,'text','扫码值以 MYSTERY 开头','impactLabel','会记录二级提示')),'reveal',JSON_OBJECT('text','扫码值是 MYSTERY-MARKET-COUNTER-V1','impactLabel','将记为兜底完成，不计正常榜单分'))),'merchantInstruction','保持公开二维码可扫描，无需人工核验扫码结果','hiddenInfoReminder','不得代替玩家扫码或透露后台哈希'),
           JSON_OBJECT('code','ALLEY','nodeName','线索巷口','preparationChecklist',JSON_ARRAY(JSON_OBJECT('code','SIGN','label','线索牌完好')),'playerTask',JSON_OBJECT('taskCode','ALLEY_SYMBOL','prompt','提交线索牌三枚符号的现场照片','inputType','PHOTO','verificationRequired',FALSE,'completionPolicy','EVIDENCE_ONLY','hintPolicy',JSON_OBJECT('levels',JSON_ARRAY(JSON_OBJECT('level',1,'text','让三枚符号完整进入画面','impactLabel','会记录一级提示'),JSON_OBJECT('level',2,'text','使用现场线索牌作为照片主体','impactLabel','会记录二级提示')),'reveal',JSON_OBJECT('text','按现场线索牌示例继续剧情','impactLabel','将记为兜底完成，不计正常榜单分'))),'merchantInstruction','保持公开线索牌完好，照片仅作现场证据留档','hiddenInfoReminder','不得指出符号正确顺序或透露剧情答案')),
         'fallbackPlans',JSON_ARRAY(JSON_OBJECT('planCode','ARCHIVE_TO_COUNTER','version',1,'status','APPROVED','sourceStationCode','ARCHIVE','targetStationCode','COUNTER','playerMessage','档案馆暂停服务，请前往暗号柜台完成替代任务')),
         'story',JSON_OBJECT('variables',JSON_OBJECT('trust',0,'evidence',0),'publicVariables',JSON_ARRAY('trust','evidence'),
           'choices',JSON_ARRAY(
             JSON_OBJECT('id','SHARE','label','公开共享证据','stationCode','ARCHIVE','allowedRoles',JSON_ARRAY('INVESTIGATOR'),'chapterId',2,'choiceGroup','ARCHIVE_DECISION','required',TRUE,'effects',JSON_OBJECT('trust',1)),
             JSON_OBJECT('id','VERIFY','label','向站点核验证据','stationCode','COUNTER','allowedRoles',JSON_ARRAY('LIAISON'),'chapterId',2,'choiceGroup','COUNTER_DECISION','required',TRUE,'effects',JSON_OBJECT('evidence',1)),
             JSON_OBJECT('id','SKIP_VERIFY','label','保留疑点继续推理','stationCode','COUNTER','allowedRoles',JSON_ARRAY('LIAISON'),'chapterId',2,'choiceGroup','COUNTER_DECISION','required',TRUE,'effects',JSON_OBJECT()),
             JSON_OBJECT('id','RECORD','label','整理三站记录','stationCode','ALLEY','allowedRoles',JSON_ARRAY('SCRIBE'),'chapterId',2,'choiceGroup','ALLEY_DECISION','required',TRUE,'effects',JSON_OBJECT('trust',1))),
           'endings',JSON_ARRAY(JSON_OBJECT('code','TEAM_TRUTH','title','共同真相','text','你们用共享和核验还原了完整故事。','conditions',JSON_OBJECT('trust',JSON_OBJECT('gte',1),'evidence',JSON_OBJECT('gte',1))),JSON_OBJECT('code','OPEN_CASE','title','未完待续','text','关键线索仍待下一场补齐。','fallback',TRUE)))),
       update_by='seed_game_module_mystery_market_20260823', update_time=NOW()
 WHERE member_id=100000 AND create_by='seed_game_module_mystery_market_20260823' AND del_flag=0;

INSERT INTO cms_topic_chapter(topic_id,name,description,sort_id,required,recruit_enabled,terms_mode,max_merchant,create_by,create_time)
SELECT t.id,'商圈谜案三站','三个商家站点的协作推理体验',1,1,1,'TRAFFIC',3,'seed_game_module_mystery_market_20260823',NOW()
  FROM cms_topic t
 WHERE t.member_id=100000 AND t.create_by='seed_game_module_mystery_market_20260823' AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c WHERE c.topic_id=t.id AND c.name='商圈谜案三站');

INSERT INTO cms_topic_node(topic_id,chapter_id,merchant_member_id,node_audit_status,name,description,address,node_time,status,sort_id,checkin_code,create_by,create_time)
SELECT t.id,c.id,99999+s.sort_id,1,s.node_name,s.node_desc,'预演环境样板商圈',20,1,s.sort_id,s.checkin_code,
       'seed_game_module_mystery_market_20260823',NOW()
  FROM cms_topic t JOIN cms_topic_chapter c ON c.topic_id=t.id AND c.name='商圈谜案三站'
  JOIN (
    SELECT '旧菜单档案馆' node_name,'查看公开菜单年份印章' node_desc,1 sort_id,'MYSTERY-MARKET-ARCHIVE-V1' checkin_code
    UNION ALL SELECT '暗号柜台','记录公开展示的今日暗号',2,'MYSTERY-MARKET-COUNTER-V1'
    UNION ALL SELECT '线索巷口','识别公开线索牌上的符号',3,'MYSTERY-MARKET-ALLEY-V1') s
 WHERE t.member_id=100000 AND t.create_by='seed_game_module_mystery_market_20260823' AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.topic_id=t.id AND n.name=s.node_name);

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM cms_topic
    WHERE member_id=100000 AND create_by='seed_game_module_mystery_market_20260823'
      AND name='商圈谜案季' AND del_flag=0 AND is_template=1
    AND status=0 AND user_status=0
    AND JSON_EXTRACT(game_module_config_json,'$.leaderboardVisible')=FALSE
    AND JSON_EXTRACT(game_module_config_json,'$.rewards.cashEnabled')=FALSE
    AND JSON_LENGTH(JSON_EXTRACT(game_module_config_json,'$.roles'))=3
    AND JSON_LENGTH(JSON_EXTRACT(game_module_config_json,'$.stations'))=3
    AND JSON_LENGTH(JSON_EXTRACT(game_module_config_json,'$.fallbackPlans'))=1
    AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.fallbackPlans[0].status'))='APPROVED'
    AND JSON_LENGTH(JSON_EXTRACT(game_module_config_json,'$.story.variables'))=2
    AND JSON_LENGTH(JSON_EXTRACT(game_module_config_json,'$.story.choices'))=4
    AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.story.choices[1].choiceGroup'))='COUNTER_DECISION'
    AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.story.choices[2].choiceGroup'))='COUNTER_DECISION'
    AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.story.choices[2].id'))='SKIP_VERIFY'
    AND JSON_LENGTH(JSON_EXTRACT(game_module_config_json,'$.story.endings'))=2)=1
  AND
  (SELECT COUNT(*) FROM cms_topic
    WHERE member_id=100000 AND create_by='seed_game_module_mystery_market_20260823'
      AND name='商圈谜案季' AND del_flag=0
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[0].preparationChecklist[0].label'))='值班人员到位'
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[0].preparationChecklist[1].label'))='公开菜单卡已摆放'
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[1].preparationChecklist[0].label'))='值班人员到位'
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[2].preparationChecklist[0].label'))='线索牌完好'
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[0].playerTask.inputType'))='TEXT'
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[1].playerTask.inputType'))='SCAN'
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[2].playerTask.inputType'))='PHOTO'
      AND JSON_EXTRACT(game_module_config_json,'$.stations[0].playerTask.verificationRequired')=FALSE
      AND JSON_EXTRACT(game_module_config_json,'$.stations[1].playerTask.verificationRequired')=FALSE
      AND JSON_EXTRACT(game_module_config_json,'$.stations[2].playerTask.verificationRequired')=FALSE
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[0].playerTask.expectedHash'))='d54123de468bd42ea00dafbd777f85fe5fa1ff6404d9838c007953c25c92a1c5'
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[1].playerTask.expectedHash'))='41380f64cee57819f882c19b05601729aa4f573d1234299b53774e6bf0d6e376'
      AND JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[2].playerTask.completionPolicy'))='EVIDENCE_ONLY'
      AND JSON_LENGTH(JSON_EXTRACT(game_module_config_json,'$.stations[0].playerTask.hintPolicy.levels'))=2
      AND LENGTH(JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[0].merchantInstruction')))>0
      AND LENGTH(JSON_UNQUOTE(JSON_EXTRACT(game_module_config_json,'$.stations[0].hiddenInfoReminder')))>0)=1
  AND
  (SELECT COUNT(*)=3 AND COUNT(DISTINCT n.merchant_member_id)=3
          AND MIN(n.merchant_member_id)=100000 AND MAX(n.merchant_member_id)=100002
     FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id
    WHERE t.member_id=100000 AND t.create_by='seed_game_module_mystery_market_20260823'
      AND t.name='商圈谜案季' AND t.del_flag=0 AND n.node_audit_status=1)=1
) AS migration_assertion;
