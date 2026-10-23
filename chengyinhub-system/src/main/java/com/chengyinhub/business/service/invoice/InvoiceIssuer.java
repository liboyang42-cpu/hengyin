package com.chengyinhub.business.service.invoice;

import java.math.BigDecimal;

/**
 * DOC2 F7 开票适配器边界。当前只有 {@link ManualInvoiceIssuer}（人工开具，不调用任何外部服务）；
 * 接入税控/电子发票服务商须另行裁决（付费、新依赖都在 AGENTS.md「先问」范围内）。
 *
 * <p>约定：真实适配器必须幂等（以 applicationNo 为幂等键），且不得在申请事务内做网络调用。
 */
public interface InvoiceIssuer {

    /** 适配器名，落 user_invoice_application.provider。 */
    String name();

    /** 受理一张已落库的申请。人工适配器只返回「待人工开具」，不做任何外呼。 */
    Receipt submit(Request request);

    /** 交给适配器的最小信息；抬头为明文快照，只在进程内传递，不写日志。 */
    record Request(String applicationNo, String titleType, String titleName, String taxNo, BigDecimal amount) { }

    /** accepted=false 表示需人工处理；providerRef 为服务商侧单号（人工为 null）。 */
    record Receipt(boolean accepted, String providerRef) { }
}
