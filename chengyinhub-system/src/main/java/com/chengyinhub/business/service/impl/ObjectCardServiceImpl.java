package com.chengyinhub.business.service.impl;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.PlayerObjectCard;
import com.chengyinhub.business.mapper.PlayerObjectCardMapper;
import com.chengyinhub.business.service.ObjectCardService;
import com.chengyinhub.business.service.ai.CardCutout;
import com.chengyinhub.business.service.ai.CardCutoutStorage;
import com.chengyinhub.business.service.ai.ImageSegClient;
import com.chengyinhub.business.service.ai.ObjectNamer;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * 铸卡:来源闸 → 抠图与认物并行 → 一行 upsert。铸出来的一律是照片卡(gen_status=NONE、0 帧)。
 *
 * <p><b>不带 {@code @Transactional}</b>:一次铸卡只有一条写库语句,事务等于零收益。
 * 调用方(玩法运行时)在事务提交之后才来这里,判定与铸卡因此天然分开:
 * 判定回滚了就不会有卡,铸卡炸了也不会把已过改成未过。
 *
 * <p>3D 转台由后台任务补(转台藏品卡施工文档 §4.3),不在这一步排队。这一步只外加两件**加分项**
 * (抠图贴纸、认物卡名),它们任何一件没结果都只是卡面朴素一点,不能把一张本来能出的卡带走。
 */
@Service
public class ObjectCardServiceImpl implements ObjectCardService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.ConsentGuard consentGuard;
    @Autowired private com.chengyinhub.business.service.support.AppMediaReferenceGuard mediaReferences;


    private static final Logger log = LoggerFactory.getLogger(ObjectCardServiceImpl.class);

    /** 与 photoCheck 对 imageUrl 的上限、库里 source_url 列宽同一条口径。 */
    static final int URL_MAX = 512;
    /** 与 AdvancedGameConfigValidator.MAX_CARD_TITLE、库里 title 列宽同一条口径(64)。 */
    static final int TITLE_MAX = 64;
    static final int CAPTION_MAX = 128;
    static final String DEFAULT_TITLE = "藏品卡";
    static final String STYLE_FOIL = "foil";
    static final String STYLE_PLAIN = "plain";
    static final String GEN_NONE = "NONE";

    /**
     * 两件加分项各自的硬超时(施工文档 §2.5)。写死不做配置:配大了就是把玩家按在快门后面
     * 等更久,而要关它们有各自的开关(ai.imageseg.enabled / object-card.naming.enabled)。
     */
    private long taskBudgetMs = 4000L;

    @Autowired
    private PlayerObjectCardMapper cardMapper;

    /** 商品分割抠图与结果转存;没装配(或开关关着)= 没有贴纸,不影响出卡。 */
    @Autowired(required = false)
    private ImageSegClient imageSegClient;

    @Autowired(required = false)
    private CardCutoutStorage cutoutStorage;

    /** AI 认物;没装配或开关关着 = 卡名沿用商家模板。 */
    @Autowired(required = false)
    private ObjectNamer objectNamer;
    @Autowired
    private com.chengyinhub.business.service.IWxContentSecurityService wxContentSecurityService;
    @Autowired
    private com.chengyinhub.business.service.IUmsMemberService umsMemberService;

    /**
     * 这两件专用的有界小池:不复用 {@code ForkJoinPool.commonPool()},也不用 RuoYi 全局的
     * {@code threadPoolTaskExecutor} —— 那是别人家的池,卡住的四秒会把不相干的任务一起拖住。
     * 队列满就放弃这一次加分项,不排队:排队只会让玩家在快门后面多等一会儿。
     */
    private ThreadPoolExecutor mintTasks = newMintTasks();

    /** 与玩法运行时同一口径:默认取本机时间,单测覆写这一缝。不是 Spring bean,别去容器里找。 */
    private AdvancedGameClock clock = Date::new;

    @Override
    public PlayerObjectCard mint(Long memberId, String sourceUrl, JsonNode photoCheck,
            String originType, String originId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        String url = StringUtils.trimToEmpty(sourceUrl);
        if (url.isEmpty()) {
            throw new IllegalArgumentException("没有照片,铸不出卡");
        }
        if (url.length() > URL_MAX) {
            throw new IllegalArgumentException("照片地址过长");
        }
        // 来源闸:这张图之后要被送去外部服务(抠图、3D 生成),别人递进来的地址不能接。
        if (!url.matches("media-asset:[1-9][0-9]{0,18}") && !isAllowedOrigin(url)) {
            throw new IllegalArgumentException("这张照片不是本站上传的");
        }
        if (!ORIGIN_PLAY.equals(originType)) {
            throw new IllegalArgumentException("藏品卡来源还不认识:" + originType);
        }
        if (StringUtils.isBlank(originId)) {
            throw new IllegalArgumentException("藏品卡缺少来源");
        }

        consentGuard.requireCurrentAgreement(memberId, "photo_model_processing", "object_photo");
        String sourceReference = mediaReferences.requireAttachment(memberId, url, true);
        String processingUrl = mediaReferences.processingUrl(sourceReference, memberId, "IMAGE");

        JsonNode cfg = photoCheck == null ? NullNode.getInstance() : photoCheck;
        com.chengyinhub.business.domain.UmsMember actor = umsMemberService.selectUmsMemberById(memberId);
        String openid = actor == null ? null : actor.getWxOpenId();
        // Recheck legacy published templates too: a failed model check must never fall back to unchecked text.
        String templateRisk = wxContentSecurityService.checkText(
                com.chengyinhub.business.service.IWxContentSecurityService.joinText(
                        cardTitle(cfg, null), cfg.path("shotNote").asText("")), openid);
        if (templateRisk != null) throw new com.chengyinhub.common.exception.ServiceException(templateRisk);
        Date now = clock.now();

        // 两件并行开工,再对着**同一个截止点**收:先等的那件耗掉的时间,后那件已经在并行里跑掉了,
        // 所以总等待是一个预算而不是两个(施工文档 §2.5)。
        Future<CardCutout> cutout = canCutout() ? submit("抠图", () -> cutoutOf(processingUrl, memberId)) : null;
        Future<ObjectNamer.Naming> naming = objectNamer == null ? null : submit("认物", () -> {
            ObjectNamer.Naming result = objectNamer.name(processingUrl);
            if (result == null) return null;
            String risk = wxContentSecurityService.checkText(result.getName(), openid);
            return risk == null ? result : null;
        });
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(taskBudgetMs);
        CardCutout cutoutResult = await(cutout, deadline, "抠图");
        ObjectNamer.Naming named = await(naming, deadline, "认物");

        PlayerObjectCard card = new PlayerObjectCard();
        card.setMemberId(memberId);
        card.setSourceUrl(sourceReference);
        card.setTitle(cardTitle(cfg, named));
        card.setCaption(StringUtils.cutKeepingSurrogates(cfg.path("shotNote").asText(""), CAPTION_MAX));
        card.setCardStyle(cardStyle(cfg));
        card.setCategory(named == null ? null : named.getCategory());
        card.setCutoutUrl(cutoutResult == null ? null : cutoutResult.getUrl());
        card.setCutoutBox(cutoutResult == null ? null : cutoutResult.getBox());
        card.setOriginType(originType);
        card.setOriginId(originId.trim());
        card.setCreateTime(now);
        card.setUpdateTime(now);
        card.setGenStatus(GEN_NONE);
        card.setFrameCount(0);

        cardMapper.upsert(card);
        return card;
    }

    /**
     * 抠图 = 照片整形成接口吃得下的样子 → 让供应商裁一刀 → 把临时结果转进我们的桶。
     * 三步都在这一次里,任何一步不成就是没有贴纸。
     */
    private CardCutout cutoutOf(String url, long memberId) {
        String input = cutoutStorage.segmentInputOf(url, memberId);
        if (StringUtils.isBlank(input)) {
            return null;
        }
        String temp = imageSegClient.segment(input);
        return StringUtils.isBlank(temp) ? null : cutoutStorage.transfer(temp, memberId);
    }

    /** 排不进就不做:队列满(或池已关)等于上游正堵着,再排队就是把玩家一起拖住。 */
    private <T> Future<T> submit(String what, Callable<T> task) {
        try {
            return mintTasks.submit(task);
        } catch (RejectedExecutionException full) {
            log.warn("[藏品卡] {} 这次不做了:线程池排不下", what);
            return null;
        }
    }

    /**
     * 对着同一个截止点取结果:剩多少等多少,到点就当没有结果。
     *
     * <p>超时只是「我们不等了」:底层 HTTP 是连接、读取各有一个超时,加起来可能比预算长,所以必须
     * {@code cancel(true)} 把线程收回池里,不然队列位置被一直占着。被中断的 socket 读不一定真停,
     * 最坏是多占一会儿线程 —— 这正是池要小、队列要有界的原因。
     */
    private <T> T await(Future<T> task, long deadlineNanos, String what) {
        if (task == null) {
            return null;
        }
        try {
            return task.get(Math.max(deadlineNanos - System.nanoTime(), 0L), TimeUnit.NANOSECONDS);
        } catch (TimeoutException tooSlow) {
            task.cancel(true);
            log.warn("[藏品卡] {} 超过 {} 毫秒预算,这张卡不带它", what, taskBudgetMs);
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            log.warn("[藏品卡] {} 等待被打断", what);
            return null;
        } catch (ExecutionException | CancellationException failed) {
            Throwable cause = failed.getCause();
            log.error("[藏品卡] {} 失败, exceptionType={}", what,
                    cause == null ? failed.getClass().getSimpleName() : cause.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 线程带名字:这两件最坏会各占一个线程到超时为止,jstack 上必须一眼认得出是谁。
     * 核心线程也设超时回收 —— 只有判过的瞬间才用得上,平时不该留两个空转的线程。
     */
    private static ThreadPoolExecutor newMintTasks() {
        ThreadFactory named = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "object-card-mint-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        // 核心数 = 最大数:JDK 线程池只在队列满了才加线程,核心 2 就等于同时只跑一次铸卡(一次要两件),
        // 第二个同时判过的人排在队列里等到截止点、贴纸与 AI 名一起丢(2026-09-24 审查实证)。
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(20), named);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** 关应用时把可能还在打外网的线程收掉,不等 keepAlive。 */
    @PreDestroy
    void shutdownMintTasks() {
        ThreadPoolExecutor pool = mintTasks;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private boolean canCutout() {
        return imageSegClient != null && cutoutStorage != null;
    }

    /**
     * 地址来源判定。生产口径 = 本站上传链产出的地址(站内 /profile/upload 相对路径或本桶域名),
     * 读的是 OSS 配置;单测覆写这一缝,不依赖环境变量。
     */
    boolean isAllowedOrigin(String url) {
        return FileOssUploadUtils.isChengyinPhotoAddress(url);
    }

    /**
     * 卡名(施工文档 §2.4 的顺序):AI 认出来的名字 → 模板起的名字 → 玩法段标题 → 兜底名
     * —— 卡上总得有字,空白卡读起来像坏了。
     */
    private static String cardTitle(JsonNode cfg, ObjectNamer.Naming named) {
        if (named != null && StringUtils.isNotBlank(named.getName())) {
            // 认物那侧已截到 12 字,列宽 64,这里不会再撑破
            return named.getName().trim();
        }
        String title = cfg.path("cardTitle").asText("").trim();
        if (title.isEmpty()) {
            title = cfg.path("title").asText("").trim();
        }
        if (title.isEmpty()) {
            title = DEFAULT_TITLE;
        }
        return StringUtils.cutKeepingSurrogates(title, TITLE_MAX);
    }

    /** 样式只认发布侧那两种;脏值退回 foil,不能把未知串写进列里让前端猜(与校验器同口径)。 */
    private static String cardStyle(JsonNode cfg) {
        String style = cfg.path("cardStyle").asText("").trim();
        return STYLE_PLAIN.equals(style) ? STYLE_PLAIN : STYLE_FOIL;
    }


    @Override
    public List<PlayerObjectCard> list(Long memberId, String category, int offset, int limit) {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        return cardMapper.selectByMember(memberId, category, Math.max(offset, 0), limit);
    }

    @Override
    public int count(Long memberId, String category) {
        if (memberId == null || memberId <= 0) {
            return 0;
        }
        return cardMapper.countByMember(memberId, category);
    }

    @Override
    public PlayerObjectCard rename(Long memberId, Long cardId, String title) {
        if (memberId == null || memberId <= 0 || cardId == null || cardId <= 0) {
            return null;
        }
        com.chengyinhub.business.domain.UmsMember actor = umsMemberService.selectUmsMemberById(memberId);
        String risk = wxContentSecurityService.checkText(title, actor == null ? null : actor.getWxOpenId());
        if (risk != null) throw new com.chengyinhub.common.exception.ServiceException(risk);
        if (cardMapper.renameOwned(cardId, memberId, title) != 1) {
            return null;
        }
        return cardMapper.selectOwnedById(cardId, memberId);
    }
}
