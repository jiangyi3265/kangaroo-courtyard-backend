-- =============================================================================
-- V108__operations_forms（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('107');

create table if not exists kg_ops_form (
 role_code varchar(16) not null primary key, revision bigint not null default 0,
 form_json longtext null, update_by varchar(64), update_time datetime null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
insert ignore into kg_ops_form(role_code) values ('keeper'),('manager');
create table if not exists kg_ops_form_change (
 change_id bigint not null auto_increment primary key, role_code varchar(16) not null, revision bigint not null,
 operator_id bigint not null, operator_name varchar(64) not null, reason varchar(300) not null,
 before_json longtext not null, after_json longtext not null, create_time datetime not null,
 unique key uk_ops_form_change(role_code,revision)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
create table if not exists kg_ops_daily_change (
 change_id bigint not null auto_increment primary key, report_id bigint not null, city_id bigint not null,
 request_id varchar(36) not null, request_json longtext not null,
 operator_id bigint not null, operator_name varchar(64) not null, reason varchar(300) not null,
 from_revision bigint not null, to_revision bigint not null, before_json longtext not null, after_json longtext not null, form_json longtext not null, create_time datetime not null,
 unique key uk_ops_daily_change(report_id,request_id), unique key uk_ops_daily_change_revision(report_id,to_revision), key idx_ops_daily_change_city(city_id,report_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
call kg_add_col_if_absent('kg_ops_daily','form_json','form_json longtext null');
call kg_add_col_if_absent('kg_ops_daily','corrected_at','corrected_at datetime null');
call kg_add_col_if_absent('kg_ops_daily','corrected_by','corrected_by varchar(64) null');
call kg_add_col_if_absent('kg_ops_daily','correction_reason','correction_reason varchar(300) null');
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) select '修改员工日报',menu_id,4,'#',1,0,'F','0','0','kangaroo:operations:correct','#','migration',now() from sys_menu where perms='kangaroo:operations:query' and not exists(select 1 from (select menu_id from sys_menu where perms='kangaroo:operations:correct') p);
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) select '配置日报填写项目',menu_id,4,'#',1,0,'F','0','0','kangaroo:operations:form','#','migration',now() from sys_menu where perms='kangaroo:operations:query' and not exists(select 1 from (select menu_id from sys_menu where perms='kangaroo:operations:form') p);

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
values (108, 'operations_forms', now(), '2b313fafdad9c61ab8de9c7d17fe50c6b60bcd35b7a8821eb627663098fb07ca')
on duplicate key update name = 'operations_forms', applied_time = now(), checksum = '2b313fafdad9c61ab8de9c7d17fe50c6b60bcd35b7a8821eb627663098fb07ca';
