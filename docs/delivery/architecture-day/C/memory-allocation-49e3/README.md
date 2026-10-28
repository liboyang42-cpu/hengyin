# 固定原组合：去除两段可证重复分配 SOURCE

Parent `49e37c4c13d32a92961de939facf1da57d858c3d`，tree `a25cdb27a9dd8bb684638e5fbf5a84f329b627b2`。
root 两轮 CI 修正与前序完整源码均在 parent；READONLY-ROOT-CI.sha256 记录只读后像，没有编辑它们。
本包只按已完成的原取证继续实际修补；没有新诊断、取证运行、参数或假定 OOM 分配归因。

唯一 owned Java 为 CombinedContextConfigurationTest，在原两个方法里追加直接控制；原 11 配置方法、原业务方法/全部旧断言不删。
两个必要生产接缝完整写在 production-allocation.review.patch，由 root 唯一审查/应用；本分支没有直接编辑生产文件。必须连同控制一起应用，不能把 reflection 找不到新 private helper 当作已接线。

1. **完整 Spring 的重复递归扫描**
   MyBatisConfig.sqlSessionFactory 唯一调用本类 setTypeAliasesPackage；该方法仍完成原 pattern 扫描、Class.forName 校验与原错误分支。仅在成功返回时去重包根：父包 P 已在集合中，则 P.child 的 `/**/*.class` 已被 P 的同一递归 scan 覆盖。严格点号边界，domainish 不当作 domain 子包；跨 namespace 根保留。
   现应用属性仍 com.chengyinhub.**.domain，所有 alias 类、实际完整 mapperLocations/configLocation、DataSource、插件与父 Spring graph 均保持。不是删 alias/mapper 或只启动组合涉及的子图，也不改变类初始化或异常策略。直接控制调用实际新 helper，覆盖父子根/重复项/相邻前缀及完整 namespace 可达性；原 13 XML/10 fixture alias 控制保留。

2. **真实 vision 发送前的几何扩容**
   原 chatVision 构造固定 JSONObject：model/messages，图片在前，文字在后。Fastjson 2.0.57 UTF8 writer 在大 ASCII 图字段处按 minCapacity 扩容；接着还有字段/文字，grow0 会按旧容量 1.5 倍复制，最后 getBytes 再复制为新 entity。
   新实现仍同一 default Context/provider、ofUTF8、setRootObject、JSONObject write、getBytes。仅为不含需转义字符的 ASCII image，在 admission.acquire 成功后的原序列化 try 内，预留整个固定 envelope 的空间，避免先按图字段增长再被尾部文字触发一次大复制。metadata 预留覆盖 pinned UTF16 写入的十二字节/源字符请求；固定 JSON envelope 的额外 1024 字节不是请求限制。
   null/非 ASCII 或需转义 image、非精确 JSONObject、非零默认 feature，以及超出 pinned 64MiB 默认 writer 预留范围，均走原 JSON.toJSONBytes；这是放弃优化，不是新拒绝/截断/放行。普通 chat 仍走原路径。返回始终是 getBytes 的全新副本，不借内部缓存，不跨账号缓存/共享图片或四个请求实体。
   实际 caller 只有 chat/chatVision→private post→同原 HttpUtils.sendJsonPostBytes。restore/ready/admission、原 deadline、原 NOT_SENT/UNKNOWN、lease/物理 worker、HTTP 内容/顺序/签名头与零重试均不改。没有更改 HttpUtils。

直接控制追加在现方法：

- originalDruidPropertiesAssemblesAValidUninitializedThreeConnectionPool：实际包根 helper 与边界/namespace 覆盖，原资源断言保持。
- photoPrepareFailureProjectionKeepsOnlyReviewedCodesAndCause：实际新 encoder 对完整 JSON 的逐字节等价、Unicode/转义回退、输入不变、两次输出独占；用 pinned writer 公共 ensureCapacity 返回对象证明该有限 envelope 的尾部写入不再换 buffer。没有 provider 成功 mock 或实际内存验收声称。

固定版本 primary 依据：

- 仓库 pom.xml：Fastjson 2.0.57；JSON.toJSONBytes 的默认 JSONObject 路径为 JSON.java:3279–3302。
- JSONWriterUTF8.java:500–518 为 ASCII/Latin1 minCapacity，548–560 为 UTF16 最大容量请求，1577–1593 为 grow/Arrays.copyOf；JSONWriter.java:2692–2704 为 1.5 倍增长。getBytes:165–166 仍复制，故本包不宣称消除了必要的独占输出数组。
- JSONWriter.ofUTF8(Context)、ensureCapacity、setRootObject、write(JSONObject)、getBytes 均为该 pinned 公开 API；JSONFactory.getDefaultObjectWriterProvider 返回同一个 default provider。非默认 feature 原路处理。
- pagehelper-spring-boot-starter 4.1.1→同版 parent→mybatis-spring-boot 4.0.1 parent→mybatis-spring 4.0.0；该 SqlSessionFactoryBean.scanClasses:736–755 对每个包根拼接 `/**/*.class`，正是包根覆盖等价的依据。没有执行 Maven 或解析项目。

主源 SHA256 在 primary-sources.tsv。没有复制或发布任何原运行数据/日志。
这两处去除的是完整启动和真实 held 前的重复分配，不是删必要工作集；Metaspace/独占 body/父 Spring/两个 DruidWall child 仍存在。不把各样本相加、heapUsed 当存活量或据此承诺 1GiB 已通过。
1CPU/1GiB/no-swap/256PID、全部 JVM/线程/GC/JIT/pool/时限、4×16MiB held、双 child/真实 SQL、72 身份/240HTTP、经济/权限/UNKNOWN/清理全部保持。
全部 SOURCE / NOT_RUN：没有 parse/import/tests/Java/Maven/编译/DB/Redis/Docker/CI；只做 Git 差分/apply-check/摘要和限定扫描。root 源码齐后按原目标统一必要验收。
