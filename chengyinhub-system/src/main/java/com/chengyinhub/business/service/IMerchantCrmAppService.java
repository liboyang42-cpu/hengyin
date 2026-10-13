package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantCrmNote;
import com.chengyinhub.business.domain.dto.MerchantCrmNoteRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmBatchTagRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.dto.MerchantCrmTagRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerDetailVO;
import com.chengyinhub.business.domain.vo.MerchantCrmBatchTagResultVO;
import com.chengyinhub.business.domain.vo.MerchantCrmContactVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerPageVO;
import com.chengyinhub.business.domain.vo.MerchantCrmTagVO;
import com.chengyinhub.business.domain.vo.MerchantCrmProfileReconcileVO;

public interface IMerchantCrmAppService {
    MerchantCrmCustomerPageVO listCustomers(Long principalMemberId, MerchantCrmCustomerQuery query);
    MerchantCrmProfileReconcileVO reconcileProfiles(Long principalMemberId);
    MerchantCrmCustomerDetailVO detail(Long principalMemberId, Long customerMemberId);
    /** F15 获取客户联系号码:权限/归属/同意/日限全过才下发明文,并写一条访问审计。 */
    MerchantCrmContactVO revealContact(Long principalMemberId, Long customerMemberId, String purpose);
    MerchantCrmNote addNote(Long principalMemberId, Long customerMemberId, MerchantCrmNoteRequest request);
    void hideNote(Long principalMemberId, Long customerMemberId, Long noteId,
                  Integer expectedVersion, String requestId);
    MerchantCrmTagVO assignTag(Long principalMemberId, Long customerMemberId, MerchantCrmTagRequest request);
    MerchantCrmBatchTagResultVO batchAssignTag(Long principalMemberId, MerchantCrmBatchTagRequest request);
    void removeTag(Long principalMemberId, Long customerMemberId, Long tagId, String requestId);
}
