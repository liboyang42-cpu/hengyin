# 可信 post-build 接口和 root 接线建议（未修改实际workflow）

## 固定接口

```text
gate.py compiled-ports
  --source <actual-built-checkout>
  --artifact <actual-tested-Boot-Jar>
  --identity <same-job-Boot-build-identity.json>
  --observation <same-job-architecture-jdeps-directory>
  --output <ignored-target-report.json>
```

artifact/identity/observation的相对路径以source为根；output按CLI当前目录解释。CI中source为`.`，全部路径在原ignored target内。输出父目录可创建，结果在返回前落盘：0＝PASS_SCOPED_COMPILED_REFERENCES；1＝确定PRIVATE_MAPPER_CONSUMER/BLOCKED；2＝UNKNOWN未完成证据验证。source/evidence入口原退出码不变；无效可信policy仍fail-closed。不能用`|| true`吞掉1/2后声称通过。

输入必须同实际checkout/source tree/run/attempt与Boot哈希；实际模块original Jar、target/classes仍须在同job可读，不能把只下载的raw或四记录摘录冒充完整输入。observation固定原11项文件；不接受新增/缺少manifest键、任意路径、symlink、错hash/index/location。post-check复用ZIP枚举，不解析class字节码、不再运行JDK。标准JDK21必须与采集时tool hash相同，invocation为原`--multi-release 21 -verbose:class -filter:none`、六原module inputs、C locale及180秒原预算。

只加载可信owners中的两个private_ports；未暴露CLI自选scope/consumer参数。两目标和原Service必须可与真实输入index对上，并存在可解析的原Service入边，否则UNKNOWN。其它确定consumer立即红，含同System和只读依赖；`$`名字没有前缀豁免。即使还有其它解析UNKNOWN，已确定违规仍红；纯UNKNOWN不得写成覆盖通过。

## root最小接线位置

以下是建议，F不写`.github/workflows/ci.yml`、registry或harness。`F_REVIEWED_FULL_SHA`由root在review后填最终完整SHA，不是活动工作流或新审批系统。

1. 原source job固定ref改为该trusted revision，相同六文件先集成candidate。source检查不能用candidate自己的脚本。
2. 在原Backend `Describe the tested Boot Jar`之前checkout**同一**固定可信revision，目标例如`chengyinhub-admin/target/f-private-port-verifier`。该路径已用git check-ignore确认被原target规则忽略；不添加ignore豁免。checkout repo显式公共hengyin、persist-credentials=false；Backend无需额外job/runner。
3. 原Describe步骤赋一个root选定id，例如`tested_artifact`；保留verify-built-jar/resources/identity所有步骤，仅将原producer调用路径换成可信checkout。仍只执行一次JDK采集：

```sh
python3 chengyinhub-admin/target/f-private-port-verifier/ci/architecture/collect_jdeps.py \
  --source . --artifact chengyinhub-admin/target/chengyinhub-admin.jar \
  --identity chengyinhub-admin/target/chengyinhub-admin.build.json \
  --output chengyinhub-admin/target/architecture-jdeps
```

4. 原Describe之后增加独立post-check步骤，使用同一可信代码，不执行候选producer/任意candidate JSON判据：

```sh
python3 chengyinhub-admin/target/f-private-port-verifier/ci/architecture/gate.py compiled-ports \
  --source . --artifact chengyinhub-admin/target/chengyinhub-admin.jar \
  --identity chengyinhub-admin/target/chengyinhub-admin.build.json \
  --observation chengyinhub-admin/target/architecture-jdeps \
  --output chengyinhub-admin/target/private-mapper-references.json
```

5. 原tested-backend artifact上传须在post-check红/UNKNOWN时仍执行。例如root将该上传的条件设为 `!cancelled() && steps.tested_artifact.outcome == 'success'`，原路径/保留期不变，仅追加private-mapper-references.json。Describe失败不冒充已生成输入；原raw/11项hash、XML及identity保存流程都保留。无新job/matrix、生产workflow或并行预算变化。

新post步骤在Describe与upload之间，专属shell自测已按“下一step”截取Describe自身run block，避免把新增YAML当shell；没有放松shell检查。root仍需按最终真实workflow跑原CI成本/接线合同。

## 验收与回退

Python可证明协议/负控，不能代替真实compiled负控。root隔离候选增加一个同System生产class，仅持有/引用原Mapper（不调用模型/库、不改XML）；真实编译、同jobJDK输出与新检查必须出现该边并红。删除该class后新完整SHA重跑绿；每次都有新source/run/attempt与原artifact，不拼其它候选结果。预期已批准Service合法引用同样需真实回读。

若实产出现其它原caller、解析缺口或位置不明，保持BLOCKED/UNKNOWN如实报告；不扩大consumer前缀/owner、重建baseline或跳过检查求绿。root协调回退fixed ref及六保护文件；停止新检查不等于旧缺口已修。
