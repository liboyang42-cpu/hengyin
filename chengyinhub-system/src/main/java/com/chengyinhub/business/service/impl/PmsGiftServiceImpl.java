package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Objects;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.business.mapper.PmsGiftMapper;
import com.chengyinhub.business.mapper.PmsGiftChangeAuditMapper;
import com.chengyinhub.business.domain.PmsGift;
import com.chengyinhub.business.domain.PmsGiftChangeAudit;
import com.chengyinhub.business.service.IPmsGiftService;

/**
 * 众筹礼物Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
@Service
public class PmsGiftServiceImpl implements IPmsGiftService 
{
    private static final long MAX_GIFT_POINT = Integer.MAX_VALUE;

    @Autowired
    private PmsGiftMapper pmsGiftMapper;

    @Autowired
    private PmsGiftChangeAuditMapper pmsGiftChangeAuditMapper;

    /**
     * 查询众筹礼物
     * 
     * @param id 众筹礼物主键
     * @return 众筹礼物
     */
    @Override
    public PmsGift selectPmsGiftById(Long id)
    {
        return pmsGiftMapper.selectPmsGiftById(id);
    }

    /**
     * 查询众筹礼物列表
     * 
     * @param pmsGift 众筹礼物
     * @return 众筹礼物
     */
    @Override
    public List<PmsGift> selectPmsGiftList(PmsGift pmsGift)
    {
        return pmsGiftMapper.selectPmsGiftList(pmsGift);
    }

    /**
     * 查询 C 端可实际赠送的礼物目录。
     *
     * @return 礼物集合
     */
    @Override
    public List<PmsGift> selectAvailablePmsGiftList()
    {
        return pmsGiftMapper.selectAvailablePmsGiftList();
    }

    /**
     * 新增众筹礼物
     * 
     * @param pmsGift 众筹礼物
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertPmsGift(PmsGift pmsGift)
    {
        requireGift(pmsGift);
        String auditReason = requireAuditReason(pmsGift);
        String operator = requireOperator(pmsGift.getCreateBy());
        if (pmsGift.getStatus() == null)
        {
            // 新目录项默认停用，避免尚未核对素材/价格时直接出现在 C 端。
            pmsGift.setStatus(2);
        }
        // 新建目录项不能由请求写成逻辑删除。
        pmsGift.setDelFlag(0);
        validateStatus(pmsGift.getStatus());
        validatePoint(pmsGift.getPoint(), true);
        pmsGift.setCreateTime(DateUtils.getNowDate());
        pmsGift.setCreateBy(operator);
        int rows = pmsGiftMapper.insertPmsGift(pmsGift);
        if (rows != 1 || pmsGift.getId() == null)
        {
            throw new ServiceException("新增礼物失败");
        }
        recordChange(pmsGift.getId(), "CREATE", null, pmsGift.getPoint(), null, pmsGift.getStatus(), auditReason, operator);
        return rows;
    }

    /**
     * 修改众筹礼物
     * 
     * @param pmsGift 众筹礼物
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updatePmsGift(PmsGift pmsGift)
    {
        requireGift(pmsGift);
        if (pmsGift.getId() == null)
        {
            throw new ServiceException("礼物编号不能为空");
        }

        String auditReason = requireAuditReason(pmsGift);
        String operator = requireOperator(pmsGift.getUpdateBy());
        PmsGift existing = pmsGiftMapper.selectPmsGiftByIdForUpdate(pmsGift.getId());
        if (existing == null)
        {
            throw new ServiceException("礼物不存在或已删除");
        }
        if (!Integer.valueOf(0).equals(existing.getDelFlag()))
        {
            throw new ServiceException("礼物不存在或已删除");
        }
        if (pmsGift.getDelFlag() != null && !Objects.equals(pmsGift.getDelFlag(), existing.getDelFlag()))
        {
            throw new ServiceException("礼物删除状态不能通过编辑修改");
        }

        // 不信任客户端传来的“调价前积分”；只把锁定行读到的旧值写入审计记录与操作日志。
        pmsGift.setAuditBeforePoint(null);
        validatePoint(pmsGift.getPoint(), false);
        validateStatus(pmsGift.getStatus());
        Long newPoint = pmsGift.getPoint() == null ? existing.getPoint() : pmsGift.getPoint();
        Integer newStatus = pmsGift.getStatus() == null ? existing.getStatus() : pmsGift.getStatus();
        if (Integer.valueOf(1).equals(pmsGift.getStatus()) && !Integer.valueOf(1).equals(existing.getStatus()))
        {
            validatePoint(newPoint, true);
        }
        boolean pointChanged = !Objects.equals(existing.getPoint(), newPoint);
        if (pointChanged)
        {
            pmsGift.setAuditBeforePoint(existing.getPoint());
        }
        pmsGift.setAuditReason(auditReason);
        // 这些字段由数据库/服务端维护，通用编辑接口不能伪造或恢复删除状态。
        pmsGift.setDelFlag(null);
        pmsGift.setCreateBy(null);
        pmsGift.setCreateTime(null);
        pmsGift.setUpdateBy(null);
        pmsGift.setUpdateTime(DateUtils.getNowDate());
        int rows = pmsGiftMapper.updatePmsGift(pmsGift);
        if (rows != 1)
        {
            throw new ServiceException("更新礼物失败");
        }
        recordChange(pmsGift.getId(), resolveChangeAction(existing, newPoint, newStatus), existing.getPoint(), newPoint,
            existing.getStatus(), newStatus, auditReason, operator);
        return rows;
    }

    /**
     * 批量删除众筹礼物
     * 
     * @param ids 需要删除的众筹礼物主键
     * @return 结果
     */
    @Override
    public int deletePmsGiftByIds(Long[] ids)
    {
        throw new ServiceException("礼物目录不支持物理删除，请停用礼物；删除处置须完成四重死活检查");
    }

    /**
     * 删除众筹礼物信息
     * 
     * @param id 众筹礼物主键
     * @return 结果
     */
    @Override
    public int deletePmsGiftById(Long id)
    {
        throw new ServiceException("礼物目录不支持物理删除，请停用礼物；删除处置须完成四重死活检查");
    }

    private void requireGift(PmsGift pmsGift)
    {
        if (pmsGift == null)
        {
            throw new ServiceException("礼物参数不能为空");
        }
    }

    private void validatePoint(Long point, boolean required)
    {
        if (point == null)
        {
            if (required)
            {
                throw new ServiceException("礼物所需积分不能为空");
            }
            return;
        }
        if (point <= 0 || point > MAX_GIFT_POINT)
        {
            throw new ServiceException("礼物所需积分必须为 1 到 " + MAX_GIFT_POINT + " 的整数");
        }
    }

    private void validateStatus(Integer status)
    {
        if (status != null && status != 1 && status != 2)
        {
            throw new ServiceException("礼物状态只能为可赠送或已停用");
        }
    }

    private String requireAuditReason(PmsGift pmsGift)
    {
        String reason = pmsGift.getAuditReason() == null ? "" : pmsGift.getAuditReason().trim();
        if (reason.length() == 0)
        {
            throw new ServiceException("礼物目录变更必须填写变更说明");
        }
        if (reason.length() > 500)
        {
            throw new ServiceException("变更说明不能超过 500 个字符");
        }
        return reason;
    }

    private String requireOperator(String operator)
    {
        String username = operator == null ? "" : operator.trim();
        if (username.length() == 0)
        {
            throw new ServiceException("未识别到礼物目录操作人");
        }
        if (username.length() > 64)
        {
            throw new ServiceException("礼物目录操作人名称不能超过 64 个字符");
        }
        return username;
    }

    private String resolveChangeAction(PmsGift existing, Long newPoint, Integer newStatus)
    {
        if (!Objects.equals(existing.getPoint(), newPoint))
        {
            return "PRICE_CHANGE";
        }
        if (!Objects.equals(existing.getStatus(), newStatus))
        {
            return "STATUS_CHANGE";
        }
        return "DETAIL_CHANGE";
    }

    private void recordChange(Long giftId, String action, Long oldPoint, Long newPoint, Integer oldStatus,
        Integer newStatus, String reason, String operator)
    {
        PmsGiftChangeAudit audit = new PmsGiftChangeAudit();
        audit.setGiftId(giftId);
        audit.setAction(action);
        audit.setOldPoint(oldPoint);
        audit.setNewPoint(newPoint);
        audit.setOldStatus(oldStatus);
        audit.setNewStatus(newStatus);
        audit.setReason(reason);
        audit.setOperatorUsername(operator);
        audit.setCreateTime(DateUtils.getNowDate());
        if (pmsGiftChangeAuditMapper.insertPmsGiftChangeAudit(audit) != 1)
        {
            throw new ServiceException("写入礼物目录审计记录失败");
        }
    }
}
