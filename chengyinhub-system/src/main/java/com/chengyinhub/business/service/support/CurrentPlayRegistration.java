package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 新玩法使用的当前资格；调用方在原事务中锁 activity→registration，历史读取不调用它。 */
public final class CurrentPlayRegistration {
    private CurrentPlayRegistration() { }
    public static CmsRegistration require(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now) {
        return require(activities,registrations,memberId,ownerType,ownerId,requestedId,now,false);
    }
    public static CmsRegistration require(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now,boolean originallyRequiresPaid) {
        return collect(activities,registrations,memberId,ownerType,ownerId,requestedId,now,originallyRequiresPaid,true,false).get(0);
    }
    /**
     * 继续已开局的玩法：自玩通行证在本局有效期内开局后，本局可打完(不因通行证中途到期打断)。
     * 退款/归属变化/非成功报名仍拒绝；活动场次与探索票照原规则(活动结束即止)。
     */
    public static CmsRegistration requireToContinue(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now) {
        return collect(activities,registrations,memberId,ownerType,ownerId,requestedId,now,false,true,true).get(0);
    }
    public static List<CmsRegistration> requireAllToContinue(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Date now) {
        return collect(activities,registrations,memberId,ownerType,ownerId,null,now,false,false,true);
    }
    /** 全部当前仍可用的原报名(逐行同一锁读规则)；多票/多报名原「任一成功报名可授权」的链用它，不只取第一张。 */
    public static List<CmsRegistration> requireAll(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Date now) {
        return collect(activities,registrations,memberId,ownerType,ownerId,null,now,false,false,false);
    }
    private static List<CmsRegistration> collect(CmsActivityMapper activities,CmsRegistrationMapper registrations,
            Long memberId,long ownerType,Long ownerId,Long requestedId,Date now,boolean originallyRequiresPaid,boolean firstOnly,
            boolean continuing) {
        List<CmsRegistration> valid=new ArrayList<>();
        CmsActivity activity=null;
        if(ownerType==2L) {
            activity=activities.selectCmsActivityByIdForUpdate(ownerId);
            if(activity==null || activity.getCancelTime()!=null || Integer.valueOf(2).equals(activity.getDelFlag())) {
                throw new IllegalStateException("活动不存在或已取消");
            }
        }
        CmsRegistration probe=new CmsRegistration();probe.setMemberId(memberId);probe.setOwnerType(ownerType);
        probe.setOwnerId(ownerId);probe.setRegistrationStatus(2);
        if(originallyRequiresPaid) probe.setPaymentStatus(2);
        List<CmsRegistration> rows=registrations.selectCmsRegistrationList(probe);
        boolean candidate=false;
        if(rows!=null) for(CmsRegistration snapshot:rows) {
            if(snapshot==null || snapshot.getId()==null || (requestedId!=null && !requestedId.equals(snapshot.getId()))) continue;
            candidate=true;
            CmsRegistration current=registrations.selectCmsRegistrationByIdForUpdate(snapshot.getId());
            if(!currentOwner(current,memberId,ownerType,ownerId)) continue;
            // 普通/免费报名沿用原status+owner资格；只有原本以支付状态为真源的链和原pk3订单要求已付。
            if((originallyRequiresPaid || Integer.valueOf(3).equals(current.getPurchaseKind()))
                    && !Integer.valueOf(2).equals(current.getPaymentStatus())) continue;
            if(ownerType==1L && !continuing && current.getExpiresAt()!=null && !current.getExpiresAt().after(now)) continue;
            String deny=ExploreTicketUsePolicy.rejection(current.getPurchaseKind(),activity==null?null:activity.getEndDate(),now);
            if(deny!=null) throw new IllegalStateException(deny);
            valid.add(current);
            if(firstOnly) return valid;
        }
        if(!valid.isEmpty()) return valid;
        // 从未有成功报名/通行证时沿用原入口文案；有原对象但当前已不可用才提示失效。
        if(!candidate) throw new IllegalStateException(ownerType==1L ? "请先购买自玩通行证" : "请先报名并完成支付");
        throw new IllegalStateException(ownerType==1L ? "当前自玩资格已失效" : "当前报名资格已失效");
    }
    public static boolean currentOwner(CmsRegistration current,Long memberId,long ownerType,Long ownerId) {
        return current!=null && memberId!=null && memberId.equals(current.getMemberId())
                && Long.valueOf(ownerType).equals(current.getOwnerType()) && ownerId.equals(current.getOwnerId())
                && Integer.valueOf(2).equals(current.getRegistrationStatus());
    }
}
