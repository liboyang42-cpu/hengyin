package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantLead;
import com.chengyinhub.business.domain.vo.NearbyMerchantVO;
import com.chengyinhub.business.domain.dto.MerchantLeadImportResult;
import com.chengyinhub.business.domain.dto.MerchantLeadImportRow;

import java.util.List;

/**
 * 商家目录/线索(含未注册商家)服务。
 */
public interface IMerchantLeadService {

    // ===== 后台管理 CRUD =====
    List<MerchantLead> selectMerchantLeadList(MerchantLead lead);

    MerchantLead selectMerchantLeadById(Long id);

    int insertMerchantLead(MerchantLead lead);

    int updateMerchantLead(MerchantLead lead);

    int deleteMerchantLeadByIds(Long[] ids);

    MerchantLeadImportResult importMerchantLeads(List<MerchantLeadImportRow> rows);

    /**
     * 附近商家目录(含未注册商家)。
     * @param canSeePhone 调用方是否有权看电话(商家/俱乐部身份=true;玩家=false)
     * 返回项已计算 canInvite(memberId!=null&&coopOpen)/canCall(phone!=null&&contactOpen&&canSeePhone);
     * 无权看电话时 phone 置空、canCall=false;不下发原始开关字段。
     */
    List<NearbyMerchantVO> nearby(double lat, double lng, int radius, int limit, boolean canSeePhone);
}
