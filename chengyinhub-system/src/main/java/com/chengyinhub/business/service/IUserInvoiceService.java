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

    void deleteTitle(Long memberId, Long titleId);

    List<UserInvoiceOrderLine> listInvoiceableOrders(Long memberId);

    /** 合并多笔订单申请一张发票；同 requestId 重放返回同一张申请，订单集合不同则拒绝。 */
    Map<String, Object> apply(Long memberId, String requestId, Long titleId, List<Long> orderIds);

    List<Map<String, Object>> listApplications(Long memberId);

    Map<String, Object> applicationDetail(Long memberId, Long applicationId);

    Map<String, Object> cancel(Long memberId, Long applicationId);

    /** 人工开具完成后回填发票号（财务后台用，本 PR 不开放接口）。 */
    UserInvoiceApplication markIssued(Long applicationId, String invoiceNo);

    /** 人工驳回并释放订单占用（财务后台用，本 PR 不开放接口）。 */
    UserInvoiceApplication markRejected(Long applicationId, String reason);
}
