package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.ServiceStatusVO;
import com.chengyinhub.business.service.IServiceStatusFeedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 故障页只读本机静态快照，不访问 MySQL、Redis、下游 HTTP 或登录态。
 * 文件缺失/损坏必须 UNKNOWN，禁止把读失败伪装成全绿。
 */
@Service
public class ServiceStatusFeedServiceImpl implements IServiceStatusFeedService {
    private static final Logger log = LoggerFactory.getLogger(ServiceStatusFeedServiceImpl.class);
    private static final Set<String> VALID_STATUSES = new HashSet<>(Arrays.asList(
            "OPERATIONAL", "DEGRADED", "PARTIAL_OUTAGE", "MAJOR_OUTAGE", "MAINTENANCE", "UNKNOWN"));
    @Value("${chengyin.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    private final ObjectMapper objectMapper;
    private final String feedFile;
    private final long maxAgeSeconds;

    @Autowired
    public ServiceStatusFeedServiceImpl(ObjectMapper objectMapper,
                                        @Value("${chengyin.status.feed-file:/www/wwwroot/status/chengyin-status.json}") String feedFile,
                                        @Value("${chengyin.status.max-age-seconds:90}") long maxAgeSeconds) {
        this.objectMapper = objectMapper;
        this.feedFile = feedFile;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public ServiceStatusFeedServiceImpl(ObjectMapper objectMapper, String feedFile) {
        this(objectMapper, feedFile, 90L);
    }

    @Override
    public ServiceStatusVO read() {
        try {
            File file = new File(feedFile);
            if (file.isFile() && file.length() > 0 && file.length() <= 512 * 1024) {
                ServiceStatusVO feed = objectMapper.readValue(file, ServiceStatusVO.class);
                if (valid(feed) && fresh(feed)) {
                    feed.setSource("LOCAL_STATIC_SNAPSHOT");
                    return feed;
                }
            }
        } catch (Exception ignored) {
            // 故障页不能因快照损坏再次抛错；下面返回明确 UNKNOWN，而不是假绿。
        }
        return unknownFallback();
    }

    private boolean valid(ServiceStatusVO feed) {
        if (feed == null || !VALID_STATUSES.contains(feed.getOverallStatus()) || feed.getUpdatedAt() == null
                || feed.getComponents() == null || feed.getIncidents() == null) return false;
        for (ServiceStatusVO.Component component : feed.getComponents()) {
            if (component == null || component.getCode() == null || component.getName() == null
                    || !VALID_STATUSES.contains(component.getStatus()) || component.getUpdatedAt() == null) return false;
        }
        return true;
    }

    private boolean fresh(ServiceStatusVO feed) {
        try {
            Instant updatedAt = Instant.parse(feed.getUpdatedAt());
            Instant now = Instant.now();
            return !updatedAt.isBefore(now.minusSeconds(maxAgeSeconds))
                    && !updatedAt.isAfter(now.plusSeconds(30));
        } catch (Exception ignored) {
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${chengyin.status.publish-ms:30000}",
            initialDelayString = "${chengyin.status.publish-initial-delay-ms:1000}")
    public void publishCoreApiHeartbeat() {
        if (!jobsEnabled) return;
        try {
            String now = Instant.now().toString();
            ServiceStatusVO feed = new ServiceStatusVO();
            feed.setOverallStatus("OPERATIONAL");
            feed.setUpdatedAt(now);
            feed.setSource("SELF_HEARTBEAT");
            ServiceStatusVO.Component api = new ServiceStatusVO.Component();
            api.setCode("CORE_API");
            api.setName("城瘾核心服务");
            api.setStatus("OPERATIONAL");
            api.setUpdatedAt(now);
            feed.setComponents(Collections.singletonList(api));
            feed.setIncidents(Collections.emptyList());

            Path target = Paths.get(feedFile);
            Path parent = target.toAbsolutePath().getParent();
            if (parent == null) throw new IllegalStateException("状态快照路径缺少父目录");
            Files.createDirectories(parent);
            Path temporary = parent.resolve("." + target.getFileName() + ".tmp");
            objectMapper.writeValue(temporary.toFile(), feed);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("写入服务状态快照失败 path={}", feedFile, e);
        }
    }

    private ServiceStatusVO unknownFallback() {
        ServiceStatusVO feed = new ServiceStatusVO();
        feed.setOverallStatus("UNKNOWN");
        feed.setUpdatedAt(Instant.now().toString());
        feed.setSource("EMBEDDED_FALLBACK");
        ServiceStatusVO.Component api = new ServiceStatusVO.Component();
        api.setCode("CORE_API");
        api.setName("城瘾核心服务");
        api.setStatus("UNKNOWN");
        api.setUpdatedAt(feed.getUpdatedAt());
        feed.setComponents(Collections.singletonList(api));
        feed.setIncidents(Collections.emptyList());
        return feed;
    }
}
