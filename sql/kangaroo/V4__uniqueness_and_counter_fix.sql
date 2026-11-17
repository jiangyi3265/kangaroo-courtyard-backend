-- =============================================================================
-- 袋鼠小院 V4 —— 业务唯一性下沉到数据库 + 存量计数纠偏
--
-- 背景（发布前排查实测，8/6 并发）：
--   业务唯一性此前只靠 Service 层的 select count(...) 判重，没有任何数据库约束。
--   并发下判重与插入之间存在竞态，实测结果：
--     · 同一用户对同一岗位 8 并发提交 -> 落库 6 条活跃报名
--     · 同城同名门店 6 并发 -> 落库 6 行
--     · 同门店同区间面试时段 6 并发 -> 落库 5 行
--   对照组 kg_staff_grant / kg_job_favorite 因为有唯一索引，同样并发下干净。
--
--   重复报名叠加"确认录用扣名额"会让同一个人把岗位名额打光；重复时段会让多个
--   应聘者被约到同一时刻。手机端弱网重试、用户连点、后台多人同时录入都会触发。
--
-- 做法：保留 Service 层的判重（它负责给用户友好提示），但正确性由数据库唯一约束保证。
--   软删与终态历史行必须能和新记录共存，所以用"生成列 + 唯一索引"表达部分索引：
--   生成列在不该参与唯一性时求值为 NULL，而 MySQL 唯一索引不约束 NULL。
--
-- 前置：MySQL 8.0（生成列 stored + 表达式默认值）。本机实测 8.0.45。
-- 幂等：建列建索引会先判断是否已存在；去重与计数纠偏语句本身也是重复执行无副作用的。
--       带着无法自动去重的数据执行时，会在任何 DDL 之前中止并给出清单（见 0-后置断言），
--       不会把库留在"一半新一半旧"的状态。
-- 回退：V4__rollback.sql
-- =============================================================================

-- 应用侧通过 JDBC 的 sessionVariables 把会话钉在 +08:00；用 mysql 客户端跑这个脚本时
-- 会话时区是 SYSTEM，本机实测与 +08:00 相差 16 小时。下面所有 now() 写进的是审计列，
-- 不钉住的话迁移行的时间会和同库其它行对不上。
set time_zone = '+08:00';

drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_abort_if_duplicates_remain;

delimiter $$

create procedure kg_add_col_if_absent(in p_table varchar(64), in p_column varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.columns
                   where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @s = concat('alter table ', p_table, ' add column ', p_ddl);
        prepare st from @s; execute st; deallocate prepare st;
    end if;
end$$

create procedure kg_add_idx_if_absent(in p_table varchar(64), in p_index varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.statistics
                   where table_schema = database() and table_name = p_table and index_name = p_index) then
        set @s = concat('alter table ', p_table, ' add ', p_ddl);
        prepare st from @s; execute st; deallocate prepare st;
    end if;
end$$

delimiter ;

-- -----------------------------------------------------------------------------
-- 0) 先清理存量重复，否则加唯一索引会失败
--    保留每组里 app_id 最小的那条（最早提交的），其余标记为逻辑删除并注明原因。
--    不物理删除：报名是有审计价值的业务记录。
-- -----------------------------------------------------------------------------
-- 0-前置：先把"将被软删的重复报名"占着的面试时段释放掉。
-- 顺序很重要：软删之后这些报名对所有接口不可见、状态也不在终态集合里，
-- 步骤 5 的释放条件就覆盖不到它们，时段会永远停在 booked ——
-- 后台既改不了（"时段已被预约占用"）也删不了，正是本脚本要修的那类僵尸数据。
update kg_interview_slot sl
  join kg_application a on a.app_id = sl.booked_app_id
  join (
        select app_user_id, job_id, min(app_id) as keep_id
        from kg_application
        where del_flag = '0'
          and status not in ('review_rejected', 'withdrawn', 'terminated')
        group by app_user_id, job_id
        having count(1) > 1
       ) d on d.app_user_id = a.app_user_id and d.job_id = a.job_id
   set sl.status = 'open', sl.booked_app_id = null,
       sl.update_by = 'V4-migration', sl.update_time = now()
 where sl.status = 'booked'
   and a.del_flag = '0'
   and a.status not in ('review_rejected', 'withdrawn', 'terminated')
   and a.app_id <> d.keep_id;

update kg_interview iv
  join kg_application a on a.app_id = iv.app_id
  join (
        select app_user_id, job_id, min(app_id) as keep_id
        from kg_application
        where del_flag = '0'
          and status not in ('review_rejected', 'withdrawn', 'terminated')
        group by app_user_id, job_id
        having count(1) > 1
       ) d on d.app_user_id = a.app_user_id and d.job_id = a.job_id
   set iv.status = 'canceled', iv.update_by = 'V4-migration', iv.update_time = now()
 where iv.status = 'booked'
   and a.del_flag = '0'
   and a.status not in ('review_rejected', 'withdrawn', 'terminated')
   and a.app_id <> d.keep_id;

update kg_application a
  join (
        select app_user_id, job_id, min(app_id) as keep_id
        from kg_application
        where del_flag = '0'
          and status not in ('review_rejected', 'withdrawn', 'terminated')
        group by app_user_id, job_id
        having count(1) > 1
       ) d on d.app_user_id = a.app_user_id and d.job_id = a.job_id
   set a.del_flag = '2',
       a.remark = concat(ifnull(a.remark, ''), '[V4迁移] 并发产生的重复报名，保留 app_id=', d.keep_id),
       a.update_by = 'V4-migration',
       a.update_time = now()
 where a.del_flag = '0'
   and a.status not in ('review_rejected', 'withdrawn', 'terminated')
   and a.app_id <> d.keep_id;

update kg_store s
  join (
        select store_name, city, min(store_id) as keep_id
        from kg_store where del_flag = '0'
        group by store_name, city having count(1) > 1
       ) d on d.store_name = s.store_name and d.city = s.city
   set s.del_flag = '2',
       s.remark = concat(ifnull(s.remark, ''), '[V4迁移] 并发产生的重复门店，保留 store_id=', d.keep_id),
       s.update_by = 'V4-migration',
       s.update_time = now()
 where s.del_flag = '0' and s.store_id <> d.keep_id
   -- 名下还有岗位或报名的重复门店不能在这里软删：
   -- 那会绕过 Service 层的"门店下仍有岗位/报名不能删除"两道保护，
   -- 把 kg_job.store_id 指向一个 del_flag='2' 的门店，之后这些岗位连编辑都过不了
   -- validate() 的"所属门店不存在或已删除"。这种情况留给下面的前置断言拦下来，
   -- 由人决定把岗位迁到哪个门店，而不是脚本替人做决定。
   and not exists (select 1 from kg_job j
                    where j.store_id = s.store_id and j.del_flag = '0')
   and not exists (select 1 from kg_application a
                    where a.store_id = s.store_id and a.del_flag = '0');

update kg_interview_slot sl
  join (
        select store_id, start_time, end_time, min(slot_id) as keep_id
        from kg_interview_slot where del_flag = '0'
        group by store_id, start_time, end_time having count(1) > 1
       ) d on d.store_id = sl.store_id and d.start_time = sl.start_time and d.end_time = sl.end_time
   set sl.del_flag = '2',
       sl.remark = concat(ifnull(sl.remark, ''), '[V4迁移] 重复时段，保留 slot_id=', d.keep_id),
       sl.update_by = 'V4-migration',
       sl.update_time = now()
 where sl.del_flag = '0' and sl.slot_id <> d.keep_id
   -- 已经被预约占用的时段不动，避免把真实面试记录连带作废
   and sl.status <> 'booked';

-- -----------------------------------------------------------------------------
-- 0-后置断言：确认三张表都已无残留重复，再开始建列建索引。
--
-- 为什么必须先断言：本脚本不是一个事务（DDL 在 MySQL 里不可回滚）。
-- 如果带着残留重复直接建唯一索引，会在中途抛 ER_DUP_ENTRY 退出，
-- 此时前面的生成列和索引已经建好、后面的计数纠偏和时段释放却没跑，
-- 库会停在"一半新一半旧"的状态。
--
-- 上面的去重刻意放过了两类行：已被预约占用的重复时段、名下仍有岗位或报名的重复门店。
-- 放过是对的（不能连带作废真实面试、不能制造孤儿岗位），但它们确实会让索引建不起来，
-- 所以在这里带着具体清单中止，交给人处理。
-- -----------------------------------------------------------------------------
delimiter $$

create procedure kg_abort_if_duplicates_remain()
begin
    declare v_app int default 0;
    declare v_store int default 0;
    declare v_slot int default 0;
    declare v_msg varchar(512);

    select count(1) into v_app from (
        select 1 from kg_application
         where del_flag = '0'
           and status not in ('review_rejected', 'withdrawn', 'terminated')
         group by app_user_id, job_id having count(1) > 1) t;

    select count(1) into v_store from (
        select 1 from kg_store where del_flag = '0'
         group by store_name, city having count(1) > 1) t;

    select count(1) into v_slot from (
        select 1 from kg_interview_slot where del_flag = '0'
         group by store_id, start_time, end_time having count(1) > 1) t;

    if v_app > 0 or v_store > 0 or v_slot > 0 then
        set v_msg = concat('[V4 中止] 仍有无法自动去重的数据，唯一索引建不起来。',
                           '重复的活跃报名组=', v_app,
                           '，同城同名门店组=', v_store,
                           '，同区间面试时段组=', v_slot,
                           '。请先用 V4__duplicates-report.sql 查看清单并人工处理，'
                           '再重新执行本脚本。本次没有改动任何表结构。');
        signal sqlstate '45000' set message_text = v_msg;
    end if;
end $$

delimiter ;

call kg_abort_if_duplicates_remain();

-- -----------------------------------------------------------------------------
-- 1) kg_application：同一用户同一岗位只允许一条"活跃"报名
--    终态（驳回/撤回/终止）与逻辑删除行求值为 NULL，不参与唯一性，
--    所以用户被驳回后可以重新报名，历史记录也能保留。
--    这里的条件必须与 KgApplicationMapper.countActiveByUserAndJob 的 where 完全一致。
-- -----------------------------------------------------------------------------
call kg_add_col_if_absent('kg_application', 'active_uk',
    "active_uk varchar(48) generated always as (
        case when del_flag = '0'
              and status not in ('review_rejected', 'withdrawn', 'terminated')
             then concat(app_user_id, '-', job_id) end) stored
     comment '活跃报名唯一键，终态与软删行为 NULL 不参与唯一性'");

call kg_add_idx_if_absent('kg_application', 'uk_app_active_user_job',
    'unique key uk_app_active_user_job (active_uk)');

-- -----------------------------------------------------------------------------
-- 2) kg_store：同城同名唯一，软删行放过
-- -----------------------------------------------------------------------------
call kg_add_col_if_absent('kg_store', 'name_city_uk',
    "name_city_uk varchar(110) generated always as (
        case when del_flag = '0' then concat(store_name, '@', city) end) stored
     comment '同城同名唯一键，软删行为 NULL'");

call kg_add_idx_if_absent('kg_store', 'uk_store_name_city',
    'unique key uk_store_name_city (name_city_uk)');

-- -----------------------------------------------------------------------------
-- 3) kg_interview_slot：同门店完全相同的时间区间唯一
--    说明：唯一索引只能挡住"完全重合"，挡不住部分重叠（09:00-10:00 与 09:30-10:30）。
--    部分重叠仍由 Service 层的 countOverlap 负责，本索引是并发下的最后一道防线。
--    已占用的时段也纳入唯一性，避免释放后又被插入一条同区间的新时段。
-- -----------------------------------------------------------------------------
call kg_add_col_if_absent('kg_interview_slot', 'slot_uk',
    "slot_uk varchar(64) generated always as (
        case when del_flag = '0' then concat(store_id, '|', start_time, '|', end_time) end) stored
     comment '同门店同区间唯一键，软删行为 NULL'");

call kg_add_idx_if_absent('kg_interview_slot', 'uk_slot_store_range',
    'unique key uk_slot_store_range (slot_uk)');

-- -----------------------------------------------------------------------------
-- 4) 存量计数纠偏
--    hired_count 此前只增不减：确认录用后再终止不会归还名额，岗位会被"名额已满"永久锁死。
--    代码侧已在 transfer() 里补了释放；这里把历史漂移一次性算回来。
-- -----------------------------------------------------------------------------
update kg_job j
   set j.applied_count = (select count(1) from kg_application a
                           where a.job_id = j.job_id and a.del_flag = '0'),
       j.hired_count = (select count(1) from kg_application a
                         where a.job_id = j.job_id and a.del_flag = '0'
                           and a.status in ('store_confirmed', 'contract_signed', 'onboarded'))
 where j.del_flag = '0';

-- -----------------------------------------------------------------------------
-- 5) 释放被终态报名占住的面试时段
--    userConfirm 的 reject 分支此前漏了释放，时段被永久锁死、后台也改不了删不了。
--
--    终态一共**四个**，与 ApplicationStatus.isFinal() 保持一致：
--    review_rejected / withdrawn / terminated / onboarded。
--    早先这里只列了前三个，漏掉 onboarded —— 走完整流程入职的那些历史报名，
--    它们占着的时段不会被这次迁移放出来，仍然停在 booked。
-- -----------------------------------------------------------------------------
update kg_interview_slot sl
  join kg_application a on a.app_id = sl.booked_app_id
   set sl.status = 'open', sl.booked_app_id = null,
       sl.update_by = 'V4-migration', sl.update_time = now()
 where sl.status = 'booked'
   and a.status in ('review_rejected', 'withdrawn', 'terminated', 'onboarded');

-- 只作废"还停在 booked"的面试记录。
-- 入职路径上那场面试是真发生过的，finishInterview 已经把它置成 done，
-- 改写成 canceled 就把历史改错了 —— 这正是代码侧 releaseSlotIfAny 刻意拆开的那一点。
-- where 里的 iv.status = 'booked' 已经保证了这一条，加上 onboarded 只是让终态口径一致。
update kg_interview iv
  join kg_application a on a.app_id = iv.app_id
   set iv.status = 'canceled', iv.update_by = 'V4-migration', iv.update_time = now()
 where iv.status = 'booked'
   and a.status in ('review_rejected', 'withdrawn', 'terminated', 'onboarded');

drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_abort_if_duplicates_remain;

select '[V4] 完成' as result,
       (select count(1) from kg_application where del_flag = '0') as 活跃报名,
       (select count(1) from kg_job j where j.del_flag = '0'
          and j.hired_count <> (select count(1) from kg_application a
                                 where a.job_id = j.job_id and a.del_flag = '0'
                                   and a.status in ('store_confirmed','contract_signed','onboarded'))) as 计数仍不一致的岗位,
       (select count(1) from kg_interview_slot sl join kg_application a on a.app_id = sl.booked_app_id
         where sl.status = 'booked' and a.status in ('review_rejected','withdrawn','terminated')) as 仍被终态报名占用的时段;
