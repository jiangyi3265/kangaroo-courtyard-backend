package com.ruoyi.kangaroo.service;

/**
 * 幂等键服务：保证同一次业务提交重复请求只产生一条业务记录。
 *
 * 与若依自带的 @RepeatSubmit 的区别：@RepeatSubmit 只按"同一用户 + 同一 URL + 同一请求体 + 10 秒内"
 * 做拦截，属于防手抖；这里是业务级幂等，键由调用方按业务维度给出，
 * 依赖数据库唯一索引，跨进程、跨重试都成立。
 */
public interface IKgIdempotencyService
{
    /**
     * 尝试占用幂等键。
     *
     * @return true 表示首次占用，可以继续执行业务；false 表示该键已被占用过
     */
    boolean tryAcquire(String idemKey, String bizType);

    /** 业务成功后回填产生的业务主键 */
    void bindBizId(String idemKey, Long bizId);

    /** 读取该幂等键此前产生的业务主键，用于重复请求时返回同一结果 */
    Long findBizId(String idemKey);

    /** 业务失败时释放，允许用同一个键重试 */
    /** 键已存在多少秒；键不存在时返回 null */
    Integer ageSeconds(String idemKey);

    void release(String idemKey);
}
