package com.ruoyi.kangaroo.framework;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.ruoyi.framework.manager.ShutdownManager;

class ShutdownManagerLifecycleTest
{
    private AnnotationConfigApplicationContext context(ScheduledExecutorService executor)
    {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // Disable inferred executor destruction: only ShutdownManager can close it in this regression.
        // There is deliberately no SpringUtils bean, nor any call to AsyncManager.me().
        context.registerBeanDefinition("scheduledExecutorService", BeanDefinitionBuilder
                .genericBeanDefinition(ScheduledExecutorService.class, () -> executor)
                .setDestroyMethodName("").getBeanDefinition());
        context.register(ShutdownManager.class);
        context.refresh();
        return context;
    }

    @Test
    void closingAnUnusedApplicationClosesTheInjectedExecutorWithoutStaticBeanLookup()
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try
        {
            AnnotationConfigApplicationContext context = context(executor);
            context.close();
            assertTrue(executor.isShutdown());
            assertTrue(executor.isTerminated());
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    void closingWaitsForAnAlreadyScheduledTaskInsteadOfAbandoningIt()
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AtomicBoolean finished = new AtomicBoolean(false);
        try
        {
            AnnotationConfigApplicationContext context = context(executor);
            executor.schedule(() -> finished.set(true), 20, TimeUnit.MILLISECONDS);
            context.close();
            assertTrue(finished.get(), "accepted background work must finish during graceful shutdown");
            assertTrue(executor.isTerminated());
        }
        finally
        {
            executor.shutdownNow();
        }
    }
}
