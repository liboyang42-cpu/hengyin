package com.chengyinhub.business.service;

/** 将通用上传回执绑定到当前会员、对象键和真实字节数，社区登记时据此证明资产来源。 */
public interface ICommunityMediaReceiptService {
    String issue(Long memberId, String objectKey, long byteSize);
    boolean verify(Long memberId, String objectKey, long byteSize, String receipt);
}
