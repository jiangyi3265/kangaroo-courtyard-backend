-- =============================================================================
-- V74__staff_task_flow（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
-- =============================================================================
-- @kg-header-begin
-- ---- 迁移头部标准片段（原样复制自 sql/kangaroo/_snippets/header.sql，勿手改）----
-- 用 mysql 客户端跑脚本时会话时区是 SYSTEM；钉到 +08:00，now() 写进审计列才和应用写的对得上
set time_zone = '+08:00';

drop procedure if exists kg_assert_env;
drop procedure if exists kg_assert_same_collation;
drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_drop_col_if_present;
drop procedure if exists kg_drop_idx_if_present;

delimiter $$

-- 库默认字符集必须是 utf8mb4；p_requires 列出的前置迁移版本（逗号分隔）必须都已记在账本里
create procedure kg_assert_env(in p_requires varchar(255))
begin
    declare v_left varchar(255) default trim(ifnull(p_requires, ''));
    declare v_ver varchar(16);
    declare v_pos int;
    declare v_msg varchar(255);
    if @@character_set_database <> 'utf8mb4' then
        signal sqlstate '45000' set message_text = '[kg_assert_env] 库默认字符集不是 utf8mb4，已中止';
    end if;
    if v_left <> '' and not exists (select 1 from information_schema.tables
                                    where table_schema = database() and table_name = 'kg_schema_version') then
        signal sqlstate '45000' set message_text = '[kg_assert_env] 没有迁移账本 kg_schema_version（V6 未执行），已中止';
    end if;
    while v_left <> '' do
        set v_pos = locate(',', v_left);
        if v_pos = 0 then
            set v_ver = v_left;
            set v_left = '';
        else
            set v_ver = trim(substring(v_left, 1, v_pos - 1));
            set v_left = trim(substring(v_left, v_pos + 1));
        end if;
        if v_ver <> '' and not exists (select 1 from kg_schema_version where version = cast(v_ver as unsigned)) then
            set v_msg = concat('[kg_assert_env] 前置迁移 V', v_ver, ' 未执行（账本里没有），已中止');
            signal sqlstate '45000' set message_text = v_msg;
        end if;
    end while;
end$$

-- 新表的排序规则必须与 kg_application 相同，否则 join 时报 Illegal mix of collations
create procedure kg_assert_same_collation(in p_table varchar(64))
begin
    declare v_ref varchar(64);
    declare v_got varchar(64);
    declare v_msg varchar(255);
    set v_ref = (select table_collation from information_schema.tables
                  where table_schema = database() and table_name = 'kg_application');
    set v_got = (select table_collation from information_schema.tables
                  where table_schema = database() and table_name = p_table);
    if v_got is null or v_ref is null or v_got <> v_ref then
        -- SIGNAL 的 message_text 上限 128 个字符，这里控制在 100 以内
        set v_msg = left(concat('[kg_assert_same_collation] ', p_table, ' 排序规则 ', ifnull(v_got, '(无此表)'),
                                ' ≠ kg_application 的 ', ifnull(v_ref, '(无此表)')), 120);
        signal sqlstate '45000' set message_text = v_msg;
    end if;
end$$

create procedure kg_add_col_if_absent(in p_table varchar(64), in p_column varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.columns
                   where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @kg_s = concat('alter table ', p_table, ' add column ', p_ddl);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_add_idx_if_absent(in p_table varchar(64), in p_index varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.statistics
                   where table_schema = database() and table_name = p_table and index_name = p_index) then
        set @kg_s = concat('alter table ', p_table, ' add ', p_ddl);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_drop_col_if_present(in p_table varchar(64), in p_column varchar(64))
begin
    if exists (select 1 from information_schema.columns
               where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @kg_s = concat('alter table ', p_table, ' drop column ', p_column);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_drop_idx_if_present(in p_table varchar(64), in p_index varchar(64))
begin
    if exists (select 1 from information_schema.statistics
               where table_schema = database() and table_name = p_table and index_name = p_index) then
        set @kg_s = concat('alter table ', p_table, ' drop index ', p_index);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

delimiter ;
-- ---- 迁移头部标准片段结束 ----
-- @kg-header-end
call kg_assert_env('62');

-- V74：员工任务派发加流转、全体派发、按岗位派发（2026-11-03 主人：「任务派发，需要有个流转过程，不能只单向发送，派发任务，可以一键发送给所有全体员工。
-- 我还可以给某一个岗位，单独的发布任务」）。规则见 com.ruoyi.kangaroo.ext.manage.KgStaffTaskService。
-- 同一批主人要求里的「小院主理人审核活动」「过期线上任务继续开启」不用改表（KgKeeperActivityReview、KgOnlineTaskReopen），不在本迁移里。
-- 只依赖 V62（kg_staff_task 三张表）；V68–V73 是别的包的号，本迁移不依赖它们，先后执行都行。
-- 后端要在本段执行之后再发布：新代码启动时核对账本里有 V74 和两条新索引（StaffTaskFlowSchema），没有拒绝启动。
-- 只新增：两张新表、kg_staff_task 四列两索引、一个按钮权限；老任务补批次和时间线（按已有数据生成，可重复执行），
-- 菜单名「主理人任务派发」改成「员工任务派发」（只改还叫老名字的那一行，运营自己改过名的不动）。
-- 线上老 jar 不认新列新表也照常跑（老 jar 只认 pending/submitted/approved/rejected 四种状态，新状态的任务在老 jar 里标签是空的）；回滚见 V74__rollback.sql。

-- 一、派发批次：每点一次「确认派发」一行（点名、全体员工、按岗位都算），后台按批次看「完成 x/y」
create table if not exists kg_staff_task_batch (
  batch_id     bigint       not null auto_increment comment '批次ID',
  title        varchar(100) not null                comment '任务标题（派发时）',
  scope        varchar(16)  not null                comment 'pick 点名 / all 全体员工 / role 按岗位',
  role_code    varchar(32)  null                    comment '按岗位派发时的岗位（kg_staff_grant.role_code）',
  city         varchar(64)  null                    comment '按岗位 / 全体派发时限定的城市（门店城市归一后比）',
  store_id     bigint       null                    comment '限定的门店',
  total        int          not null default 0      comment '派出几条（一人一条）',
  request_key  varchar(100) not null                comment '幂等键（同 kg_staff_task.request_key）',
  create_by    varchar(64)  not null                comment '派发人（后台账号）',
  create_time  datetime     not null                comment '派发时间',
  primary key (batch_id),
  unique key uk_staff_task_batch_request (request_key)
) engine=innodb default charset=utf8mb4 comment='员工任务派发批次（V74）';

-- 二、时间线：谁、什么时候、做了什么、写了什么、附了哪些图（图片在 kg_staff_task_file，这里存编号 JSON）
create table if not exists kg_staff_task_event (
  event_id     bigint       not null auto_increment comment '事件ID',
  task_id      bigint       not null                comment '任务',
  actor_type   varchar(8)   not null                comment 'admin 后台 / staff 负责人 / system',
  actor_id     bigint       null                    comment '后台 sys_user.user_id 或小程序 kg_app_user.user_id',
  actor_name   varchar(64)  not null default ''     comment '当时的名字',
  action       varchar(24)  not null                comment 'assign accept reply extend_request decline submit approve reject extend reassign close reopen',
  content      text         null                    comment '留言 / 原因 / 完成说明',
  image_ids    varchar(512) null                    comment '附图（kg_staff_task_file.file_id 的 JSON 数组）',
  due_date     date         null                    comment '派发时的截止日期 / 申请延到 / 改成的截止日期',
  ref_id       bigint       null                    comment '提交、审核对应的 kg_staff_task_submission.submission_id',
  create_time  datetime     not null                comment '时间',
  primary key (event_id),
  key idx_staff_task_event (task_id, event_id),
  key idx_staff_task_event_ref (ref_id, action)
) engine=innodb default charset=utf8mb4 comment='员工任务流转记录（V74）';

set @kg_collation = (select table_collation from information_schema.tables where table_schema = database() and table_name = 'kg_application');
set @kg_s = concat('alter table kg_staff_task_batch convert to character set utf8mb4 collate ', @kg_collation); prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
set @kg_s = concat('alter table kg_staff_task_event convert to character set utf8mb4 collate ', @kg_collation); prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
call kg_assert_same_collation('kg_staff_task_batch');
call kg_assert_same_collation('kg_staff_task_event');

-- 三、kg_staff_task 四列两索引。状态列不改类型（varchar(16)），新状态：accepted 进行中 / declined 无法完成 / closed 已关闭；pending 改叫「待接收」。
-- 老任务都是派给义工主理人的（V62 只能选 manager），role_code 默认 manager 就是对的
call kg_add_col_if_absent('kg_staff_task', 'batch_id', 'batch_id bigint null comment ''派发批次（kg_staff_task_batch）'' after task_id');
call kg_add_col_if_absent('kg_staff_task', 'role_code', 'role_code varchar(32) not null default ''manager'' comment ''派发时按哪个岗位（转派后是新负责人的岗位）'' after assignee_id');
call kg_add_col_if_absent('kg_staff_task', 'requested_due_date', 'requested_due_date date null comment ''负责人申请延到哪天（后台改截止日期后清空）'' after due_date');
call kg_add_col_if_absent('kg_staff_task', 'admin_attention', 'admin_attention char(1) not null default ''0'' comment ''1 = 负责人有新动作（回复、申请延期、无法完成、提交），等后台处理'' after seen_notice_version');
call kg_add_idx_if_absent('kg_staff_task', 'idx_staff_task_batch', 'key idx_staff_task_batch (batch_id, status)');
call kg_add_idx_if_absent('kg_staff_task', 'idx_staff_task_attention', 'key idx_staff_task_attention (admin_attention, update_time)');

-- 四、老任务补批次：同一次派发（同一个 request_key）一个批次，算点名派发；已经有批次的不动
insert into kg_staff_task_batch (title, scope, role_code, city, store_id, total, request_key, create_by, create_time)
select min(t.title), 'pick', null, null, null, count(*), t.request_key, min(t.create_by), min(t.create_time)
from kg_staff_task t
where t.batch_id is null and not exists (select 1 from kg_staff_task_batch b where b.request_key = t.request_key)
group by t.request_key;
update kg_staff_task t join kg_staff_task_batch b on b.request_key = t.request_key set t.batch_id = b.batch_id where t.batch_id is null;

-- 五、老任务补时间线：派发一条、每次提交一条、每次审核一条（按已有数据生成；已经有的不重复补）
insert into kg_staff_task_event (task_id, actor_type, actor_id, actor_name, action, content, image_ids, due_date, ref_id, create_time)
select t.task_id, 'admin', null, t.create_by, 'assign', null, null, t.due_date, null, t.create_time
from kg_staff_task t
where not exists (select 1 from kg_staff_task_event e where e.task_id = t.task_id and e.action = 'assign');
insert into kg_staff_task_event (task_id, actor_type, actor_id, actor_name, action, content, image_ids, due_date, ref_id, create_time)
select x.task_id, 'staff', t.assignee_id, coalesce(u.nickname, ''), 'submit', x.content, x.image_ids, null, x.submission_id, x.create_time
from kg_staff_task_submission x join kg_staff_task t on t.task_id = x.task_id left join kg_app_user u on u.user_id = t.assignee_id
where not exists (select 1 from kg_staff_task_event e where e.ref_id = x.submission_id and e.action = 'submit');
insert into kg_staff_task_event (task_id, actor_type, actor_id, actor_name, action, content, image_ids, due_date, ref_id, create_time)
select x.task_id, 'admin', null, coalesce(x.review_by, ''), case when x.status = 'approved' then 'approve' else 'reject' end, x.review_note, null, null, x.submission_id, x.review_time
from kg_staff_task_submission x
where x.status in ('approved', 'rejected') and x.review_time is not null
  and not exists (select 1 from kg_staff_task_event e where e.ref_id = x.submission_id and e.action in ('approve', 'reject'));
-- 提交过还没审的老任务标「待处理」，后台列表一眼能看到
update kg_staff_task set admin_attention = '1' where status = 'submitted' and admin_attention = '0';

-- 六、菜单：改名「员工任务派发」（只改还叫老名字的），加按钮权限「处理任务」（回复、改截止日期、转派、关闭、退回重新处理）。
-- 优先用固定编号 3214，被占时退给自增；授权给能审核任务（kangaroo:stafftask:audit）的角色和超级管理员。只补不删
update sys_menu set menu_name = '员工任务派发', remark = '管理员派发任务给员工（点名 / 全体员工 / 按岗位），双方按时间线来回处理，后台审核'
where perms = 'kangaroo:stafftask:list' and menu_name = '主理人任务派发';
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select case when exists (select 1 from sys_menu x where x.menu_id = 3214) then null else 3214 end,
       '处理任务', p.menu_id, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stafftask:handle', '#', 'admin', now(), '回复、改截止日期、转派、关闭、退回重新处理'
from sys_menu p where p.perms = 'kangaroo:stafftask:list' and p.menu_type = 'C'
  and not exists (select 1 from sys_menu x where x.perms = 'kangaroo:stafftask:handle')
order by p.menu_id limit 1;
insert ignore into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, n.menu_id from sys_role_menu rm
join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:stafftask:audit'
join sys_menu n on n.perms = 'kangaroo:stafftask:handle';
insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms = 'kangaroo:stafftask:handle';

-- @kg-footer-begin
-- ---- 迁移尾部标准片段（原样复制自 sql/kangaroo/_snippets/header.sql，勿手改）----
drop procedure if exists kg_assert_env;
drop procedure if exists kg_assert_same_collation;
drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_drop_col_if_present;
drop procedure if exists kg_drop_idx_if_present;
-- ---- 迁移尾部标准片段结束 ----
-- @kg-footer-end
-- @kg-ledger（本行起不计入 checksum：checksum = 本行之前全部内容的 sha256）
insert into kg_schema_version (version, name, applied_time, checksum)
values (74, 'staff_task_flow', now(), 'c6a4730f6155ddc2774e1308dcfa77ddcd36ffc794e5f20f6808659e330f74d6')
on duplicate key update name = 'staff_task_flow', applied_time = now(), checksum = 'c6a4730f6155ddc2774e1308dcfa77ddcd36ffc794e5f20f6808659e330f74d6';
