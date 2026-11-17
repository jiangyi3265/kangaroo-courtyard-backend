package com.ruoyi.kangaroo.ext.core;

/**
 * 举报成立后，对被举报内容做处置（隐藏帖子 / 评论 / Vlog / 评价…）。
 * 各模块按自己的 targetType 实现；没有实现的类型只记处理结果、不做处置。
 */
public interface KgReportHandler
{
    String targetType();

    /** 举报成立：把对象处置掉（通常是隐藏），返回一句处置说明 */
    String uphold(long targetId, String operator);
}
