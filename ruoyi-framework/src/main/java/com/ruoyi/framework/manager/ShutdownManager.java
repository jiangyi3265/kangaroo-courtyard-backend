package com.ruoyi.framework.manager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.concurrent.ScheduledExecutorService;
import com.ruoyi.common.utils.Threads;

/**
 * 确保应用退出时能关闭后台线程
 *
 * @author ruoyi
 */
@Component
public class ShutdownManager
{
    private static final Logger logger = LoggerFactory.getLogger("sys-user");

    @Resource(name = "scheduledExecutorService")
    private ScheduledExecutorService executor;

    @PreDestroy
    public void destroy()
    {
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
            // 销毁时不再首次初始化 AsyncManager：此时 Spring 已可能禁止通过静态工具获取 bean。
            // 显式依赖也让 Spring 先执行本类的关闭逻辑，再销毁线程池。
            Threads.shutdownAndAwaitTermination(executor);
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
        }
    }
}
