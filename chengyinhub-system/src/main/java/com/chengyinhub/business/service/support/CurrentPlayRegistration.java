package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
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
        if(rows!=null) for(CmsRegistration snapshot:rows) {
            if(snapshot==null || snapshot.getId()==null || (requestedId!=null && !requestedId.equals(snapshot.getId()))) continue;
            CmsRegistration current=registrations.selectCmsRegistrationByIdForUpdate(snapshot.getId());
            if(!currentOwner(current,memberId,ownerType,ownerId)) continue;
            // 普通/免费报名沿用原status+owner资格；只有原本以支付状态为真源的链和原pk3订单要求已付。
            if((originallyRequiresPaid || Integer.valueOf(3).equals(current.getPurchaseKind()))
                    && !Integer.valueOf(2).equals(current.getPaymentStatus())) continue;
            if(ownerType==1L && current.getExpiresAt()!=null && !current.getExpiresAt().after(now)) continue;
            String deny=ExploreTicketUsePolicy.rejection(current.getPurchaseKind(),activity==null?null:activity.getEndDate(),now);
            if(deny!=null) throw new IllegalStateException(deny);
            return current;
        }
        throw new IllegalStateException(ownerType==1L ? "当前自玩资格已失效" : "当前报名资格已失效");
    }
    public static boolean currentOwner(CmsRegistration current,Long memberId,long ownerType,Long ownerId) {
        return current!=null && memberId!=null && memberId.equals(current.getMemberId())
                && Long.valueOf(ownerType).equals(current.getOwnerType()) && ownerId.equals(current.getOwnerId())
                && Integer.valueOf(2).equals(current.getRegistrationStatus());
    }
}
