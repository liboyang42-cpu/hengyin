package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ai.AiSafetyPrecheckReq;
import com.chengyinhub.business.domain.vo.ai.AiSafetyIssue;
import com.chengyinhub.business.domain.vo.ai.AiSafetyPrecheckResp;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.support.TopicPublishCheckService.PublishCheckIssue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * DOC2 O5c-(c):把既有 AI 安全预检({@link IAiCreatorService#safetyPrecheck})接进主题发布前试检查。
 *
 * <p>开关 {@code chengyin.audit.o5c.ai-precheck.enabled} 默认关:关时不调任何外部服务、返回空列表,
 * 试检查输出与旧版逐项一致。开时的口径:
 * <ul>
 *   <li><b>fail-open</b>:超时 / 额度用尽 / 上游不可用 / AI 返回无法解析 / 线程池满 → 一条 WARN
 *       {@code AI_PRECHECK_SKIPPED},从不因 provider 故障拦发布;</li>
 *   <li>文本内容安全(微信 msgSecCheck,正式发布闸本来就拦同一份文本)命中 → error
 *       {@code AI_PRECHECK_CONTENT_BLOCKED};</li>
 *   <li>AI 模型给出的风险意见 → WARN {@code AI_PRECHECK_RISK}:正式发布闸不执行 AI 意见,
 *       试检查不能把它报成「发布会被拦」。是否升级为 BLOCK 待 owner 拍板。</li>
 * </ul>
 *
 * <p>隐私:送 provider 的只有主题名称/副标题/简介与启用节点的名称/简介,不带地址、经纬度、
 * 手机号、会员信息;文本里形似手机号或经纬度小数的片段先打码再送。
 */
@Service
public class TopicAiPrecheckService {

    private static final Logger log = LoggerFactory.getLogger(TopicAiPrecheckService.class);

    public static final String CODE_AI_PRECHECK_SKIPPED = "AI_PRECHECK_SKIPPED";
    public static final String CODE_AI_PRECHECK_CONTENT_BLOCKED = "AI_PRECHECK_CONTENT_BLOCKED";
    public static final String CODE_AI_PRECHECK_RISK = "AI_PRECHECK_RISK";

    static final int MAX_NODES = 50;
    static final int MAX_TEXT = 500;
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 经纬度形态:带 4 位以上小数的数字(如 31.230416 / 121.4737);普通金额、时间不受影响。 */
    private static final Pattern COORDINATE = Pattern.compile("-?\\d{1,3}\\.\\d{4,}");

    private final IAiCreatorService aiCreatorService;

    @Value("${chengyin.audit.o5c.ai-precheck.enabled:false}")
    private boolean enabled = false;

    @Value("${chengyin.audit.o5c.ai-precheck.timeout-ms:8000}")
    private long timeoutMs = 8000L;

    /** 有界小池:试检查是同步 GET,provider 挂住时不能把请求线程或无界队列拖垮;池满即 WARN 跳过。 */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16), new DaemonFactory(), new ThreadPoolExecutor.AbortPolicy());

    public TopicAiPrecheckService(IAiCreatorService aiCreatorService) {
        this.aiCreatorService = aiCreatorService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 开关关、主题无归属会员时返回空列表;开时最多返回若干条 error/warn,从不抛异常。 */
    public List<PublishCheckIssue> check(CmsTopic topic, List<CmsTopicNode> nodes) {
        if (!enabled || topic == null || topic.getId() == null) {
            return Collections.emptyList();
        }
        Long memberId = topic.getMemberId();
        if (memberId == null || memberId <= 0L) {
            // 额度池按会员记;官方/无归属主题没有可计费主体,不替别人扣额度。
            return Collections.singletonList(skipped("主题无归属创作者,AI 预检未执行(不影响发布)"));
        }
        AiSafetyPrecheckReq req = buildRequest(topic, nodes);
        AiSafetyPrecheckResp resp;
        try {
            Future<AiSafetyPrecheckResp> future = executor.submit(() -> aiCreatorService.safetyPrecheck(req, memberId));
            resp = future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 不 cancel:网关内部的额度预占/恢复按自身路径收尾,中断反而制造「结果待确认」。
            log.warn("[O5c] AI 预检超时 topicId={} timeoutMs={}", topic.getId(), timeoutMs);
            return Collections.singletonList(skipped("AI 预检超时,已跳过(不影响发布)"));
        } catch (ExecutionException e) {
            return fromFailure(topic.getId(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.singletonList(skipped("AI 预检被中断,已跳过(不影响发布)"));
        } catch (RuntimeException e) {
            // RejectedExecutionException 等:池满或已关闭。
            log.warn("[O5c] AI 预检未能提交 topicId={}: {}", topic.getId(), e.toString());
            return Collections.singletonList(skipped("AI 预检繁忙,已跳过(不影响发布)"));
        }
        return fromResponse(resp);
    }

    List<PublishCheckIssue> fromFailure(Long topicId, Throwable cause) {
        if (cause instanceof AiServiceException
                && ((AiServiceException) cause).getReason() == AiServiceException.Reason.INPUT_BLOCKED) {
            // 入参被内容安全拒:与正式发布的文本内容安全闸同一判据,发布同样会被拦。
            return Collections.singletonList(PublishCheckIssue.error(CODE_AI_PRECHECK_CONTENT_BLOCKED, null,
                    "主题文字未通过内容安全检测,请修改后再发布"));
        }
        String reason = cause instanceof AiServiceException
                ? ((AiServiceException) cause).getReason().name() : String.valueOf(cause);
        log.warn("[O5c] AI 预检不可用,fail-open topicId={} reason={}", topicId, reason);
        return Collections.singletonList(skipped("AI 预检暂不可用,已跳过(不影响发布)"));
    }

    List<PublishCheckIssue> fromResponse(AiSafetyPrecheckResp resp) {
        List<PublishCheckIssue> out = new ArrayList<PublishCheckIssue>();
        if (resp == null) {
            out.add(skipped("AI 预检无结果,已跳过(不影响发布)"));
            return out;
        }
        boolean parseError = false; // 也承载「内容安全接口不可用」:两者都按跳过处理
        if (resp.getIssues() != null) {
            for (AiSafetyIssue issue : resp.getIssues()) {
                if (issue == null) continue;
                if ("content_security".equals(issue.getType()) && detectionUnavailable(issue.getMessage())) {
                    // 内容安全接口忙/不可用不是「命中」:同样 fail-open。
                    parseError = true;
                } else if ("content_security".equals(issue.getType())) {
                    out.add(PublishCheckIssue.error(CODE_AI_PRECHECK_CONTENT_BLOCKED, null,
                            "主题文字未通过内容安全检测:" + nullToEmpty(issue.getMessage())));
                } else if ("parse_error".equals(issue.getType())) {
                    parseError = true;
                } else {
                    out.add(PublishCheckIssue.warn(CODE_AI_PRECHECK_RISK, null,
                            "AI 预检提示:" + nullToEmpty(issue.getMessage())));
                }
            }
        }
        if (parseError) {
            out.add(skipped("AI 预检返回无法解析,已跳过(不影响发布)"));
        }
        return out;
    }

    AiSafetyPrecheckReq buildRequest(CmsTopic topic, List<CmsTopicNode> nodes) {
        AiSafetyPrecheckReq req = new AiSafetyPrecheckReq();
        req.setTitle(scrub(topic.getName()));
        req.setSubtitle(scrub(topic.getSubtitle()));
        req.setDescription(scrub(topic.getDescription()));
        List<AiSafetyPrecheckReq.PrecheckNode> list = new ArrayList<AiSafetyPrecheckReq.PrecheckNode>();
        if (nodes != null) {
            for (CmsTopicNode node : nodes) {
                if (node == null) continue;
                if (list.size() >= MAX_NODES) break;
                AiSafetyPrecheckReq.PrecheckNode n = new AiSafetyPrecheckReq.PrecheckNode();
                n.setName(scrub(node.getName()));
                n.setTask(scrub(node.getDescription()));
                list.add(n);
            }
        }
        req.setNodes(list);
        return req;
    }

    static String scrub(String text) {
        if (text == null) return null;
        String out = MOBILE.matcher(text).replaceAll("***");
        out = COORDINATE.matcher(out).replaceAll("***");
        return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out;
    }

    private static boolean detectionUnavailable(String message) {
        return IWxContentSecurityService.isDetectionUnavailable(message)
                || IWxContentSecurityService.MSG_BUSY.equals(message)
                || IWxContentSecurityService.MSG_UNAVAILABLE.equals(message);
    }

    private static PublishCheckIssue skipped(String message) {
        return PublishCheckIssue.warn(CODE_AI_PRECHECK_SKIPPED, null, message);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class DaemonFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "topic-ai-precheck-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
