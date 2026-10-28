package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UserInvoiceApplication;
import com.chengyinhub.business.domain.UserInvoiceOrderLine;
import com.chengyinhub.business.domain.UserInvoiceTitle;

import java.util.List;
import java.util.Map;

/**
 * DOC2 F7 用户发票。会员侧方法第一个参数恒为当前登录会员，所有读写按它做归属过滤；
 * 回读视图里的税号一律掩码。业务拒绝抛 {@link com.chengyinhub.business.service.invoice.UserInvoiceException}。
 */
public interface IUserInvoiceService {
    List<Map<String, Object>> listTitles(Long memberId);

    /** id 为空新建，否则只改本人的那一条。返回掩码视图。 */
    Map<String, Object> saveTitle(Long memberId, UserInvoiceTitle input);

    /** 新客户端保存命令：同号同规范化载荷重放；结果回执不携带名称或税号。 */
    Map<String, Object> saveTitleWithRequest(Long memberId, String requestId, UserInvoiceTitle input);

    Map<String, Object> titleSaveReceipt(Long memberId, String requestId);

    void deleteTitle(Long memberId, Long titleId);

    List<UserInvoiceOrderLine> listInvoiceableOrders(Long memberId);

    /** 合并多笔订单申请一张发票；同 requestId 重放返回同一张申请，订单集合不同则拒绝。 */
    Map<String, Object> apply(Long memberId, String requestId, Long titleId, List<Long> orderIds);

    List<Map<String, Object>> listApplications(Long memberId);

    Map<String, Object> applicationDetail(Long memberId, Long applicationId);

    /** 本人原请求精确回读，不受最近申请列表条数限制；未查到不代表未写入。 */
    Map<String, Object> applicationByRequest(Long memberId, String requestId);

    Map<String, Object> cancel(Long memberId, Long applicationId);

    /** 旧无 scope 写口仅为二进制兼容保留，始终拒绝；改用 UserInvoiceProcessingService。 */
    UserInvoiceApplication markIssued(Long applicationId, String invoiceNo);

    /** 旧无 scope 写口始终拒绝；不得绕过处理人/主体/回执合同。 */
    UserInvoiceApplication markRejected(Long applicationId, String reason);
}
