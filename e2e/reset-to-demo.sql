-- =============================================================================
-- 把 kg_ 业务数据重置到「演示基线」。
--
-- !! 危险 !!
-- 它按白名单保留数据：只保留 seed_demo_data.py 创建的 3 个门店、12 个岗位、
-- 6 个演示账号，其余 kg_ 业务数据一律物理删除。
-- 只能在开发/测试库上执行。生产环境请用 cleanup.sql（按测试前缀保守清理）。
--
-- 用途：多轮自动化排查会留下大量带随机前缀的测试数据（verif- / v3- / probe- / 复核 …），
--       靠前缀逐个清理追不过来，用白名单反向清理更可靠。
--
-- 为什么必须显式确认：白名单是"按演示门店名与 openid 前缀查出来的"。
-- 在生产库上这些前缀一条都查不到，于是三张白名单临时表全是空的，
-- 而 `where xxx not in (空集)` 在 MySQL 里恒为真 —— 它会删光全部业务数据。
-- 所以脚本开头要求显式声明"这不是生产库"，并且要求白名单真的查到了东西。
--
-- 用法：
--   mysql -uroot -p dkxy -e "set @I_KNOW_THIS_IS_NOT_PRODUCTION=1; source e2e/reset-to-demo.sql"
-- 或（等价）：
--   ( echo "set @I_KNOW_THIS_IS_NOT_PRODUCTION=1;"; cat e2e/reset-to-demo.sql ) | mysql -uroot -p dkxy
--   python e2e/seed_demo_data.py      # 重新补齐演示报名与员工授权
-- =============================================================================

-- ---------------------------------------------------------------- 守卫
drop procedure if exists kg_reset_guard;
delimiter $$
create procedure kg_reset_guard(in p_ack int, in p_stores int, in p_users int, in p_jobs int)
begin
  if p_ack is null or p_ack <> 1 then
    signal sqlstate '45000' set message_text =
      'reset-to-demo.sql 会按白名单物理删除业务数据。执行前必须先 set @I_KNOW_THIS_IS_NOT_PRODUCTION=1 明确声明这不是生产库。';
  end if;
  if p_stores = 0 or p_users = 0 or p_jobs = 0 then
    signal sqlstate '45000' set message_text =
      '演示白名单一条都没查到（这个库里没有演示门店/演示账号/演示岗位）。白名单为空时 NOT IN 恒为真，脚本会删光整库，已中止。';
  end if;
end $$
delimiter ;

-- 用 in (...) 而不是 find_in_set(@var)：会话变量的排序规则与列不同，
-- MySQL 8 会以 "Illegal mix of collations" 直接报错。
create temporary table if not exists _keep_store (store_id bigint primary key);
truncate table _keep_store;
insert into _keep_store
select store_id from kg_store
 where store_name in ('古城拾光咖啡馆', '双廊听海民宿', '喜洲花间手作') and del_flag = '0';

create temporary table if not exists _keep_user (user_id bigint primary key);
truncate table _keep_user;
insert into _keep_user
select user_id from kg_app_user where openid like 'mock_h5-seed-app-0%';

create temporary table if not exists _keep_job (job_id bigint primary key);
truncate table _keep_job;
insert into _keep_job
select job_id from kg_job
 where store_id in (select store_id from _keep_store) and del_flag = '0'
   and title not like '回归%' and title not like 'hunt-%' and title not like '复核%'
   and title not like 'E2E%' and title not like 'ZZ%';

-- 白名单建好之后、动手删之前，先过守卫
call kg_reset_guard(@I_KNOW_THIS_IS_NOT_PRODUCTION,
                    (select count(*) from _keep_store),
                    (select count(*) from _keep_user),
                    (select count(*) from _keep_job));
drop procedure if exists kg_reset_guard;

-- 按依赖顺序删除
delete from kg_application_log
 where app_id in (select app_id from kg_application
                   where store_id not in (select store_id from _keep_store)
                      or app_user_id not in (select user_id from _keep_user)
                      or job_id not in (select job_id from _keep_job));

delete from kg_interview
 where app_id in (select app_id from kg_application
                   where store_id not in (select store_id from _keep_store)
                      or app_user_id not in (select user_id from _keep_user)
                      or job_id not in (select job_id from _keep_job));

delete from kg_application
 where store_id not in (select store_id from _keep_store)
    or app_user_id not in (select user_id from _keep_user)
    or job_id not in (select job_id from _keep_job);

delete from kg_interview_slot where store_id not in (select store_id from _keep_store);

-- 时段整体重建，只留下"仍被保留的面试记录钉住"的那几条。
--
-- 这里原本的规则是"删掉 30 天以后的时段"，理由是回归用例为了绕开同门店同区间的
-- 唯一约束会把时段排到一两年后。但后来补的用例 —— C14 的 X13、C06 的预约链路 ——
-- 排的是"当前起 2~3 天"，这条规则一条都删不到，于是每跑一轮基线里的时段就多几条
-- （实测从 16 涨到 21）。"基线"这两个字也就不成立了。
--
-- 改成：不被面试记录钉住的一律删掉，再按保留门店重新排 3 个固定时段
-- （次日、后日、大后日的 10:00–10:30）。这样无论此前跑过多少轮，
-- 重置之后的时段数都只取决于保留下来的面试记录条数，是个真的定值。
delete from kg_interview_slot
 where slot_id not in (select ifnull(slot_id, 0) from kg_interview
                        where app_id in (select app_id from kg_application));

insert into kg_interview_slot
       (store_id, start_time, end_time, status, del_flag, create_by, create_time)
select x.store_id, x.st, x.et, 'open', '0', 'reset-to-demo', now()
  from (select s.store_id as store_id,
               date_add(curdate(), interval (d.n + 1) * 1440 + 600 minute) as st,
               date_add(curdate(), interval (d.n + 1) * 1440 + 630 minute) as et
          from _keep_store s
          cross join (select 0 as n union all select 1 union all select 2) d) x
 on duplicate key update kg_interview_slot.update_time = now();
delete from kg_job_favorite where job_id not in (select job_id from _keep_job)
                               or app_user_id not in (select user_id from _keep_user);
delete from kg_job where job_id not in (select job_id from _keep_job);
delete from kg_staff_grant where store_id not in (select store_id from _keep_store)
                              or app_user_id not in (select user_id from _keep_user);
delete from kg_message where recipient_id not in (select user_id from _keep_user);

-- 还要删掉"收件人是保留账号、但关联的报名已经不在了"的孤儿消息。
-- 测试造的报名会给门店员工发待办消息，而员工恰好是保留账号，
-- 只按收件人过滤的话这些消息会一轮轮堆下去，演示基线的消息数就不再稳定
-- （实测跑了几轮之后从 16 涨到 45）。
delete from kg_message
 where biz_type = 'application' and biz_id is not null
   and not exists (select 1 from kg_application a where a.app_id = kg_message.biz_id);
-- ---------------------------------------------------------------- 扩展模块（V7–V13）
-- 只删属于非演示账号 / 非演示门店 / 已删除岗位与报名的数据；可空列（例如用户自发的活动没有门店）为 NULL 时 NOT IN 不命中，不会误删。
-- 顺序：先子表后父表。
delete from kg_online_submission where app_id not in (select app_id from kg_application);
delete from kg_online_payout_correction where app_id not in (select app_id from kg_application)
 or settle_id not in (select settle_id from kg_online_settlement);
delete from kg_online_settlement where app_id not in (select app_id from kg_application);
delete from kg_online_task where job_id not in (select job_id from _keep_job);
delete from kg_talent_invite where store_id not in (select store_id from _keep_store) or app_user_id not in (select user_id from _keep_user) or job_id not in (select job_id from _keep_job);
delete from kg_talent_mark where store_id not in (select store_id from _keep_store) or app_user_id not in (select user_id from _keep_user);
delete from kg_task_assignee where app_user_id not in (select user_id from _keep_user) or task_id in (select task_id from kg_task where store_id not in (select store_id from _keep_store));
delete from kg_task where store_id not in (select store_id from _keep_store);
delete from kg_weekly_item where report_id in (select report_id from kg_weekly_report where store_id not in (select store_id from _keep_store));
delete from kg_weekly_report where store_id not in (select store_id from _keep_store);
delete from kg_daily_entry where store_id not in (select store_id from _keep_store);
delete from kg_point_entry where app_user_id not in (select user_id from _keep_user);
delete from kg_wallet_withdraw where app_user_id not in (select user_id from _keep_user);
delete from kg_wallet_entry where app_user_id not in (select user_id from _keep_user) or store_id not in (select store_id from _keep_store);
delete from kg_wallet_payee where app_user_id not in (select user_id from _keep_user);
delete from kg_commission_rule where payee_user_id not in (select user_id from _keep_user) or store_id not in (select store_id from _keep_store);
delete from kg_honor_award where app_user_id not in (select user_id from _keep_user);
delete from kg_growth_milestone where app_user_id not in (select user_id from _keep_user);
delete from kg_gift_claim where app_user_id not in (select user_id from _keep_user);
delete from kg_appeal where app_user_id not in (select user_id from _keep_user);
delete from kg_support_log where app_user_id not in (select user_id from _keep_user);
delete from kg_support_ticket where app_user_id not in (select user_id from _keep_user);
delete from kg_job_subscribe where app_user_id not in (select user_id from _keep_user) or store_id not in (select store_id from _keep_store);
delete from kg_resume_exp where app_user_id not in (select user_id from _keep_user);
delete from kg_resume where app_user_id not in (select user_id from _keep_user);
delete from kg_cert where app_user_id not in (select user_id from _keep_user);
delete from kg_contract_sign where app_id not in (select app_id from kg_application);
delete from kg_app_arrival where app_id not in (select app_id from kg_application);
delete from kg_team_member where app_id not in (select app_id from kg_application);
delete from kg_notify_pref where app_user_id not in (select user_id from _keep_user);
delete from kg_guide_apply where app_user_id not in (select user_id from _keep_user);
delete from kg_insurance_apply where app_id not in (select app_id from kg_application);
delete from kg_dispatch_log where store_id not in (select store_id from _keep_store);
delete from kg_job_share where job_id not in (select job_id from _keep_job);
-- 2026-09-24 补：V8/V13–V15 的这几张表原先不在清理范围，测试账号删掉后会留下孤儿行（订阅额度、待办负责人、转发记录…）
delete from kg_job_share_visit where share_code not in (select share_code from kg_job_share);
delete from kg_resume_forward where app_id not in (select app_id from kg_application);
delete from kg_todo_meta where store_id not in (select store_id from _keep_store);
delete from kg_subscribe_grant where app_user_id not in (select user_id from _keep_user);
delete from kg_subscribe_log where app_user_id not in (select user_id from _keep_user);
delete from kg_subscribe_outbox where app_user_id not in (select user_id from _keep_user);
delete from kg_account_deletion where app_user_id not in (select user_id from _keep_user);
delete from kg_staff_op_log where store_id not in (select store_id from _keep_store) or app_user_id not in (select user_id from _keep_user);
delete from kg_activity where store_id not in (select store_id from _keep_store) or app_user_id not in (select user_id from _keep_user);
delete from kg_activity_signup where app_user_id not in (select user_id from _keep_user) or activity_id not in (select activity_id from kg_activity);
delete from kg_activity_stat where activity_id not in (select activity_id from kg_activity);
delete from kg_activity_subscribe where app_user_id not in (select user_id from _keep_user);
delete from kg_post where app_user_id not in (select user_id from _keep_user);
delete from kg_post_comment where app_user_id not in (select user_id from _keep_user) or post_id not in (select post_id from kg_post);
delete from kg_post_like where app_user_id not in (select user_id from _keep_user) or post_id not in (select post_id from kg_post);
delete from kg_post_comment_like where app_user_id not in (select user_id from _keep_user) or comment_id not in (select comment_id from kg_post_comment);
-- 临时表在同一条语句里不能引用两次（MySQL 1137），两个方向分开删
delete from kg_user_follow where follower_id not in (select user_id from _keep_user);
delete from kg_user_follow where followee_id not in (select user_id from _keep_user);
delete from kg_user_block where user_id not in (select user_id from _keep_user);
delete from kg_chat_member where app_user_id not in (select user_id from _keep_user);
delete from kg_chat_msg where sender_id not in (select user_id from _keep_user) or conv_id not in (select conv_id from kg_chat_member);
delete from kg_chat_conv where conv_id not in (select conv_id from kg_chat_member);
delete from kg_trip_plan where app_user_id not in (select user_id from _keep_user);
delete from kg_consent where app_user_id not in (select user_id from _keep_user);
delete from kg_pii_access where viewer_id not in (select user_id from _keep_user);
delete from kg_pii_access where target_id not in (select user_id from _keep_user);
delete from kg_report where reporter_id not in (select user_id from _keep_user);
delete from kg_favorite where app_user_id not in (select user_id from _keep_user);
delete from kg_review where app_user_id not in (select user_id from _keep_user) or store_id not in (select store_id from _keep_store);
delete from kg_pay_order where app_user_id not in (select user_id from _keep_user) or store_id not in (select store_id from _keep_store);
delete from kg_shop_order where app_user_id not in (select user_id from _keep_user) or store_id not in (select store_id from _keep_store);
delete from kg_stay_order where app_user_id not in (select user_id from _keep_user) or store_id not in (select store_id from _keep_store);
delete from kg_stay_activity_selection where order_id not in (select order_id from kg_stay_order);
delete from kg_stay_vlog_comment where app_user_id not in (select user_id from _keep_user) or store_id not in (select store_id from _keep_store);
delete from kg_stay_vlog_event where store_id not in (select store_id from _keep_store);
delete from kg_stay_vlog where store_id not in (select store_id from _keep_store);
delete from kg_shop_service where store_id not in (select store_id from _keep_store);
delete from kg_stay where store_id not in (select store_id from _keep_store);
delete from kg_stay_calendar where stay_id not in (select stay_id from kg_stay);
delete from kg_stay_promo where stay_id not in (select stay_id from kg_stay);
delete from kg_stay_room_type where stay_id not in (select stay_id from kg_stay);
delete from kg_stay_qualification where stay_id not in (select stay_id from kg_stay);

delete from kg_app_user where user_id not in (select user_id from _keep_user);
delete from kg_store where store_id not in (select store_id from _keep_store);
delete from kg_idempotency;

-- 计数按实际报名重算
update kg_job j
   set j.applied_count = (select count(1) from kg_application a
                           where a.job_id = j.job_id and a.del_flag = '0'),
       j.hired_count = (select count(1) from kg_application a
                         where a.job_id = j.job_id and a.del_flag = '0'
                           and a.status in ('store_confirmed', 'contract_signed', 'onboarded'))
 where j.del_flag = '0';

-- 释放被已删除报名占住的时段
update kg_interview_slot
   set status = 'open', booked_app_id = null
 where booked_app_id is not null
   and booked_app_id not in (select app_id from kg_application);

drop temporary table if exists _keep_store;
drop temporary table if exists _keep_user;
drop temporary table if exists _keep_job;

select '已重置到演示基线' as result,
       (select count(1) from kg_store) as 门店,
       (select count(1) from kg_job) as 岗位,
       (select count(1) from kg_app_user) as 账号,
       (select count(1) from kg_application) as 报名,
       (select count(1) from kg_interview_slot) as 面试时段,
       (select count(1) from kg_message) as 消息;
