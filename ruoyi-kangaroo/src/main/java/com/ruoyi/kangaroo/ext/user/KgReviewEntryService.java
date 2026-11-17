package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 写评价的入口（2026-10-16 内测反馈 2.0）：评价功能早就有，但入口只在「我的」里，测试者找不到。
 * 岗位详情「评价」页签顶部的「我在这家店做过，写评价」按钮按这里给的报名编号显示、跳转。
 *
 * 口径和写评价接口（KgUserHubService.writeReview）一致：本人的、这家店的、已入职（onboarded / completed）的报名，
 * 线上任务不算（「我的订单」里线上任务也没有「去评价」），并且这条报名还没评价过。
 * 会员规则开着时再加一条：换工 / 兼职要门店确认完成，全职入职即可（和提交校验同一口径）。
 */
@Service
public class KgReviewEntryService
{
    @Autowired
    private KgJdbc db;
    @Autowired(required=false) private KgMembershipRewards memberRewards;

    /**
     * 当前用户在这家店可以写评价、还没写的那条报名（多条取最新的一条）；游客、没有门店、没有符合条件的报名都返回 null。
     * 小程序按 /pages/profile/review?bizType=application&bizId=&lt;返回值&gt; 跳转。
     */
    public Long myReviewableAppId(Long me, Long storeId)
    {
        if (me == null || storeId == null)
        {
            return null;
        }
        Map<String, Object> r = db.one("select a.app_id from kg_application a join kg_job j on j.job_id = a.job_id "
                + "where a.app_user_id = :u and coalesce(j.recruit_store_id,j.store_id) = :s and a.del_flag = '0' and a.status in ('onboarded','completed') and j.job_type <> 'online' "
                // 会员规则开着时和提交校验同一口径（KgMembershipRewards.APP_REVIEWABLE_SQL）：换工 / 兼职要门店确认完成，全职入职即可
                + (memberRewards!=null&&memberRewards.enabled()?" and "+KgMembershipRewards.APP_REVIEWABLE_SQL+" ":"")
                + "and not exists (select 1 from kg_review v where v.biz_type = 'application' and v.biz_id = a.app_id and v.app_user_id = :u) "
                + "order by a.app_id desc limit 1", p("u", me, "s", storeId));
        return r == null ? null : KgJdbc.asLong(r.get("appId"));
    }
}
