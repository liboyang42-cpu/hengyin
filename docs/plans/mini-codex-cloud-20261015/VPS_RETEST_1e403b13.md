# B03夹具修复：VPS复测交付

CY-HANDOFF: CODEX。协议CY-COORD-20261015-v1；C00/C99唯一维护控制账/源码，本地验证协调者负责VPS串行测试与独立VPS_EXTERNAL_*证据。

## 精确公共候选

- 仓库：liboyang42-cpu/hengyin
- 分支：codex/mini-bug-arch-integration-20261015
- 完整commit：**1e403b135206844a34219753a36e2dee39c7dd59**
- 父基线：69c281af1ecc3493db5614b74e494b91c7bb0656
- 复用PR18；仅一个测试文件4增1删：chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03AssetMybatisIntegrationTest.java。

## 修复与证据

已完整读[独立PR24](https://github.com/liboyang42-cpu/hengyin/pull/24)及其README/evidence.json，证据HEAD ccbcf93236e386aaf37a1c7ab0d45b6df5fb5ba6、base69c281af。首轮是1个setup错误、7业务方法未执行。B03_sol原写者接续后已停止编辑，C00单路径提交。

setup改用项目ItSchema.readStatements/WxPaymentCallbackFencingMySqlIT既有行尾分号分隔约定；COMMENT中的分号不再截断表SQL。缺表断言保留，缺终止符仍显式失败；七个@Test业务方法起至文件末尾与父提交逐字一致。没有修改schema/migration/业务SQL/断言/环境开关/CI测试清单，无新依赖。

实际轻量检查仅Python读取当前base schema并核三段完整CREATE TABLE：ums_member3386字符、points_detail1581字符、merchant_batch1929字符，旧首分号算法只会截断merchant_batch；每段恰好一个CREATE并保留ENGINE尾，diff --check成功。**这不是Java/MySQL执行；新SHA的七项仍待VPS复测。**

## 本轮结果更新

已读[PR24 f30057dd独立证据](https://github.com/liboyang42-cpu/hengyin/blob/f30057ddc3e7812849121ab0d3d400bc68b0cb0e/docs/verification/mini-bug-arch-20261015/VPS_EXTERNAL_1e403b13/README.md)：1e403b13 B03实跑7/7、零失败/错误/跳过，退出0/无OOM。原失败记录保留；本套无需重复跑。以下命令保留供审查/必要复现，不是要求再跑。其余普通202+AI存储22也已在相同SHA按PR24@43ca532签收，231矩阵闭环，TEST_DEPLOYED及设备/业务仍未验。

## 第一优先：原套件复测

沿用验证者已获准的独占MySQL8.0.45/Java21/Maven3.9.11、单Maven容器串行资源限额。先核归档来源SHA和哈希，源码不在VPS修改、不依赖旧项目jar；任务专属schema及无宿主发布端口保持。

安全环境注入（凭据不进命令文本/日志/Git）：

- CHENGYIN_FUNDS_MYSQL_IT=1
- CHENGYIN_TEST_MYSQL_HOST、PORT、USER、PASSWORD均显式设置（PASSWORD变量可显式空，不能缺席）
- CHENGYIN_TEST_MYSQL_DATABASE_BASE（任务专属base）及CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX（非空/唯一）；只允许测试实例，账户须有该测试环境建删临时库权限。套件setup/cleanup会DROP/CREATE其任务schema，不能指向共享/业务库。
- Maven最大堆沿原验证驱动640MB，测试进程384MB；不启动第二个Maven容器争用资源，不部署应用。

从仓库根运行：

```sh
mvn -B -o -pl chengyinhub-admin -am test \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=B03AssetMybatisIntegrationTest -DargLine=-Xmx384m
```

期望：**7个业务方法实际执行，tests=7/failures=0/errors=0/skipped=0**；若类级setup再次失败，报告真实1 setup error而非7失败/通过。Surefire原XML必须回读，与源码SHA、完整Maven日志、容器退出/OOM状态一起记录，不能只看BUILD SUCCESS。

XML位置：chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.service.B03AssetMybatisIntegrationTest.xml。可在原测试执行后用只读Python回读（对缺文件/空结果/skip失败）：

```sh
python3 - <<'CHECK'
from pathlib import Path
import xml.etree.ElementTree as ET
p = Path('chengyinhub-admin/target/surefire-reports/TEST-com.chengyinhub.business.service.B03AssetMybatisIntegrationTest.xml')
r = ET.parse(p).getroot()
expected = {'tests': '7', 'failures': '0', 'errors': '0', 'skipped': '0'}
for key, value in expected.items():
    assert r.get(key) == value, (key, r.get(key), value)
assert len(r.findall('testcase')) == 7
assert not r.findall('.//skipped') and not r.findall('.//error') and not r.findall('.//failure')
print('B03: 7 business methods executed; no failures/errors/skips')
CHECK
```

## 后续与分层

本轮其余最小选定目标为普通202项、AI真实MySQL/Redis22项；完整类名/精确环境见[VPS_TEST_MATRIX_1e403b13.md](VPS_TEST_MATRIX_1e403b13.md)。先运行以上B03，再串行普通与AI组，无需等待Cloud直连。只包含现有bug与共用后端本轮改动，不含Claude暂缓G1等新功能。运行时业务需有明确授权测试服务/身份、源码版本与库绑定证据，不能靠单测代替或未经授权部署；微信/iOS设备另列。

原69c281af GitHub CI成功保留为原SHA证据；1e403b13新CI/本VPS复测待验。NO_API_CHANGE，TEST_DEPLOYED仍NO。独立验证者只写自己的VPS_EXTERNAL_*文档/PR，不改本控制表；失败直接反馈C00，原写者继续最小修复，直到已授权的可执行验证范围闭合。
