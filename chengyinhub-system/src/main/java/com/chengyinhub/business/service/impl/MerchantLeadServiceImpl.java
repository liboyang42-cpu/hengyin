package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantLead;
import com.chengyinhub.business.domain.vo.NearbyMerchantVO;
import com.chengyinhub.business.domain.dto.MerchantLeadImportResult;
import com.chengyinhub.business.domain.dto.MerchantLeadImportRow;
import com.chengyinhub.business.mapper.MerchantLeadMapper;
import com.chengyinhub.business.service.IMerchantLeadService;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MerchantLeadServiceImpl implements IMerchantLeadService {
    private static final Logger log = LoggerFactory.getLogger(MerchantLeadServiceImpl.class);

    @Autowired
    private MerchantLeadMapper merchantLeadMapper;

    @Override
    public List<MerchantLead> selectMerchantLeadList(MerchantLead lead) {
        return merchantLeadMapper.selectMerchantLeadList(lead);
    }

    @Override
    public MerchantLead selectMerchantLeadById(Long id) {
        return merchantLeadMapper.selectMerchantLeadById(id);
    }

    @Override
    public int insertMerchantLead(MerchantLead lead) {
        if (lead.getStatus() == null) lead.setStatus(1);
        if (lead.getContactOpen() == null) lead.setContactOpen(1);
        if (lead.getCoopOpen() == null) lead.setCoopOpen(1);
        if (lead.getDelFlag() == null) lead.setDelFlag(0);
        if (lead.getSource() == null) lead.setSource("offline");
        lead.setCreateTime(DateUtils.getNowDate());
        lead.setUpdateTime(DateUtils.getNowDate());
        return merchantLeadMapper.insertMerchantLead(lead);
    }

    @Override
    public int updateMerchantLead(MerchantLead lead) {
        lead.setUpdateTime(DateUtils.getNowDate());
        return merchantLeadMapper.updateMerchantLead(lead);
    }

    @Override
    public int deleteMerchantLeadByIds(Long[] ids) {
        return merchantLeadMapper.deleteMerchantLeadByIds(ids);
    }

    @Override
    public MerchantLeadImportResult importMerchantLeads(List<MerchantLeadImportRow> rows) {
        MerchantLeadImportResult result = new MerchantLeadImportResult();
        result.setTotalCount(rows == null ? 0 : rows.size());
        if (rows == null) return result;
        for (MerchantLeadImportRow row : rows) {
            MerchantLead lead;
            try {
                lead = toMerchantLead(row);
            } catch (IllegalArgumentException e) {
                result.addFailure(row.getRowNumber(), e.getMessage());
                continue;
            }
            try {
                if (insertMerchantLead(lead) <= 0) {
                    result.addFailure(row.getRowNumber(), "写入未生效，请重试");
                    continue;
                }
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (RuntimeException e) {
                log.error("商家线索批量导入第 {} 行写入异常", row.getRowNumber(), e);
                result.addFailure(row.getRowNumber(), "系统写入异常，请稍后重试");
            }
        }
        return result;
    }

    private MerchantLead toMerchantLead(MerchantLeadImportRow row) {
        if (row.getParseError() != null) throw new IllegalArgumentException(row.getParseError());
        MerchantLead lead = new MerchantLead();
        lead.setName(required(row.getName(), "商家名称", 128));
        lead.setPhone(optional(row.getPhone(), "联系电话", 32));
        lead.setAddress(optional(row.getAddress(), "地址", 255));
        lead.setCategory(optional(row.getCategory(), "类型", 64));
        lead.setTags(optional(row.getTags(), "标签", 255));
        String source = optional(row.getSource(), "来源", 32);
        lead.setSource(source == null ? "bd_import" : source);
        lead.setMemberId(parseMemberId(row.getMemberId()));
        lead.setContactOpen(parseFlag(row.getContactOpen(), "开放联系", 1));
        lead.setCoopOpen(parseFlag(row.getCoopOpen(), "开放协作", 1));
        lead.setStatus(parseFlag(row.getStatus(), "展示状态", 1));
        applyCoordinates(lead, row.getLongitude(), row.getLatitude());
        return lead;
    }

    private String required(String value, String label, int maxLength) {
        String text = optional(value, label, maxLength);
        if (text == null) throw new IllegalArgumentException(label + "不能为空");
        return text;
    }

    private String optional(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) return null;
        String text = value.trim();
        if (text.length() > maxLength) throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 字");
        return text;
    }

    private Long parseMemberId(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String text = value.trim();
        if (!text.matches("[1-9]\\d*")) throw new IllegalArgumentException("绑定会员ID必须是正整数");
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("绑定会员ID超出可用范围");
        }
    }

    private Integer parseFlag(String value, String label, int defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        String text = value.trim();
        if (!"0".equals(text) && !"1".equals(text)) {
            throw new IllegalArgumentException(label + "只能是 0 或 1");
        }
        return Integer.valueOf(text);
    }

    private void applyCoordinates(MerchantLead lead, String longitude, String latitude) {
        String lng = longitude == null ? "" : longitude.trim();
        String lat = latitude == null ? "" : latitude.trim();
        if (lng.isEmpty() && lat.isEmpty()) return;
        if (lng.isEmpty() || lat.isEmpty()) throw new IllegalArgumentException("经度和纬度必须同时填写");
        try {
            BigDecimal lngValue = new BigDecimal(lng);
            BigDecimal latValue = new BigDecimal(lat);
            if (lngValue.compareTo(BigDecimal.valueOf(-180)) < 0 || lngValue.compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new IllegalArgumentException("经度必须在 -180 到 180 之间");
            }
            if (latValue.compareTo(BigDecimal.valueOf(-90)) < 0 || latValue.compareTo(BigDecimal.valueOf(90)) > 0) {
                throw new IllegalArgumentException("纬度必须在 -90 到 90 之间");
            }
            lead.setLongitude(lng);
            lead.setLatitude(lat);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("经度和纬度必须是数字");
        }
    }

    @Override
    public List<NearbyMerchantVO> nearby(double lat, double lng, int radius, int limit, boolean canSeePhone) {
        List<NearbyMerchantVO> list = merchantLeadMapper.selectNearbyLeads(lat, lng, radius, limit);
        if (list == null) return java.util.Collections.emptyList();
        for (NearbyMerchantVO vo : list) {
            boolean hasPhone = vo.getPhone() != null && !vo.getPhone().trim().isEmpty();
            // canInvite:已绑定小程序账号且开放线上协作(能走 /api/coop/invite 的 toId=memberId)
            vo.setCanInvite(vo.getMemberId() != null && isOpen(vo.getCoopOpen()));
            // canCall:有权看电话 且 有电话 且 开放电话联系
            vo.setCanCall(canSeePhone && hasPhone && isOpen(vo.getContactOpen()));
            if (!vo.getCanCall()) {
                // 只有真正可拨打(有权+有号+开放电话联系)才下发手机号,否则一律脱敏——
                // 防「商家侧 canCall=false 但 phone 仍在返回 JSON 被抓包拿到真号」。
                vo.setPhone(null);
            }
            // 原始开关不下发,避免泄露内部策略字段
            vo.setContactOpen(null);
            vo.setCoopOpen(null);
        }
        return list;
    }

    /** 开关默认开放:null 或 1 视为开(建表默认 1)。 */
    private boolean isOpen(Integer flag) {
        return flag == null || flag == 1;
    }
}
