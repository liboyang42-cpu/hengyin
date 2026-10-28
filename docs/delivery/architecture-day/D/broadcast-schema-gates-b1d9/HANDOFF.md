# D：广播 fresh-schema 已批准组合的门禁接线增量（SOURCE / root review）

Parent `b1d9acc13d74b8eef3a007fa6691583bfd7329c3`，tree `a656f115f4ab541fe0cfe233610a8f47ca2e5c34`。
本提交仅运输此目录3文件。正式 root-owned CI 文件没有直接改写；
root.review.patch 完整重建2个后像，IMAGES.sha256列出前后SHA256/Git blob与补丁字节。
不重套已装的 b909c4aa1a70c3f10a0c33bbcf3a1b8bf31a4d05 七路径包；原 b909 parent2fb、
tree77dc90b068939a2ca361de7b6721b2327cfee805 和全部旧后像/源码计数保留。

## 实际首因与范围

root提供40776744585/attempt1/Migration safety123128111345：
check_schema_manifest_order_source 仍精确批准旧 notice 单替代 runner，
而 b1d9 已正式装配 notice→broadcast 两个固定替代和共享 fresh gate。
这是必须同步的真实门禁 caller，不能将 expected=[] 当成修复。
仅修改建议 ci/migration_gates.py、ci/test_migration_gates.py。
scripts/e2e/build_schema.py blob f5ba9d59499815e59c46595a00fb411529a0d99f、
所有 SQL/依赖/registry/工作流及生产 ledger 不改；新runner无需再变更。

## 精确有限合同

- 原 notice 的9项 AST 指纹逐字保留。新增广播5个定义的完整精确 AST 对照：
  BROADCAST_D/OLD/UNION/FORWARD_FILES、broadcast_forward_plan。
  对照来自 root 已批准的 b1d9 真实源码；门禁只解析，不 import/执行它。
  使用字面源码的 AST 对照是为了本轮不运行项目 AST 生成指纹；不是通配/第二执行平台。
  文件路径/hash、唯一完整pending、phase、父依赖及 manifest/pending原序都仍固定。
- 主流程精确要求9条有序直接赋值：
  canonical manifest→all→selected→notice选择→其结果→broadcast选择→其结果→deferred→ordered。
  selected仅3次指定赋值；ordered仅1次；撤销旧“fresh块中可另写ordered”的特例。
  任何额外 rebind/filter、shadow/subscript mutation 仍拒绝。
- fresh准备整个 try/finally 精确匹配：
  notice OR broadcast→absent严格整数0检查→无IF NOT EXISTS的CREATE→两个固定REPLACED_UNAPPLIED回执→close。
  --drop不能绕过；原库/并发创建库不得接管；不允许第三替代/互换identity/伪APPLIED。
- 主执行循环严格匹配 NOTICE_V2 OR BROADCAST_UNION 强制停止条件；
  原canonical迭代/路径/label/schema_only、三个run_file调用、两个同scope连接/finally不变。
  总5个for：1个ledger执行+1个固定替代报告+3个原失败/观察报告。
  不是“允许任意多一个循环”：新报告loop在精确fresh AST内固定，任何额外loop仍报错。

## 配套测试 SOURCE / 原例保护

ci/test_migration_gates.py 原144个 test_方法名全部保留，新增2个，文本声明146。
原 notice mutation 的名称/拒绝意义保留，仅迁7个旧锚点：
drop绕过、先删库、丢replacement、逆replacement、关闭v2 strict、strict错对象、loop skip。
原主循环fixture同步真实双strict表达式；原布局正控继续，另验证broadcast同样仅格式变化可接受。
新增2个方法共37个广播负控：
19个身份/path/hash/pending/deps/phase/原序/替代对象控制，
18个调用、fresh OR、drop/filter/rebind/shadow、strict、回执/第三替代控制。
广播selector变异限定自身原源码块，不借notice失败冒充其拒绝。
所有 _assertIn(actual needle)/non-no-op/ast.parse合法Python/拒绝断言保留；
这些都是待统一执行的测试源码，本轮没有实际运行。

已执行：只读固定公共源、Git diff/apply --check、字节摘要、补丁新增行限定秘密模式扫描（0匹配）。
NOT_RUN：项目 parse/import、任何Python/Node/Java测试或编译、MySQL/Redis/Docker/HTTP/CI。
原1GiB/四路16MiB/held前屏障/finally credit/金额/权限/UNKNOWN预算均无触及。
生产upgrade依旧未验证；本增量不扩大原absentfresh替代授权。
