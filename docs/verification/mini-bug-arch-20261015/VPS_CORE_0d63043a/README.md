# CORE 0d63043a：157项针对性复测通过

公共完整候选：`0d63043ad06775bc3dc52fb925620ef36d1081a7`，[PR44](https://github.com/liboyang42-cpu/hengyin/pull/44)。对应原 `f7e0e523` 的13个失败/错误，原作者串行交付8个测试类的夹具/边界修正；生产源码未改，旧断言/负控保留。原始483项失败记录继续保留，不改写历史。

本地在专用测试VPS使用212守卫实际执行8个精确失败类：**157项通过，0失败、0错误、0跳过**。8份XML均为本次开始后的新报告，类集合与预选一致，数量与修复前这8类的157项相同；守卫退出0/completed。完整命令、起止时间、源包/日志/XML哈希见 [summary.json](summary.json)。

这是针对性复测PASS，**不代表在新SHA重新跑了全部483项**。f7到本候选仅8测试类和对应修复文档变化，另外75类没有无理由重复跑；本记录不继承它们为本SHA实跑。真实MySQL/Redis并发、实际后端HTTP状态、设备、集成合并与部署均另验。

修复说明：C03保留缺ID/零/负值负控并避免fixture三元拆箱空指针；媒体提交后的异常经真实GlobalExceptionHandler翻译；C04保留锁序并核真实二次锁读，避免嵌套Mockito stub，现金提现沿真实插入原行身份且增加断言；C06避免覆盖已有stub时提前触发Answer，使用合法版本冻结规则、补已有CAS/当前读fixture。未通过删断言、跳过或修改生产语义消除失败。

资源：`chengyin-build-budget-212`，1536MiB内存且同额memory-swap无额外swap、1CPU、256PIDs、network none无发布端口，已有java-build.lock持有到结束；准入≥2560MiB/运行低于768MiB只中止自有容器。精确本次容器已守卫清理，原/新source、cache、日志及XML各自保留于独立task目录。没有变更其他服务、网络或预览。

结果已直接交C00按157项targeted PASS签收；其原队继续开发、修复槽可取下一真实项。新Cloud/EXTRA及后续生产源码变更不能沿用本结果。
