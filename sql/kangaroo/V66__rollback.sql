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
-- V66 回滚：改名还原、删掉 kg_job 的新列、报销的表和菜单、账本记录。
-- 只有新列全空、报销表一行都没有时才允许：这些是门店填的内容（餐补、宠物、住宿照片和视频）和财务记录，不能跟着回滚一起没了。
-- 只退 jar 不用跑这个脚本：V66 给 kg_job 只加可空列，报销是新表，线上 4bd5 jar 都不读不写。
-- 停止应用并备份后，显式 set @kg_allow_forms_rollback=1。
drop procedure if exists kg_rollback_forms;
delimiter $$
create procedure kg_rollback_forms()
begin
    if coalesce(@kg_allow_forms_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V66 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 66) then
        signal sqlstate '45000' set message_text = 'V66 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_job' and column_name = 'lodging_video_cover') then
        if exists (select 1 from kg_job where coalesce(meal_allowance, '') <> '' or coalesce(meal_allowance_unit, '') <> '' or coalesce(pet_status, '') <> ''
                   or coalesce(pet_images, '') <> '' or coalesce(lodging_images, '') <> '' or coalesce(lodging_video, '') <> '' or coalesce(lodging_video_cover, '') <> '') then
            signal sqlstate '45000' set message_text = 'V66 rollback blocked: kg_job has meal, pet or lodging data';
        end if;
    end if;
    -- 报销是财务记录，不能跟着回滚一起没了：有一行就拒绝（提交人撤回的、注销转匿名的都算）
    if exists (select 1 from information_schema.tables where table_schema = database() and table_name = 'kg_reimbursement') then
        if exists (select 1 from kg_reimbursement) then
            signal sqlstate '45000' set message_text = 'V66 rollback blocked: kg_reimbursement has data';
        end if;
    end if;

    update sys_dict_data set remark = replace(remark, '抖音卖房', '云连锁')
     where dict_type = 'kg_staff_role' and dict_value = 'merchant_service' and remark like '%抖音卖房%';
    update sys_menu set remark = replace(remark, '抖音卖房', '云连锁')
     where perms = 'kangaroo:merchantrequest:list' and remark like '%抖音卖房%';
    update kg_merchant_request set title = replace(title, '抖音卖房', '云连锁')
     where request_type = 'cloud_chain' and title like '%抖音卖房%';
    update kg_message set title = replace(title, '抖音卖房', '云连锁'), content = replace(content, '抖音卖房', '云连锁')
     where biz_type = 'merchant_request' and (title like '%抖音卖房%' or content like '%抖音卖房%');

    call kg_drop_col_if_present('kg_job', 'meal_allowance');
    call kg_drop_col_if_present('kg_job', 'meal_allowance_unit');
    call kg_drop_col_if_present('kg_job', 'pet_status');
    call kg_drop_col_if_present('kg_job', 'pet_images');
    call kg_drop_col_if_present('kg_job', 'lodging_images');
    call kg_drop_col_if_present('kg_job', 'lodging_video');
    call kg_drop_col_if_present('kg_job', 'lodging_video_cover');
    delete rm from sys_role_menu rm join sys_menu m on m.menu_id = rm.menu_id where m.perms like 'kangaroo:reimburse:%';
    delete from sys_menu where menu_type = 'F' and perms like 'kangaroo:reimburse:%';
    delete from sys_menu where perms = 'kangaroo:reimburse:list';
    drop table if exists kg_reimbursement;

    delete from kg_schema_version where version = 66;
end$$
delimiter ;
call kg_rollback_forms();
drop procedure if exists kg_rollback_forms;
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
