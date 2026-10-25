# fullrestore 原隔离 Python / pre-start cleanup 修补（SOURCE / NOT_RUN）

结论：保留 `-I`，将已有 PyMySQL==1.1.1 安装到本job自有标准venv；严格识别同源同run的“未尝试启动app”，不因不存在的日志加次错、不把原失败签绿。parent `347bcd0226c3bed71616e38ea7c1391fc76efe5c`；独立分支 `codex/architecture-day-f-isolated-python-cleanup`。只改本workflow、原auth owner Python及其直接controls；C probe/runtime、shared shell、生产/POM/政策均未修改。

## 真实证据 / 保留边界

F独立回读 run40220857316/job121251920466 原日志：pip明确 `Defaulting to user installation because normal site-packages is not writeable`、安装PyMySQL-1.1.1成功；C run `ModuleNotFoundError`；随后auth cleanup报 `application logs unavailable after exact resource cleanup`；C finalize另报IndexError，最终exit1。模块具体名字未公开，不造trace。静态第一relay query调用原`auth.database()`，其157行lazy `import pymysql`；`-I`不加载user site，安装到那里不能保证该调用可用。

协调方已核原artifact12609009475/ZIP SHA256 `c3861ad6c800cc233cb1c7adcb6c12692101aab6028ecb7b264a2679d7d40ced` 只有application/provenance两JSON；F不重复下载或将其说成新运行/cleanup证明。旧run缺marker、缺cleanupJSON等事实不能由本source补造。

追加协调方同源mixed真实trace：run40220853485/job121251908444，gameplay_process_recovery_probe.py:442 → 原auth.database:157 → `ModuleNotFoundError: No module named 'pymysql'`。仅此mixed模块名已确证，不再UNKNOWN；fullrestore原类型日志仍没有独立公开模块名。原f2ebc9受控解释器已覆盖这条真实-I caller，不重写/再派新环境包，版本/隔离不变。

## 实际caller / 环境

| 固定模式 | 实际影响 |
|---|---|
| restore-application-startup | 原shell `python3 -I C run/finalize`；C调用auth.database及canonical/helper子进程。固定PATH选task venv，同一pin可被`-I`和原`python3 -B restore-runtime-user`访问 |
| gameplay-process-recovery | 原`python3 -I gameplay_process_recovery_probe.py run/finalize`，442行通过原owner.database做实际SQL；同一个隔离导入接缝，使用相同task venv |
| gameplay-capacity-mixed（原live mixed） | 仍走上述`-I` gameplay子入口并叠原capacity工作负载，不另建解释器或第二套fixture |
| recovery / 普通capacity-mixed / PR默认 | 原安装步骤命令和解释器选择不变；其SQL执行为原`-B`，`-I` precheck不导入driver。没有默认重跑已过项目 |

venv只用本job当前python3的标准库创建，不换Ubuntu24/crypt/base Python；路径包含真实run/attempt，创建前必须不存在，私有owner标记绑定repo/run/attempt。venv interpreter以`-I -m pip ... --no-user`安装原pin，再以`-I`实际校验metadata版本、pymysql来自sys.prefix、非base prefix、原BLOWFISH能力。没有添加用户site/PYTHONPATH、系统site、`--system-site-packages`、pip升级或新依赖；不改TLS/proxy/index/凭据配置，不向全局site安装。

仅将其bin加入本job GITHUB_PATH，使既有shell与实际owner子进程解析同一python3。原资源cleanup/C finalize全部完成后，workflow在保留证据步骤前精确检查venv owner marker并删该单一路径、读回不存在；失败不fallback全局或吞错。全局Python/M2不动。原run预算/资源caps/秘密与三JSON上传白名单不变。

## NOT_STARTED精确合同与C接点

C独占report字段：`applicationStartAttempted=false`初值，在第一次`slot.start`调用前变true；F不改C文件。新增识别只在以下全部满足后成立：manual restore flag、公共repo、完整source SHA/run/attempt、原NAME/schema、C有界普通JSON非symlink；同source/schema/run/attempt；marker严格布尔false（0/字符串/缺失均拒绝）；boots/ownedAppCids/removedAppCids全空；NO_ACTIVE_APP_SLOT、NOT_COMPLETED、固定类型的firstFailureType、productionDispatchRelease=false。

实际外层先移除资源，独立确认app原本缺席、其余mysql/restore-mysql/redis精确3CID/name/independentNoSuch及network不存在；新报告中的3CID须全部匹配且互不相同。原auth报告只能是尚无业务probe的FAIL_BEFORE_BUSINESS_PROBE，损坏或原PASS矛盾不接受。此时只把日志状态记NOT_STARTED，保留原FAIL_BEFORE_BUSINESS_PROBE；资源清理确认不等于业务成功。

已尝试启动、任何历史boot/CID、错源/错run/损坏/缺报告、app实际存在、daemon/absence未知皆继续原失败路径。成功原四CID清理、实际app log采集/失败守卫、原pre-app readiness失败合同逐字逻辑保留。原EXIT trap首个非零不变，新的venv清理不能改变C原失败。

C还拥有同次原finalize IndexError接缝：其旧代码读取ownedAppCids[-1]，未启动时数组为空。请root/C在C-owned片按新marker处理失败收据，不能填假appCID/强求成功四CID或把业务失败签绿；F未动该文件，本片单独不能宣称整个失败收据闭环已运行通过。

## Mixed共享finalize最小review hunk（未应用）

同一mixed失败在F observe之前退出，`capacity-mixed.json`未生成。原gameplay.finalize却在已有`original_exit == 0`断言之前先调用shared capacity.finalize，后者781行无条件读原结果JSON，故报次错。无需改capacity业务判定或造缺失JSON。

`mixed-finalize.review.patch.b64`只建议把gameplay原非零断言前移到shared调用前，原断言原样保留、成功路径所有检查不变。原报告仍保留ModuleNotFoundError/原reason及businessResultBeforeCleanup，保持FAIL，不把未执行F observe说成通过。配一个直接控制SOURCE：原exit1/124时shared loader不得调用、原首因保留、capacity JSON不得补造；原正向与所有旧断言不删。

该hunk目标是`ci/gameplay_process_recovery_probe.py`及其原direct test，**F没有编辑/提交这两个共享文件的后像**，只在/tmp生成review并做Git apply --check。请root串行交原owner应用；decoded摘要见`SHARED-FINALIZE.sha256`。C fullrestore probe/runtime不在hunk。没有修独立capacity默认成功路径、没有新增日志类别/依赖/授权。

## 控制 / 交付

原全部owner与entry4控制保留，新5个直接控制SOURCE：同源pre-start清理正控且终态仍FAIL；started/unknown/foreign/CID负控；缺失/损坏/symlink/原报告矛盾负控；真实app日志失败/daemon/PR/default不豁免；精确三模式venv/同pin/-I/PATH/owner cleanup接线。全部未执行；workflow内原隔离解释器import检查也是未来运行断言，不是当前结果。

`review.patch.b64`是3个执行/控制文件完整diff，decoded hash见PATCH.sha256；全作者后像见POSTIMAGES.sha256。root唯一应用/合成/验证；只需本包实际受影响模式的同源验证，不重复CLM8、client10767或未受影响旧项目。实际仅Git diff/apply检查、摘要和有限秘密模式检查；所有parse/测试/编译/资源/CI **NOT_RUN**。
