# 报名查询边界

Parent：beffa2023a68ffba2da5fce35f00e697252e72f4。实际源 bdb6be21035f2d26810850c411b665e37343b82e，RegistrationQueryApplication blob 918f9160ec4899d7e3f2733de4cbadc0ba2ab3c4。

新增唯一 owner C_REGISTRATION_QUERY_APPLICATION / admin / com.chengyinhub.web.service.registration.RegistrationQueryApplication。原 18 注入类型当前均不属于已声明技术 owner，新增允许方向为零；原 Controller→query 六处委托及共享谓词静态桥保留。18 类型逐一为协议正控，topicChapterService 注入存在但当前无方法调用。

精确禁止回指 ApiRegistrationController/BaseController，以及直接依赖 CmsRegistrationServiceImpl/CmsRegistrationMapper/CmsRegistrationPaymentFactMapper/RefundServiceImpl/RefundApplicationMapper/RegistrationCheckoutServiceImpl。70 类型、58 owner、68 方向；3 私有 Mapper、2 whole-table facts、旧方向与 2ec baseline 不变，六可信文件另五文件逐字等于 parent。

原混合读写接口 ICmsRegistrationService、IRefundService 等仍是已有读取依赖；class 图不能区分其读写方法，不能由此签传递纯读或新的写授权。未知引用仍为 unscoped observation，graph_complete=false；尚未交付的扫码/核销类型未预授权。

Python discover 234/234、0skip、23.020s：含 18 类型正控、8 精确反向/写端负控、query 缺 raw UNKNOWN。协议为 NOT_COMPILED fixture；真实 Java/JAR/jdeps/DB/CI NOT_RUN。限定 diff / Python3.9 grammar / 秘密扫描通过。只修改 owners、专属测试和交付；生产/CI 不改。
