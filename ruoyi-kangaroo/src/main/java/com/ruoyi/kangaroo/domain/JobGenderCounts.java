package com.ruoyi.kangaroo.domain;

import java.math.BigDecimal;
import com.ruoyi.common.exception.ServiceException;

/** 招募计划按男女拆分；不改变报名占位、审核和录用规则。 */
public final class JobGenderCounts
{
    private JobGenderCounts() { }

    public static Integer count(Object value)
    {
        if (value == null) return null;
        try
        {
            int n = new BigDecimal(String.valueOf(value)).intValueExact();
            if (n >= 0 && n <= 99) return n;
        }
        catch (RuntimeException ignored) { }
        throw new ServiceException("男生、女生人数须为 0–99 的整数", 422);
    }

    public static void apply(KgJob job, KgJob existing)
    {
        Boolean enabled = job.getGenderCountsEnabled();
        Integer male = job.getGenderMaleCount(), female = job.getGenderFemaleCount();
        if (enabled == null && male == null && female == null && existing != null)
        {
            male = existing.getGenderMaleCount();
            female = existing.getGenderFemaleCount();
        }
        if (!"volunteer".equals(job.getJobType()) || Boolean.FALSE.equals(enabled))
        {
            male = null;
            female = null;
        }
        else if (Boolean.TRUE.equals(enabled) || male != null || female != null)
        {
            if (male == null || female == null)
                throw new ServiceException("请分别填写男生和女生招募人数，不招请填 0", 422);
            count(male);
            count(female);
            int total = male + female;
            if (total < 1 || total > 99)
                throw new ServiceException("男生和女生合计需为 1–99 人", 422);
            if (job.getHeadcount() == null || job.getHeadcount() != total)
                throw new ServiceException("男生、女生人数之和须等于总招募人数，请同时修改", 422);
            job.setGenderPref(preference(male, female));
        }
        job.setGenderMaleCount(male);
        job.setGenderFemaleCount(female);
        job.setGenderCountsEnabled(male != null && female != null);
    }

    public static String preference(Integer male, Integer female)
    {
        return male == 0 ? "F" : female == 0 ? "M" : "";
    }
}
