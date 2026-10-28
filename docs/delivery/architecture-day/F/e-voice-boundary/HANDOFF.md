# E原语音/公共admission/factory第四参数精确边界

parent `d489ea66f832c00b8901909d73d5c9d2d916fbc0`（D已独立冻结，parent88c→f15），实际供体0d32d888e29c6d5ef51322b10d7aca736be856ff。只在既有owners/checker协议追加4职责/5精确类型、4方向：E_VOICE_PROVIDER_CLIENT=System VoiceCloneClient+DashScopeVoiceClient；E_LLM_CLIENT_FACTORY=System LlmClientConfig；F_AI_HTTP_ADMISSION=System原AiHttpAdmission；E_VOICE_CONFIGURATION=Common原AiVoiceConfig。仍是已有实现，不造线程池/动态平台/HTTP新依赖；现69类型/57职责/65方向。

4条公共方向逐字段方法可核：Voice.config→Common AiVoiceConfig，Voice.admission/acquire(url)→原Admission，factory.aiHttpAdmission第四参数voice/baseUrl()→Common config，factory new AiHttpAdmission(endpoints)→原Admission。其余AiModel/Vision/Provider configs、OpenAiCompatibleLlmClient和通用HttpUtils暂unowned，保留调用并明确scope未覆盖，不按包授任何表/金额/退款/支付/玩法权限。

具体边界：Common配置不反调System factory/admission/voice/provider或实际业务caller；admission不反调factory/provider/caller；voice/factory不回调原ApiMerchantController/NodeNpcService/NpcChatService。仅真实出现的精确类型，旧方向不变；没有source import新scanner，不改原5返回值/模块9边/未批准SCC baseline。

新增5组NOT_COMPILED协议控制：4合法方向；Common反向System红；client/factory/admission反调红；factory↔admission有hash witness的新环红；每个新声明type缺raw行仍UNKNOWN。原方法AST逐个保留，Python224/224、0skip、21.516s。新声明接口/配置在实际JAR/raw未出现仍UNKNOWN，不能拿其它类输出或synthetic fixture签Java。

已完整读源及生产caller，源码证据见source-evidence.json。已知原日志合同余项：0d32 voice76/131输出provider output、173/187输出raw、181输出provider message、161记录cause。类型图无法证明日志脱敏；本片不改E生产、也不称WP13/日志安全完成，交root/E原职责。原post/admission/lease/timeout/NOT_SENT与UNKNOWN/额度等语义不由classgraph证明，需E/F受控HTTP场景。

D audit writer相对真实可用merge-base e108的NEW_TABLE_WRITER仍保留，新增D technical port不授业务写权；反洗白/3Mapper/2facts/2ecbaseline/locking和所有shared旧owner/方法不变。新role没有消除该源阻断。六可信hash见trusted-files.sha256；其余五可信文件逐字不变。

只改owners/test_collect_jdeps/docs。root独占语音第四参数wiring.review.patch及CI双ref；本片不改workflow/Bean/生产/registry，无新framework/dependency/job。Java/javac/jdeps/JAR/leaf/DB/Spring/CI全部NOT_RUN，不借rootMySQL4/任何旧日志作验收。Bean邀请后续独立cf4c预期33未运行，root同批实际source/原始XML/hash回读后才可签。
