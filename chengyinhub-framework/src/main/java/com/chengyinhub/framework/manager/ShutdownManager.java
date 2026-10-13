package com.chengyinhub.framework.manager;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import jakarta.annotation.PreDestroy;

/**
 * 确保应用退出时能关闭后台线程
 *
 * @author ruoyi
 */
@Component
public class ShutdownManager
{
    private static final Logger logger = LoggerFactory.getLogger("sys-user");

    private final ScheduledAnnotationBeanPostProcessor scheduledTasks;

    public ShutdownManager(Optional<ScheduledAnnotationBeanPostProcessor> scheduledTasks)
    {
        this.scheduledTasks = scheduledTasks.orElse(null);
    }

    @PreDestroy
    public void destroy()
    {
        // 先取消未来的定时任务，让同池已提交的异步任务正常收尾。
        if (scheduledTasks != null)
        {
            scheduledTasks.destroy();
        }
        shutdownAsyncManager();
    }

    /**
     * 停止异步执行任务
     */
    private void shutdownAsyncManager()
    {
        try
        {
            logger.info("====关闭后台任务任务线程池====");
            AsyncManager.me().shutdown();
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
        }
    }
}
