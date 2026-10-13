# F15 CRM 电话解密映射修复

基线：`7860bfa0f79ad77d02a4e3051465353ec86cafad`；本次生产改动仅 `MerchantCrmAppMapper.xml` 的 CustomerRow.phone result 映射。

## 问题与修复

客户列表与导出共用 CustomerRow。负责方报名电话优先、会员电话兜底的 SQL 投影未绑定现有 PiiCryptoTypeHandler，数据库 v1 密文直接进入 VO，导出再对密文执行手机号掩码。

补上项目现有解密映射；不改变客户归属 SQL、同意记录检查、导出脱敏方式、加密实现或数据库。报名与会员兜底两条来源均覆盖。历史明文沿用现有 TypeHandler 兼容逻辑。

## 验证

- RED：真实 MyBatis 映射读取测试密文，预期 13800000000，实际 v1 密文；4 项中 1 项失败。日志 `/tmp/cy-f15-red.log`。
- GREEN：同一测试读取报名密文得到明文；通过实际导出行投影且同意记录成立时输出 `138****0000`；报名电话为空时，会员密文读取为 `13900000000`。
- `MerchantCrmProfileNamespaceMyBatisTest` 4 项、`MerchantCrmAppServiceImplTest` 23 项、`MerchantCrmExportServiceImplTest` 22 项，共 49 项通过，无失败/错误/跳过。日志 `/tmp/cy-f15-green.log`。
- 回归使用 H2 与测试专用加密密钥，恢复原 PiiCryptoService 静态实例，不接触生产数据或密钥。

尚未验证真实 MySQL、HTTP 列表/下载、原生 UI 或生产发布。现有 TypeHandler 未初始化时透传的全局行为不在本次改动内；本次验证使用已注册的加密服务。

## 总控 MySQL、文件与原生页面补验

`/tmp/f15-probe/evidence.md`及`run.log`：MySQL8.0.45真实Mapper→正式导出执行器/helper→正式下载Controller→XLSX全单元格解析通过。报名电话与会员兜底两来源均掩码，文件不含原号码或密文，未同意不导出号码。仅/tmp移除解密映射的负控确实失败。

真实列表Service/Controller合成JSON交给原生CRM响应handler，三个场景截图均已查看：有敏感权限且获同意显示完整号码，未同意隐藏号码，保持既有规则。请求恢复并disconnect；UUID临时schema已删除并回读确认。

上述补齐早先未验证的MySQL、实际下载文件与受控UI；权限、同意、任务/审计协作者仍为mock，不证明HTTP安全过滤器、真实登录、任务CAS/审计事务或生产发布。
