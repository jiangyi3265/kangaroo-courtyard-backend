package com.ruoyi.kangaroo.ext.pay;

import java.util.Map;

/**
 * 业务方接收支付结果的回调。支付单确认到账 / 退款成功时，在同一个事务里调用对应业务的实现。
 * bizType 与支付单的 biz_type 一一对应（stay_order、activity_signup …）。
 */
public interface KgPayListener
{
    String bizType();

    /** 支付成功：pay 是支付单这一行（驼峰键） */
    void onPaid(Map<String, Object> pay);

    /** Validate actor/business before requesting payment; callbacks do not require a live staff grant. */
    default void beforePay(Map<String,Object> pay,long userId) { }

    /** Validate the business state before any refund is queued or money is returned. */
    default void beforeRefund(Map<String, Object> pay, long amount) { }

    /** Durable channel refund number, bound inside the approval transaction. */
    default void onRefundRequested(Map<String,Object> pay,String refundNo,long amount) { }

    /** 退款成功（amount 为本次退款金额，分） */
    default void onRefunded(Map<String, Object> pay, long amount)
    {
    }

    /**
     * 微信把一笔退款关闭了（钱没有退出，amount 为这笔退款金额，分）。业务方只需把自己记着「等微信确认」的那笔退款
     * 改成没退成，别让它一直挡着下一笔退款；不要自动重新退款，由门店 / 平台重新发起。
     */
    default void onRefundClosed(Map<String, Object> pay, long amount)
    {
    }
}
