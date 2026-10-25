# B：347b 七 cohort source-create 首因诊断

公共父提交：`347bcd0226c3bed71616e38ea7c1391fc76efe5c`。只改原host及直接控制源码；本说明不转签旧入口/编译/业务绿色。

## 已知与未知

root提供run40220862215中七个子job停在source-create的原日志/两个artifact摘要：payment job121252348794、voice job121252348717只有exit1；artifact12608663164与12609094309各只有host.json，lastStage=source-create/originalExit1/probeFinalized=false，精确资源清理已确认。B本轮未重新下载原ZIP或执行Docker，不能从这些摘要推断是哪条Docker参数、镜像、预算或权限被拒。

父源码Host.command第124–127行捕获subprocess stderr，却仅抛固定stage字符串；main再将异常压成exit1，故原Docker退出码与原stderr首因没有进入公开收据。此片修该实际诊断丢失，不宣称修复了尚未知的Docker拒绝本身。原create端口、镜像摘要、tmpfs、内存/CPU/PID预算、环境绑定、CID/name/label/网络与源/目标两资源循环逐字保留。

## 最小接线

- 原Host.command调用点仍只有image pull、network create、source/target create/start、精确资源删除及原probe finalize。失败新增固定operation、COMMAND_EXIT/TIMEOUT/START_ERROR分类、原returnCode、stderr是否取得/字节数/完整SHA256；未知或未启动时不补造stderr或退出码。
- 仅首个失败保存在firstFailure；后续main通用异常、cleanup或finalize不能覆盖。非命令异常只记类型与UNKNOWN，不公开异常消息。host.json新增原GitHub runId/runAttempt，与既有sourceSha/scope/cohort同一份收据绑定。
- safeReasonExcerpt只可能是代码中的固定白名单摘录，不输出匹配的原行；未匹配一律UNKNOWN。没有记录argv、stdout、完整环境、password/cnf/private路径、provider或SQL。hash是原stderr完整字节摘要，分类只看前16KiB且不据此授权任何恢复或清理。
- 原exact label/CID/name/network检查与lost-ACK按精确新名字回读不变。daemon读取失败仍经原require_absent拒绝推定不存在；清理失败维持UNKNOWN。即使调用者错误传original_exit=0，已记录失败也不得签绿。
- 原一次构建、七固定cohort、同JAR/run/attempt、三份JSON、完整收据汇总与正常/缺口恢复门禁不改。未加任意命令入口、scope缩减或“单项等于七项”的放行参数；仍走原已注册backend-jar复用链，工作流/registry/共享flag由root单写。

## 源码控制与交付

原12个host测试方法和所有原断言保留，新增6个方法（共18条声明）：七cohort真正provision调用的固定Docker argv及脱敏首因、固定原因白名单/未知拒猜、create超时lost-ACK后原精确资源清理、daemon失败不作absence且不覆盖首因、误传0仍失败、启动异常无伪造stderr及正常stdout原样返回。subprocess/inspect为明确单元边界，不冒充真实Docker/数据库清理或业务证明；未更改C/F/A/G/D的源码。

本轮仅源码读取/编辑、差分/摘要与patch applycheck。测试、解析/导入、Python/Node控制、编译、Java、DB/HTTP、故障、Docker/资源及CI均 **NOT_RUN**。下次root同源定点执行后，应读取firstFailure而非再按stage猜原因；原347b失败仍失败，业务cohort尚未执行不能签通过。完整七项成功仍须原全部证据、独立清理及汇总，不由本诊断片授权。
