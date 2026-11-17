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
-- V74 回滚：删掉员工任务的批次表、时间线表、kg_staff_task 的四列两索引、「处理任务」按钮权限和账本记录，菜单名改回「主理人任务派发」。
-- 任务本身（kg_staff_task / 提交 / 图片）保留；新状态退回老 jar 认得的：进行中、无法完成 → 待完成（pending）。
-- 有「已关闭」的任务时拒绝回滚：老 jar 没有这个状态，也没有合适的状态能替它（标成已通过是假的）；先在新版本里处理，或确认后手工改。
-- 按全体 / 岗位派发出去的任务，回滚后只要负责人在那家店还有义工主理人授权才看得到（老 jar 只认 manager）。
-- 只退 jar 不用跑这个脚本：老 jar 不读新列新表，新状态的任务在老页面上状态标签是空的。
-- 停止应用并备份后，显式 set @kg_allow_staff_task_flow_rollback=1。
drop procedure if exists kg_rollback_staff_task_flow;
delimiter $$
create procedure kg_rollback_staff_task_flow()
begin
    if coalesce(@kg_allow_staff_task_flow_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V74 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 74) then
        signal sqlstate '45000' set message_text = 'V74 rollback blocked by later migrations';
    end if;
    if exists (select 1 from kg_staff_task where status = 'closed') then
        signal sqlstate '45000' set message_text = 'V74 rollback blocked: closed staff tasks exist (old jar has no closed status)';
    end if;
    update kg_staff_task set status = 'pending' where status in ('accepted', 'declined');
    update sys_menu set menu_name = '主理人任务派发', remark = '管理员派发任务，主理人图文回执，后台审核'
    where perms = 'kangaroo:stafftask:list' and menu_name = '员工任务派发';
    delete rm from sys_role_menu rm join sys_menu m on m.menu_id = rm.menu_id where m.perms = 'kangaroo:stafftask:handle';
    delete from sys_menu where perms = 'kangaroo:stafftask:handle';
    call kg_drop_idx_if_present('kg_staff_task', 'idx_staff_task_attention');
    call kg_drop_idx_if_present('kg_staff_task', 'idx_staff_task_batch');
    call kg_drop_col_if_present('kg_staff_task', 'admin_attention');
    call kg_drop_col_if_present('kg_staff_task', 'requested_due_date');
    call kg_drop_col_if_present('kg_staff_task', 'role_code');
    call kg_drop_col_if_present('kg_staff_task', 'batch_id');
    drop table if exists kg_staff_task_event;
    drop table if exists kg_staff_task_batch;
    delete from kg_schema_version where version = 74;
end$$
delimiter ;
call kg_rollback_staff_task_flow();
drop procedure if exists kg_rollback_staff_task_flow;
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
