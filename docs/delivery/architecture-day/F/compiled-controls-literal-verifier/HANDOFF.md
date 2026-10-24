# F：后置真实编译工具绑定literal修复verifier

结论：工具唯一固定 `f8531bfd6cca90d88f9c281d1904a170b635f990`，无浮动ref或参数覆盖。parent `3327df8ec9cfb704788d33201b761031a2312a2b`，只改compiled_controls.py固定SHA、专属旧ref拒绝控制和本目录；没有复制本工具分支中170/e4旧35型policy、producer、生产树。root应只取工具delta，完整verifier按5caf→f853取（含0e00+bd33），不能覆盖现有55/56类型策略。

单独0e00阶段工具3327已冻结/推送，六可信SHA见其F/compiled-controls-0e00/0e00-trusted-files.sha256；0e00与bd33对照保留。若root先审核0e00，可只用3327/55型阶段；最终采用本版则须完整f853/56类型45职责51方向，拒绝旧0e00/bd33/5caf或dirty verifier。六可信文件最终hash见本目录trusted-files.sha256（实际code manifest，非新增baseline豁免）。root唯一双ref补丁来自实际公共7da3；临时Git逐字apply模拟通过，workflow未改。

原完整actual JAR/raw/class-index/source/tree/run/attempt/11hash读回不变；同模块其他source有raw但某声明类型全部缺行，仍UNKNOWN；缺索引/目标/JAR/不可解析均不能冒绿。三例SDK→router、DTO→facade、Common→Framework仍须真实javac构造派生jar和standard jdeps，两状态negative→restored；结果与原tested主JAR严格分开。root从已审定的固定工具commit取执行文件，不从可变candidate自行批准；独立ignored verifier checkout必须HEAD=f853且完全干净。

现有root Backend JDK21路径下先运行（不新增job）：

```sh
python3 <已审定固定工具checkout>/ci/architecture/compiled_controls.py --leaf-only \
  --source "$GITHUB_WORKSPACE" \
  --verifier "$GITHUB_WORKSPACE/chengyinhub-admin/target/f-private-port-verifier" \
  --output "$GITHUB_WORKSPACE/chengyinhub-admin/target/architecture-leaf-shape"
```

完整模式去掉--leaf-only，并用另一空output路径，加 `--artifact chengyinhub-admin/target/chengyinhub-admin.jar --identity chengyinhub-admin/target/chengyinhub-admin.build.json --observation chengyinhub-admin/target/architecture-jdeps`。仅leaf-shape确认不等于3例通过或应用编译通过；控制源没有任何业务调用，不填假raw/class图。原tested identity要startupPassedInThisJob=true，原图先scoped PASS，缺主产物不能降级空输入通过。

实测：8/8 Python、0skip（python-tests.txt），实际从干净f853加载producer及45职责成功、未调用JDK；六文件SHA在该checkout逐项核对。原5工具用例保留；新增仅旧ref拒绝阵列包含0e00与bd33。source verifier的196控制是独立f853结果，不与本8加总冒充同源业务测试。真实空class/interface java.lang.Object raw只由既有OpenJDK源码提供静态依据，实际JDK leaf、三个编译负控、主JAR、DB/CI全部NOT_RUN；无VM Java/新依赖/新任务CI。

完成后剩余：root独立审查后唯一repin双ref，并在获准同源CI先读回真实leaf，再执行3例原有后置控制。反射/动态XML/跨owner方法调用/传递纯读/其它业务表写权继续UNKNOWN，不声称全系统Q7或无环。f853另修确定literal写owner漏检，不借compiled fixture替代MySQL SQL执行。
