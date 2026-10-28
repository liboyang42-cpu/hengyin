# 真库 Maven source/cache 调用核对：不改运行源码

结论：暂无证据证明真库入口的 repository/settings/cache 调用存在独有缺陷。本片不修改 `ci/real_storage_it.py`、workflow、POM/版本/依赖或全局M2，不加mirror/offline/缓存来源重写。parent `fb871607e4c4a5a038776201ff0b785f999f9ab1`；独立分支 `codex/architecture-day-f-storage-maven-source`。

实际只通过 GitHub connector 回读既有日志和读取公共source，没执行任何Maven/Java/DB/HTTP探测、控制、parse、编译或CI。没有重跑已过项目，没把模型失败当业务失败。逐项现成回执见 `EVIDENCE.json`，不含全量原日志/凭据/settings正文。

## 可核事实

五个同源job均恢复key `setup-java-Linux-x64-maven-0ea77dcc91feead05a75d8f04a264c813aee27a4da06a487d4799f85861eca64`，显示cache大小152674761 bytes，setup-java创建默认settings的server-id为github。

| 原run/job | 实际解析边界 |
|---|---|
| storage40219261122 /121247151219，ClmCommercePaymentProofMySqlIT | 三个import POM报present-but-unavailable/无法从central解析，Maven模型阶段停止。测试0/8，业务执行NOT_RUN；后续清理由原入口执行，不补签通过 |
| 同storage /121247151229，CoopOrderStationFulfillmentMySqlIT | 同样有旧repository ID不可用的warning，但模型解析已过，实际到了admin testCompile失败；H/G/E fixture问题另owner处理 |
| standard40219238315 /121247109875，Backend | 同key/同settings日志，旧IDwarning后已到testCompile失败 |
| 同standard /121247109763，SBOM build | 同key/同settings日志，已到testCompile失败，SBOM未生成 |
| HTTP40219266741 /121247076937，FreezeGame在线前置 | 同key/同settings日志，已到testCompile失败，HTTP未执行 |

因此旧IDwarning不能单独当作必然失败机制。key/大小相同**不证明cache archive bytes/producer/原artifact可信来源已核验**。失败日志只给了“cached from a remote repository ID ... unavailable”，没有旧ID值；也没有字面HTTP404响应。Maven `Could not find artifact`不足以独立证明网络edge/代理/中心库实际HTTP状态，均保UNKNOWN。没有读取或改写runner的 `_remote.repositories`/`.lastUpdated`/全局settings；不从猜测ID生成镜像。

## 实际source调用合同

- 原 `pom.xml` repositories/pluginRepositories均为 `id=central`、`https://repo.maven.apache.org/maven2`；三版本仍原2.21.7/3.1.7/4.1.1。本source没有tracked `.mvn` overrides。
- 真库：setup-java Temurin/登记JDK，cache-dependency-path=`candidate/**/pom.xml`；原 `run_maven` 在线 `mvn -B -ntp -f candidate/pom.xml -pl chengyinhub-admin -am test -Dtest=<固定登记类>`，不传settings/offline/mirror。`child_env`没有删除或改写Maven设置，仍保原精确业务gate过滤。
- 标准Backend/SBOM：相同setup-java maven cache（默认POM hash），原root cwd调用 `mvn -B clean test` / `mvn -B -q -pl chengyinhub-admin -am -DskipTests package`。Backend额外MAVEN_OPTS/ARGS只为原内存/测试context，未设置repository。实际key已证与真库一致，不能凭双checkout路径猜测是另份cache。
- HTTP：显式cache-dependency-path=`**/pom.xml`，**先在线** `mvn -B -q -pl chengyinhub-admin -am test -DskipTests`，随后才原受控offline执行。不能复制offline到尚未解析依赖的真库入口冒充解决。

这些source与实际回执没有显示一个已批准、只有成功路径使用的不同mirror/settings入口。也没有证明改cache namespace或清元数据能解决；强制冷cache可能使所有消费者重新下载，不能为了拿绿贸然改。

## 有界处置建议（未执行、非新自动机制）

待其它owner testCompile共同修补入同一新冻结SHA后，root可在原real-storage手动入口用原batch + 精确 `classes=ClmCommercePaymentProofMySqlIT` 做**一次**失败入口定点复验；workflow ref/input source SHA仍严格同源，保原gate/在线central校验/零skip/原XML/清理回读。该建议不是重跑已通过项目，也不借旧run通过签新SHA。

若同一模型错误再现则停止自动重试，保留原模型非零/0 XML事实，由root取得该runner的安全repository-ID/negative-cache状态及真实传输响应后再决定最小修补。不得自动反复运行整个`mvn test`，不得删metadata将cache伪标central、默认offline、换URL/代理、关闭TLS/checksum或导入凭据。没有确认新代码必要性，故不新增重试循环或假通过负控。

本片所有测试/编译/DB/CI验证均NOT_RUN；这是现有真实日志比较后的无需改运行源码结论，不是依赖/业务/真库通过结论。无需root应用任何runtime patch；只需取本F证据，作者文件摘要见`POSTIMAGES.sha256`。
