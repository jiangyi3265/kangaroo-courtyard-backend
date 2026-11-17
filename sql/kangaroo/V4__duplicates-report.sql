-- =============================================================================
-- V4 前置检查 · 列出"无法自动去重、需要人工处理"的数据
--
-- V4 迁移在建唯一索引之前会断言三张表已无残留重复；断言失败时执行这个脚本查看清单。
-- 本脚本**只读**，不修改任何数据。
--
-- 用法：
--   mysql -u<用户> -p <库名> < sql/kangaroo/V4__duplicates-report.sql
-- =============================================================================

set time_zone = '+08:00';

select '=== 1. 重复的活跃报名（同一用户同一岗位多条未终止的报名）===' as section;

select a.app_id, a.app_no, a.app_user_id, a.job_id, a.status, a.create_time,
       case when sl.slot_id is null then '未占时段'
            else concat('占用时段 ', sl.slot_id, ' ', sl.start_time) end as 时段占用
  from kg_application a
  left join kg_interview_slot sl on sl.booked_app_id = a.app_id and sl.status = 'booked'
 where a.del_flag = '0'
   and a.status not in ('review_rejected', 'withdrawn', 'terminated')
   and (a.app_user_id, a.job_id) in (
        select app_user_id, job_id from kg_application
         where del_flag = '0'
           and status not in ('review_rejected', 'withdrawn', 'terminated')
         group by app_user_id, job_id having count(1) > 1)
 order by a.app_user_id, a.job_id, a.app_id;

select '处理建议：保留每组里最早的一条，其余改为 withdrawn 或 terminated（走后台的终止操作，'
       '这样名额与时段会被正确归还），不要直接改 del_flag。' as 建议;


select '=== 2. 同城同名的重复门店 ===' as section;

select s.store_id, s.store_name, s.city, s.status, s.create_time,
       (select count(1) from kg_job j where j.store_id = s.store_id and j.del_flag = '0') as 名下岗位,
       (select count(1) from kg_application a where a.store_id = s.store_id and a.del_flag = '0') as 名下报名
  from kg_store s
 where s.del_flag = '0'
   and (s.store_name, s.city) in (
        select store_name, city from kg_store where del_flag = '0'
         group by store_name, city having count(1) > 1)
 order by s.store_name, s.city, s.store_id;

select '处理建议：名下岗位与报名都为 0 的重复门店，V4 会自动软删；'
       '仍有岗位或报名的，需要先把岗位改挂到保留的那个门店，再重跑 V4。' as 建议;


select '=== 3. 同门店同区间的重复面试时段 ===' as section;

select sl.slot_id, sl.store_id, sl.start_time, sl.end_time, sl.status, sl.booked_app_id
  from kg_interview_slot sl
 where sl.del_flag = '0'
   and (sl.store_id, sl.start_time, sl.end_time) in (
        select store_id, start_time, end_time from kg_interview_slot where del_flag = '0'
         group by store_id, start_time, end_time having count(1) > 1)
 order by sl.store_id, sl.start_time, sl.slot_id;

select '处理建议：status=booked 的时段 V4 不会动（避免连带作废真实面试）。'
       '请先在后台取消这些预约或改约到别的时间，再重跑 V4。' as 建议;
