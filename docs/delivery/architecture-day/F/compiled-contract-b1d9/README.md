# F b1d9 compiled-ports 必要源码修补与有限合同审装

父源 b1d9acc13d74b8eef3a007fa6691583bfd7329c3。对应原40776744585/job123128111386/attempt1 的7条真实 findings。仅直接提交必要测试SOURCE；5生产文件与2门禁数据/控制文件均以完整review.patch交root，不擅改其实际路径或可信pin。原279525e等前轮已继承，不回退。

## 七条分别收口

1. **ApiTopicAttributeController → ApiTopicPlaySetupController**：真实只有 read/write 两处 catch 调 package-static coded(ServiceException)；setup自身save也用同一完整投影。它是响应投影、不是另一个控制器的权限/业务入口。不授controller peer方向。production.review.patch将原完整方法体归入既有common BaseController的 protected static codedServiceException；三处真实caller都改调用它，删setup原方法体，非复制/转发壳。原null code早返、code/msg/errorCode/data逐字语义保持；不改另一份ApiTopicStateKeyController的不同旧投影。BaseController只新增同common模块ServiceException引用，无依赖/新class/API。

2–4. **PHOTO / PREFERENCE / REVEAL → BasicIntent**：三条都是原公开 executeBasicIntent 的真实正向应用调用。原PHOTO带picUrl；PREFERENCE带choices/reuseTagCode；REVEAL带空输入列表；全部携带原actor/activity/topic/node/actionId/expectedVersion/routeSessionId/runtimeInstance/authorityEpoch，并接收同一PlayOriginalRun。child原@Transactional/read-committed边界、旧overload拒绝、原回执先读与聚合锁后current再次读、claim/业务/终态原子次序不变。绝不把它们直接改调Mapper或复活私有God体。

policy.review.patch仅向root正式提议三条class方向：C_PLAY_PHOTO_COMMAND/C_PLAY_PREFERENCE/C_PLAY_PUZZLE_COMMAND → C_PLAY_BASIC_INTENT。它们复用已有C_PLAY_BASIC_COMMAND → C_PLAY_BASIC_INTENT的原标准。BasicIntent本身的输入hash/member/scope/原局/epoch/回执规则不改。没有新incoming/table/command授权，不声称class方向能限制具体方法或证明经济授权。四个caller Java源逐字不变，固定文件hash见INTENT-CALLER-SOURCES.sha256。

root已对这三条给出正式有限SOURCE合同裁决（其本地收据名 b1d9acc1-root-original-intent-directions-review-local.json）；本提案精确采用此三条，仍由root独立审装/固定verifier。新增直接负控包含反向、peer及第4未审owner C_PLAY_HISTORY_QUERY → C_PLAY_BASIC_INTENT拒绝；旧BasicCommand和GuardReader已审方向不删改。

5–7. **Naming → Attributes反向引用/方向/SCC**：真实rename只取上层AttributeService的12字限制和两条原话术；AttributeService同时真实依赖Naming的命名/校验/复制能力。将属性命名约束本身归回已承担所有命名的ThemeKeyNamingService：MAX_ATTRIBUTE_LABEL=12、原超长/同名话术只在此定义一次，rename使用本owner定义。ThemeAttributeService原MAX_LABEL/MSG_*名称保兼容别名，继续消费Naming唯一来源；全部其它属性算法/原校验/调用者不动。不是把业务逻辑复制两份，不新增接口/转发类，也不把原反向边加入grant。原M_KEY_NAMING forbidden_backrefs仍保，原Attributes→Naming方向仍保，新增cycle仍红。

本次证据是原jdeps/技术owner图SCC，不是已证实Spring Bean循环；Naming没有注入AttributeService，常量引用也可能内联。修后真实class边须由新同源编译/JAR/raw回读验证，源码改动不代签它已消失。

## Caller、SOURCE控制、身份

- 生产完整差分5路径见PRODUCTION-PATHS.txt；REVIEW-IMAGES.tsv给7份审装文件的前/后像，固定父+完整patch可重建。
- ThemeKeyNamingServiceTest原7方法/全部原断言保留，仅在原rename场景补属性12字可用、13字/同名拒绝且原行/registry version不变；普通counter20字仍可用。Doc2原fixture、属性原8场景均不改。
- 新TopicAuthorErrorProjectionTest 3方法：实际两Controller的三个catch caller保code/msg/errorCode/data；null code保原早返；归属拒绝/冻结写均不可达业务owner。只mock原授权/服务边界并使用实际Controller入口，不冒充完整Spring/真实RBAC或外部内容检查链已执行。
- policy.review.patch保所有原控制，只为固定dbd12前像保留断言精确排除本次3条C方向，未泛化为任意新边。新增1个直接规则SOURCE：三正向、反向/peer负控；原controller peer仍拒绝；Naming反向必须同时报backref/direction/cycle；partial synthetic index仍UNKNOWN。原JAR/raw/source/hash/缺raw控制及旧baseline完全未动。
- 用户给的source_sha256/target_sha256按原collect_jdeps.py:161实际来自class-index的**class字节**摘要，不是Java文件摘要。CLASS-EVIDENCE-ROOT.tsv保root提供值，未冒称F另下载核验；本包独立记录公开Java前后像hash，两者不得混签。新class/JAR/raw须root同源重建回读，不能套原制品。

## 严格未运行 / root接线

全部项目parse/import/tests、Java/Maven、DB/Redis/Docker、HTTP、CI：NOT_RUN。实际只有Git读源/diff/apply-check、摘要与有限敏感模式检查。

两份完整review.patch已分别对固定b1d9的临时Git index apply-check；bytes/SHA256见PAYLOADS.tsv。实际生产、owners、commands、workflow及可信92fd工具全部未被本供体修改。root应先独立审5生产hunk及仅3方向/控制，再制作可信verifier/双ref，保持原基线/SCC/UNKNOWN门槛，最后必要同源验收。原1GiB/held-before-read/finally credit/身份金额权限UNKNOWN/精确cleanup均无改动。仅这7条的 SOURCE闭环，不宣称全图/整批已验。
