package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.support.CancellationReceiptStore;
import com.chengyinhub.business.service.support.CancellationReceiptStore.Request;
import com.chengyinhub.business.service.support.CancellationReceiptStore.Resource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.service.IClubTopicSettingService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.domain.vo.ClubEventCancellationResult;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ClubTopicSettingServiceImpl implements IClubTopicSettingService {
    /** 节点审核态 1=通过,只有通过的才算「这一章有几家」。 */
    private static final int NODE_APPROVED = 1;

    @Autowired private IClubAccessService clubAccessService;
    @Autowired private ICmsTopicService cmsTopicService;
    @Autowired private ICmsTopicChapterService cmsTopicChapterService;
    @Autowired private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired private CmsTopicMapper cmsTopicMapper;
    @Autowired private ICmsActivityService cmsActivityService;
    @Autowired private IClubEventOpsService clubEventOpsService;
    @Autowired private ITeamGroupService teamGroupService;
    @Autowired private CmsActivityMapper cancellationActivityMapper;
    @Autowired private PlatformTransactionManager cancellationTransactions;

    @Override
    public Map<String, Object> detail(Long principalMemberId, Long clubId, Long topicId) {
        CmsTopic topic = requireTopic(principalMemberId, clubId, topicId, ClubPermission.ACTIVITY_READ);
        ClubAccessContext access = clubAccessService.resolve(principalMemberId, clubId, null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topicId", topicId);
        out.put("topicName", topic.getName());
        out.put("lifecycleText", lifecycleText(topic.getLifecycle()));
        // 加列前不存在这个闸 ⇒ 存量行读到 null 时按「开着」解释,与迁移的默认值同口径
        out.put("coopOpen", !Integer.valueOf(0).equals(topic.getClubCoopOpen()));
        out.put("pinned", Integer.valueOf(1).equals(topic.getClubPinned()));
        out.put("memberOnly", Integer.valueOf(1).equals(topic.getClubMemberOnly()));
        out.put("canManage", access != null && access.hasPermission(ClubPermission.ACTIVITY_MANAGE));
        // 上架态给前端:「结束主题」那一行要拿它做 CAS(update_user_status 的 expectedUserStatus),
        // 已经下架时也不该再给一个点了没反应的入口。0=未上架 1=已上架,与 CmsTopic 同口径。
        out.put("userStatus", Integer.valueOf(1).equals(topic.getUserStatus()) ? 1 : 0);
        Long epoch=cmsTopicMapper.selectCancellationEpoch(topicId);
        if(epoch==null || epoch<0) throw new ServiceException("原主题取消轮次无法确认",409);
        out.put("cancellationEpoch",epoch);
        out.put("chapters", chapters(topicId));
        return out;
    }

    /**
     * 结束主题 = 先停售,再逐场取消并全额退款。稿 J5 164:118 的正文承诺的就是这个。
     *
     * ⚠️ 整个方法**故意不加 @Transactional**。逐场取消每一场自己是一个事务
     * (cancelOccurrence / cancelActivityByClubOperator 各自标了),一场失败不该把
     * 前面已经退掉的那几场回滚 —— 钱已经原路退出去了,回滚数据库只会让账和事实对不上。
     * 所以这里收集逐场结果并原样报给调用方,失败的那几场写清是哪一场、为什么。
     *
     * 顺序也不能反:先下架再退款。反过来会在退款过程中又卖出新票。
     */
    @Override
    public Map<String, Object> endTopic(Long principalMemberId, String username,
                                        Long clubId, Long topicId, String reason) {
        return endTopic(principalMemberId,username,clubId,topicId,reason,null);
    }

    @Override
    public Map<String,Object> endTopic(Long principalMemberId,String username,Long clubId,Long topicId,
                                      String reason,Long cancellationEpoch) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("结束主题的逐场观察不得悬挂原持锁事务");
        // 稿 J5 164:118 是纯确认框,没有原因输入框 —— 所以原因是选填的。
        // 空的时候给一句陈述事实的系统文案,不替主理人编一个理由塞给用户看。
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) why = "主办方已结束该主题，为你全额退款";

        final String originalReason=why;
        EndPreparation prepared=endTransaction(() -> prepareEnd(principalMemberId,clubId,topicId,originalReason,cancellationEpoch));
        if(prepared.receipt().done()) return prepared.receipt().topicEnd(topicId);
        int cancelled=0,refundedOrders=0,manualOrders=0,closedOrders=0;
        List<String> failed=new ArrayList<>();
        for(Long id:prepared.receipt().activities()) {
            String caption="场次 #"+id;
            try {
                // Fresh permission/scope before each independent child. Do not switch identity after UNKNOWN.
                EndPreparation current=endTransaction(() -> prepareEnd(principalMemberId,clubId,topicId,originalReason,prepared.request().epoch()));
                if(current.receipt().done()) return current.receipt().topicEnd(topicId);
                CmsActivity row=cmsActivityService.selectCmsActivityById(id);
                if(row==null || !topicId.equals(row.getTopicId())
                        || (row.getClubId()!=null && !clubId.equals(row.getClubId())))
                    throw new ServiceException("原结束主题场次范围已变化",HttpStatus.CONFLICT);
                if(row.getName()!=null) caption=row.getName();
                ActivityCancelResult result;
                if(row.getClubId()!=null) {
                    ClubEventCancellationRequest req=new ClubEventCancellationRequest();
                    req.setClubId(clubId);req.setActivityId(id);req.setReason(originalReason);
                    req.setRequestId(topicEndRequestId(topicId,id));
                    ClubEventCancellationResult out=clubEventOpsService.cancelOccurrence(principalMemberId,username,req);
                    if(out==null || out.getRefundedOrders()==null || out.getManualOrders()==null
                            || out.getClosedPendingOrders()==null) throw new ServiceException("原场次取消回执不完整",409);
                    result=new ActivityCancelResult(out.getRefundedOrders(),out.getManualOrders(),out.getClosedPendingOrders());
                } else {
                    result=teamGroupService.cancelActivityByClubOperator(id,current.request().owner(),principalMemberId,originalReason);
                }
                if(result==null) throw new ServiceException("退款回执为空，无法确认是否退成");
                if(result.getRefundedOrders()<0 || result.getManualOrders()<0 || result.getClosedPendingOrders()<0)
                    throw new ServiceException("原场次取消计数不完整",409);
                int nextRefunded=Math.addExact(refundedOrders,result.getRefundedOrders());
                int nextManual=Math.addExact(manualOrders,result.getManualOrders());
                int nextClosed=Math.addExact(closedOrders,result.getClosedPendingOrders());
                refundedOrders=nextRefunded;manualOrders=nextManual;closedOrders=nextClosed;cancelled++;
            } catch(RuntimeException uncertain) {
                failed.add(caption+"："+(uncertain instanceof ServiceException && uncertain.getMessage()!=null
                        ?uncertain.getMessage():"取消尚未确认，请保留原请求重试或人工核对"));
            }
        }
        if(failed.isEmpty()) {
            ActivityCancelResult totals=new ActivityCancelResult(refundedOrders,manualOrders,closedOrders);
            return endTransaction(() -> {
                EndPreparation current=prepareEnd(principalMemberId,clubId,topicId,originalReason,prepared.request().epoch());
                if(current.receipt().done()) return current.receipt().topicEnd(topicId);
                // Counts came only from exact durable child receipts, never current table reconstruction.
                return receipts().completeTopicEnd(current.request(),totals).topicEnd(topicId);
            });
        }
        Map<String,Object> pending=new LinkedHashMap<>();
        pending.put("topicId",topicId);pending.put("stoppedSelling",prepared.receipt().stoppedSelling());
        pending.put("cancellationEpoch",prepared.request().epoch());
        pending.put("sessionTotal",prepared.receipt().activities().size());pending.put("cancelledSessions",cancelled);
        pending.put("refundedOrders",refundedOrders);pending.put("manualOrders",manualOrders);
        pending.put("closedPendingOrders",closedOrders);pending.put("failedSessions",failed);
        pending.put("cancellationState","PENDING");return pending;
    }

    private record EndPreparation(Request request,CancellationReceiptStore.Snapshot receipt) { }
    private String topicEndRequestId(Long topic,Long activity) {
        String original="topic-end-"+topic+"-"+activity;
        return original.length()<=40?original:"te-"+Long.toString(topic,36)+"-"+Long.toString(activity,36);
    }
    private <T> T endTransaction(java.util.function.Supplier<T> work) {
        TransactionTemplate tx=new TransactionTemplate(cancellationTransactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> work.get());
    }
    private CancellationReceiptStore receipts() {return new CancellationReceiptStore(cancellationActivityMapper,cmsTopicMapper);}

    private EndPreparation prepareEnd(Long actor,Long clubId,Long topicId,String reason,Long suppliedEpoch) {
        requireTopic(actor,clubId,topicId,ClubPermission.ACTIVITY_MANAGE);
        CmsTopic topic=cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if(topic==null || !clubId.equals(topic.getClubId()) || Integer.valueOf(2).equals(topic.getDelFlag()))
            throw new ServiceException("原主题范围已变化",403);
        // Recheck current permission after the shared topic lock. No external I/O under this transaction.
        requireTopic(actor,clubId,topicId,ClubPermission.ACTIVITY_MANAGE);
        ClubAccessContext access=clubAccessService.resolve(actor,clubId,null);
        if(access==null || access.getOwnerMemberId()==null) throw new ServiceException("俱乐部归属缺失",403);
        Request request=new Request(Resource.TOPIC,topicId,actor,access.getOwnerMemberId(),topic.getMemberId(),
                topicId,clubId,"CLUB_TOPIC_END","club-topic-end-"+topicId,reason,receipts().requestEpoch(topicId,suppliedEpoch));
        long currentEpoch=receipts().currentTopicEpoch(topicId);
        String existing=cmsTopicMapper.selectCancellationReceiptForUpdate(topicId,request.epoch());
        if(existing!=null) {
            var receipt=receipts().current(request);
            if(request.epoch()<currentEpoch) {
                if(!receipt.done()) throw new ServiceException("原轮次取消尚未确认",409);
                return new EndPreparation(request,receipt);
            }
            if(Integer.valueOf(1).equals(topic.getUserStatus()))
                throw new ServiceException("原结束主题生命周期已变化，请人工核对",409);
            CmsActivity scope=new CmsActivity();scope.setTopicId(topicId);scope.setExcludeCancelled(1);scope.setDelFlag(0);
            List<CmsActivity> current=cmsActivityService.selectCmsActivityList(scope);
            if(current!=null) for(CmsActivity row:current) {
                if(row==null || row.getId()==null)
                    throw new ServiceException("原结束主题场次范围不完整",409);
                if(!topicId.equals(row.getTopicId())) continue;
                if((row.getClubId()==null || clubId.equals(row.getClubId())) && !receipt.activities().contains(row.getId()))
                    throw new ServiceException("原结束主题批次已变化，请人工核对",409);
            }
            return new EndPreparation(request,receipt);
        }
        if(request.epoch()!=currentEpoch) throw new ServiceException("原轮次缺少可核验取消结果",409);

        // ② 捞这个主题名下、属于本俱乐部、还没取消也没删的场次
        CmsActivity probe = new CmsActivity();
        probe.setTopicId(topicId);
        probe.setClubId(clubId);
        probe.setExcludeCancelled(1);
        probe.setDelFlag(0);
        List<CmsActivity> rows = cmsActivityService.selectCmsActivityList(probe);
        rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
        Set<Long> includedIds = new HashSet<>();
        for (CmsActivity row : rows) {
            if (row != null && row.getId() != null) includedIds.add(row.getId());
        }
        // 自由探索默认场次可能只带 topic_id；补入取消清单后仍由既有活动归属闸核验。
        probe.setClubId(null);
        List<CmsActivity> topicSessions = cmsActivityService.selectCmsActivityList(probe);
        if (topicSessions != null) {
            for (CmsActivity row : topicSessions) {
                if (row != null && row.getId() != null && row.getClubId() == null
                        && topicId.equals(row.getTopicId()) && includedIds.add(row.getId())) {
                    rows.add(row);
                }
            }
        }

        // S14-4/5-07:兜底取消传下去的 owner 必须是【俱乐部当前主理人】,不能再传主题作者。
        // 转让主理人后,主题作者既不是活动创建人(cms_activity.member_id 写死)也不是当前主理人,
        // 传他必被归属校验拒掉 ⇒ 这一场报「无权」且不退款。与 event-ops 那条路径同口径。
        List<Long> ids=new ArrayList<>();
        for(CmsActivity row:rows) {
            if(row==null || row.getId()==null || !topicId.equals(row.getTopicId())
                    || (row.getClubId()!=null && !clubId.equals(row.getClubId())))
                throw new ServiceException("原结束主题场次范围不完整",409);
            ids.add(row.getId());
        }
        ids.sort(Long::compareTo);
        var receipt=receipts().beginTopicEnd(request,ids,Integer.valueOf(1).equals(topic.getUserStatus()));
        if(Integer.valueOf(1).equals(topic.getUserStatus()) && cmsTopicService.casUserStatus(topicId,1,0)!=1)
            throw new ServiceException("主题状态刚刚变过，请刷新后重试",409);
        return new EndPreparation(request,receipt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Long principalMemberId, Long clubId, Long topicId,
                                    Boolean coopOpen, Boolean pinned, Boolean memberOnly) {
        requireTopic(principalMemberId, clubId, topicId, ClubPermission.ACTIVITY_MANAGE);
        if (coopOpen == null || pinned == null || memberOnly == null) {
            // 三个开关一起提交。少传一个就当没传,而不是把它当 false 关掉。
            throw new ServiceException("缺少开关值");
        }
        int changed = cmsTopicMapper.updateClubDisplaySettings(topicId, clubId,
                coopOpen ? 1 : 0, pinned ? 1 : 0, memberOnly ? 1 : 0);
        if (changed != 1) throw new ServiceException("保存失败，请重试");
        return detail(principalMemberId, clubId, topicId);
    }

    private CmsTopic requireTopic(Long principalMemberId, Long clubId, Long topicId, String permission) {
        if (principalMemberId == null) throw new ServiceException("请先登录");
        if (clubId == null || topicId == null) throw new ServiceException("缺少俱乐部或主题参数");
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || topic.getClubId() == null || !topic.getClubId().equals(clubId)) {
            throw new ServiceException("该主题不属于本俱乐部", HttpStatus.FORBIDDEN);
        }
        ClubAccessContext access = clubAccessService.resolve(principalMemberId, clubId, null);
        if (access == null || !access.hasPermission(permission)) {
            throw new ServiceException("当前岗位没有这个权限", HttpStatus.FORBIDDEN);
        }
        return topic;
    }

    private List<Map<String, Object>> chapters(Long topicId) {
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topicId);
        List<CmsTopicChapter> chapters = cmsTopicChapterService.selectCmsTopicChapterList(probe);
        if (chapters == null || chapters.isEmpty()) return Collections.emptyList();
        chapters.sort(Comparator.comparing(c -> c.getSortId() == null ? Long.MAX_VALUE : c.getSortId()));

        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(topicId);
        List<CmsTopicNode> nodes = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe);
        Map<Long, Integer> approvedByChapter = new HashMap<>();
        if (nodes != null) {
            for (CmsTopicNode node : nodes) {
                if (node.getChapterId() == null || node.getMerchantMemberId() == null) continue;
                if (node.getNodeAuditStatus() == null || node.getNodeAuditStatus() != NODE_APPROVED) continue;
                Integer prev = approvedByChapter.get(node.getChapterId());
                approvedByChapter.put(node.getChapterId(), prev == null ? 1 : prev + 1);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (CmsTopicChapter chapter : chapters) {
            Integer count = approvedByChapter.get(chapter.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", chapter.getId());
            row.put("name", chapter.getName());
            row.put("category", chapter.getCategory());
            row.put("recruiting", chapter.getRecruitEnabled() != null && chapter.getRecruitEnabled() == 1);
            row.put("merchantCount", count == null ? 0 : count);
            out.add(row);
        }
        return out;
    }

    /** 生命周期 0草稿 1招募 2定价 3售票 4进行 5结算 6关闭 7立项待审。未知值不猜,回 null。 */
    private static String lifecycleText(Integer lifecycle) {
        if (lifecycle == null) return null;
        switch (lifecycle) {
            case 0: return "草稿";
            case 1: return "招募中";
            case 2: return "定价中";
            case 3: return "已开卖";
            case 4: return "进行中";
            case 5: return "结算中";
            case 6: return "已关闭";
            case 7: return "立项待审";
            default: return null;
        }
    }
}
