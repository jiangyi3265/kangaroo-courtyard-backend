-- V54 回退：去掉门店页改版加的 kg_store 十六列（采访方式 interview_mode、采访视频 interview_video / interview_cover / interview_seconds / interview_note、
-- 店长 manager_name / manager_avatar / manager_years / manager_skills / manager_quote、该店亮点 facilities、交通参考 travel_rail / travel_airport / travel_cost、
-- 营业执照 license_image、店里日常 daily_images）。
-- 需要先停应用（新版本程序发现缺这些列拒绝启动），并显式 set @kg_allow_store_profile_rollback = 1。
-- 采访方式里的「实地探访」和 safe_cert = '0' 是同一件事，回退不丢（认证照旧在 safe_cert 里）；
-- 已经填过店长、亮点、交通参考、执照、日常照片、采访视频，或选过「线上采访」的门店，回退会丢掉这些（照片 / 视频文件本身留在 upload/ 下不删），
-- 默认拒绝；确认可以丢再显式 set @kg_allow_store_profile_loss = 1。
-- 回退后旧版本程序照常运行：岗位详情是原来的样子，门店只有安心认证。
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

drop procedure if exists kg_rollback_store_profile;
delimiter $$
create procedure kg_rollback_store_profile()
begin
    if coalesce(@kg_allow_store_profile_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V54 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 54) then
        signal sqlstate '45000' set message_text = 'V54 rollback blocked by later migrations';
    end if;
    if coalesce(@kg_allow_store_profile_loss, 0) <> 1 then
        set @kg_v54_used = 0;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'interview_mode') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where interview_mode = ''online''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'interview_video') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where interview_video <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'interview_cover') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where interview_cover <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'interview_note') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where interview_note <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'manager_name') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where manager_name <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'manager_avatar') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where manager_avatar <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'manager_years') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where manager_years <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'manager_skills') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where manager_skills <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'manager_quote') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where manager_quote <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'facilities') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where facilities <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'travel_rail') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where travel_rail <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'travel_airport') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where travel_airport <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'travel_cost') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where travel_cost <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'license_image') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where license_image <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'daily_images') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where daily_images <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_store' and column_name = 'interview_seconds') then
            set @kg_s = 'select count(*) into @kg_v54_used from kg_store where interview_seconds is not null';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v54_used > 0 then
                signal sqlstate '45000' set message_text = 'V54 rollback blocked: stores still have interview / manager / profile content (set @kg_allow_store_profile_loss = 1)';
            end if;
        end if;
    end if;
    call kg_drop_col_if_present('kg_store', 'daily_images');
    call kg_drop_col_if_present('kg_store', 'license_image');
    call kg_drop_col_if_present('kg_store', 'travel_cost');
    call kg_drop_col_if_present('kg_store', 'travel_airport');
    call kg_drop_col_if_present('kg_store', 'travel_rail');
    call kg_drop_col_if_present('kg_store', 'facilities');
    call kg_drop_col_if_present('kg_store', 'manager_quote');
    call kg_drop_col_if_present('kg_store', 'manager_skills');
    call kg_drop_col_if_present('kg_store', 'manager_years');
    call kg_drop_col_if_present('kg_store', 'manager_avatar');
    call kg_drop_col_if_present('kg_store', 'manager_name');
    call kg_drop_col_if_present('kg_store', 'interview_note');
    call kg_drop_col_if_present('kg_store', 'interview_seconds');
    call kg_drop_col_if_present('kg_store', 'interview_cover');
    call kg_drop_col_if_present('kg_store', 'interview_video');
    call kg_drop_col_if_present('kg_store', 'interview_mode');
    delete from kg_schema_version where version = 54;
end$$
delimiter ;
call kg_rollback_store_profile();
drop procedure kg_rollback_store_profile;

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
