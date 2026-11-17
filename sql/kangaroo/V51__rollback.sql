-- V51 回退：删除 kg_stay 的所在区县 district（和索引 idx_stay_district）、主理人 host_age / host_hometown / host_skills、
-- 出行参考 travel_pros / travel_cons / travel_airport / travel_rail 八列，删掉设施字典里的 study 学习室、gym 健身房。
-- 需要先停应用（新版本程序发现缺这八列拒绝启动），并显式 set @kg_allow_stay_district_travel_rollback = 1。
-- 还有房源填过区县、主理人信息或出行参考时会丢掉这些文字，要再显式 set @kg_allow_stay_district_travel_discard = 1 才放行。
-- 房源设施里已经勾了 study / gym 的不改（kg_stay.facilities 原样保留）：旧版本前端遇到不认识的设施代码直接不显示。
-- 回退后旧版本程序照常运行：列表位置只按商圈筛，详情页没有出行参考和主理人年龄 / 籍贯 / 擅长。
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

drop procedure if exists kg_rollback_stay_district_travel;
delimiter $$
create procedure kg_rollback_stay_district_travel()
begin
    if coalesce(@kg_allow_stay_district_travel_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V51 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 51) then
        signal sqlstate '45000' set message_text = 'V51 rollback blocked by later migrations';
    end if;
    if coalesce(@kg_allow_stay_district_travel_discard, 0) <> 1 then
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'travel_rail') then
            if exists (select 1 from kg_stay where travel_rail <> '') then
                signal sqlstate '45000' set message_text = 'V51 rollback blocked: stays still have districts / host details / travel notes';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'travel_airport') then
            if exists (select 1 from kg_stay where travel_airport <> '') then
                signal sqlstate '45000' set message_text = 'V51 rollback blocked: stays still have districts / host details / travel notes';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'travel_cons') then
            if exists (select 1 from kg_stay where travel_cons <> '') then
                signal sqlstate '45000' set message_text = 'V51 rollback blocked: stays still have districts / host details / travel notes';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'travel_pros') then
            if exists (select 1 from kg_stay where travel_pros <> '') then
                signal sqlstate '45000' set message_text = 'V51 rollback blocked: stays still have districts / host details / travel notes';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'host_skills') then
            if exists (select 1 from kg_stay where host_skills <> '') then
                signal sqlstate '45000' set message_text = 'V51 rollback blocked: stays still have districts / host details / travel notes';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'host_hometown') then
            if exists (select 1 from kg_stay where host_hometown <> '') then
                signal sqlstate '45000' set message_text = 'V51 rollback blocked: stays still have districts / host details / travel notes';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'host_age') then
            if exists (select 1 from kg_stay where host_age <> '') then
                signal sqlstate '45000' set message_text = 'V51 rollback blocked: stays still have districts / host details / travel notes';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'district') then
            if exists (select 1 from kg_stay where district <> '') then
                signal sqlstate '45000' set message_text = 'V51 rollback blocked: stays still have districts / host details / travel notes';
            end if;
        end if;
    end if;
    call kg_drop_idx_if_present('kg_stay', 'idx_stay_district');
    call kg_drop_col_if_present('kg_stay', 'travel_rail');
    call kg_drop_col_if_present('kg_stay', 'travel_airport');
    call kg_drop_col_if_present('kg_stay', 'travel_cons');
    call kg_drop_col_if_present('kg_stay', 'travel_pros');
    call kg_drop_col_if_present('kg_stay', 'host_skills');
    call kg_drop_col_if_present('kg_stay', 'host_hometown');
    call kg_drop_col_if_present('kg_stay', 'host_age');
    call kg_drop_col_if_present('kg_stay', 'district');
    delete from sys_dict_data where dict_type = 'kg_stay_facility' and dict_value in ('study', 'gym');
    delete from kg_schema_version where version = 51;
end$$
delimiter ;
call kg_rollback_stay_district_travel();
drop procedure kg_rollback_stay_district_travel;

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
