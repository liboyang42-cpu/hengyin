# SOURCE 后宿主恢复链的安全拒绝定位与原数据库选项

Parent：`8b150cba25938a10cef8583b51454b6c3a40c864`。仅2个 C owned 文件及静态交付；canonical工具、领域reader/evaluate与其测试、SQL文件、Java、共享CI/host均未改。

## RCA边界

旧宿主把非 `ChildBoundaryFailure` 的异常统一投影成 UNKNOWN，不能由 SOURCE正常退出推定失败于 canonical restore、typed capture或领域谓词。没有旧私有trace/实际谓词证据，本包不把四项旧失败认定为同一原因；各自原谓词仍 UNKNOWN。不由缺运行证据认定资金、provider或生产实现错误。

可由源码确认的宿主接缝是：原 target创建注释要求匹配原数据库选项，但只写 `CHARACTER SET utf8mb4`，隐式采用target默认collation。新 `database_create_sql` 只从原source的原database metadata读取charset/collation，固定UTF8 fixture校验后，用安全元数据标识符构造同一处 target CREATE。未复制表/业务seed、不DROP现有库、不重试import，不改变canonical的完整database-definition比较；源备份后定义若漂移仍由原manifest/restore拒绝。不能据此声称旧四项均碰到collation差异。

## 实际调用与行为保留

- 原 `run(resources_path, output, private)` / `finalize` CLI、同源JAR/actor/cohort/UUID/run/attempt、固定7cohort、600/900秒预算、备份/故障/STOP/restore/owner时序不变。唯一新增SQL是同source的只读database选项输入；原target CREATE位置不变，原canonical工具全部原检查不动。
- 宿主按原真实调用点记录有限 stage/step/side/ownerPhase；`capture` 追加可选keyword `progress=None`，默认行为/返回值不变，仍读取所有原table/column/row/high-water，原位置参数caller可继续使用。索引只表示当前cohort已登记的表/ID位置，不输出实际row/SQL/金额/业务编号。
- 原异常保存在primary后，`host_failure` 仅读取traceback中受信任本repo三份源文件的有限function和line，最多8个位置，不格式化traceback、不读locals。只有 `type is ValueError`、实际throw-site属于该受信任源函数、完整message等于宿主/canonical静态已审理由，才投影固定reasonCode；其他保持UNKNOWN。领域理由不在C另造表，直接接F真实 `domains.failure_context(failure)`，字段名与F窄patch一致为 `domainContractFailure`。任意异常message、SQL、body、凭据和私有路径不进入public evidence。
- 非child失败新增 `hostFailure`；原child边界code/固定reason与typed stderr协议保留。输入resources/probe摘要在工作前冻结，失败构造不再依赖重新读取这些文件，失败顶部增加原JAR/probe hash。SOURCE exit0不代表恢复完成；没有合法proof仍不能启动正常读回或解除派发。
- 原catch/finally继续保首异常、失败快照、原child退出、原UUID/schema精确清理；成功或失败cleanup都不能覆盖业务失败。领域事实、金额、原意图、provider UNKNOWN/不重发、原ID高水位、正常boot eligibility全部由原算法判定，未在本片归一或豁免。

共享host实际consumer旧白名单会把新宿主reason再次降为UNKNOWN，因此交 `host-consumer.review.patch`，由root单独应用：仅对原scoped_receipt已绑定source/scope/cohort/run/attempt/resources的失败，额外核probe源码hash，并复用 `safe_host_failure` 的有限JSON校验后传递stage/code/line。错源、额外字段、越界位置不采用；原首因/returncode/清理及放行逻辑不变。原run/finalize签名与CI/host shell入口不改。

F依赖为已交 `d9c900fa0b2600c3574d10060471bb6bf6aa81a1` 的实际reader/接口，C未修改其文件。F一行 `domainContractFailure` 接线已经包含在本owned后像，root勿重复叠加F的同一行review.patch。必须将F源码、本C包和host窄patch组合后再验；不凭parent的旧reader缺接口新增兼容或放宽判断。与F reader的经济接缝仍是原 `evaluate/verify_owner`，四项旧域谓词仍UNKNOWN。

## 配套源码 / NOT_RUN

原55个控制方法/断言保留，旧legacy payment控制的domain double仅显式装配新F诊断port返回UNKNOWN。新增7个SOURCE控制：原source charset/collation与非法metadata拒绝；受信任throw-site/完整literal/私密字段边界；typed capture原table索引拒绝；有限JSON/outer实际consumer拒绝错源与私密扩展；SOURCE0后原canonical拒绝；SOURCE0后原domain拒绝。后两项委托原canonical/domain函数，仅用transport/文件协议doubles模拟时序，结果固定为FAIL，并断言无RESTORED_OWNER、同原JAR/cohort/run、原进程退出和schema清理。不是MySQL/Java/provider/真实恢复验收。合计62个SOURCE。

仅Git差分/源码搜索、SHA256与限本片秘密模式扫描；没有执行控制、parse/import、Java/编译、DB/HTTP/Docker/故障或CI。全部新增验证 NOT_RUN；不借其他cohort通过证明本片通过。两个owned与本文完整后像见 `postimages.sha256`。
