package com.ruoyi.kangaroo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 袋鼠模块的定时任务（调度名额到期恢复等）。任务本身都带 CAS 条件，多实例同时跑也只会生效一次。
 */
@Configuration
@EnableScheduling
public class KangarooScheduleConfig
{
}
