package com.chengyinhub.business.service.invoice;

import org.springframework.stereotype.Component;

/** 缺省开票适配器：不外呼、不落第三方，申请停在 SUBMITTED 等财务人工开具后回填发票号。 */
@Component
public class ManualInvoiceIssuer implements InvoiceIssuer {
    public static final String NAME = "MANUAL";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Receipt submit(Request request) {
        return new Receipt(false, null);
    }
}
