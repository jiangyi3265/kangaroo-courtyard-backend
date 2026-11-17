-- =============================================================================
-- V105__operations_reports（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('104');

-- 日报是独立的核对快照，不重复写入原收支账本，不产生付款。
create table if not exists kg_ops_daily (
 report_id bigint not null auto_increment,
 owner_id bigint not null,
 city_id bigint not null,
 role_code varchar(16) not null,
 biz_date date not null,
 store_ids text not null,
 owner_name varchar(64) not null,
 city_name varchar(64) not null,
 window_start datetime not null,
 window_end datetime not null,
 data_cutoff datetime not null,
 final_data tinyint not null default 0,
 auto_json mediumtext not null,
 manual_json mediumtext not null,
 progress_json mediumtext not null,
 summary_text text not null,
 status varchar(16) not null default 'draft',
 revision bigint not null default 0,
 submitted_at datetime null,
 create_time datetime not null,
 update_time datetime not null,
 primary key(report_id),
 unique key uk_ops_daily(owner_id,city_id,role_code,biz_date),
 key idx_ops_daily_city(city_id,biz_date,status),
 key idx_ops_daily_due(biz_date,status,final_data)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
create table if not exists kg_ops_target (
 target_id bigint not null auto_increment,
 month_key char(7) not null,
 city_id bigint not null comment '0为全国模板，仅全部城市账号可管理',
 role_code varchar(16) not null,
 owner_id bigint not null default 0 comment '0为地区团队目标，非0为指定主理人',
 metric_code varchar(48) not null,
 target_value decimal(14,2) not null,
 normal_ratio int not null default 80,
 slow_ratio int not null default 50,
 revision bigint not null default 0,
 create_by varchar(64) not null,
 create_time datetime not null,
 update_by varchar(64) not null,
 update_time datetime not null,
 primary key(target_id),
 unique key uk_ops_target(month_key,city_id,role_code,owner_id,metric_code),
 key idx_ops_target_city(city_id,month_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
create table if not exists kg_ops_monthly (
 summary_id bigint not null auto_increment,
 month_key char(7) not null,
 city_id bigint not null,
 role_code varchar(16) not null,
 owner_id bigint not null default 0,
 owner_name varchar(64) not null default '',
 city_name varchar(64) not null,
 store_ids text not null,
 summary_json mediumtext not null,
 generated_at datetime not null,
 primary key(summary_id),
 unique key uk_ops_monthly(month_key,city_id,role_code,owner_id),
 key idx_ops_monthly_city(city_id,month_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
create table if not exists kg_ops_notice (
 notice_id bigint not null auto_increment,
 city_id bigint not null,
 recipient_id bigint not null,
 event_key varchar(160) not null,
 message_id bigint null,
 create_time datetime not null,
 primary key(notice_id),
 unique key uk_ops_notice(event_key,recipient_id),
 key idx_ops_notice_city(city_id,create_time)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
create table if not exists kg_ops_result (
 result_id bigint not null auto_increment,
 store_id bigint not null,
 metric_code varchar(48) not null,
 occurred_at datetime not null,
 evidence varchar(1000) not null,
 status char(1) not null default '0',
 revision bigint not null default 0,
 create_by varchar(64) not null,
 create_time datetime not null,
 update_by varchar(64) not null,
 update_time datetime not null,
 primary key(result_id),
 unique key uk_ops_result(store_id,metric_code),
 key idx_ops_result_time(occurred_at,status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
call kg_assert_same_collation('kg_ops_daily');
call kg_assert_same_collation('kg_ops_target');
call kg_assert_same_collation('kg_ops_monthly');
call kg_assert_same_collation('kg_ops_notice');
call kg_assert_same_collation('kg_ops_result');
call kg_add_col_if_absent('kg_interview','staff_result','staff_result varchar(16) null comment ''员工记录的面试结果 pass/reject/pending；空为未记录''');
call kg_add_col_if_absent('kg_interview','staff_result_time','staff_result_time datetime null comment ''面试结果最近确认时间''');
call kg_add_col_if_absent('kg_activity','first_published_time','first_published_time datetime null comment ''首次实际发布；旧活动由create_time回填''');
update kg_activity set first_published_time=create_time where first_published_time is null and status in ('open','paused','closed','ended') and del_flag='0';
insert into sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '日报与月度任务',coalesce((select menu_id from (select menu_id from sys_menu where path='kangaroo' limit 1) p),0),108,'operations-reports','kangaroo/operations/index',1,0,'C','0','0','kangaroo:operations:query','chart','migration',now()
where not exists(select 1 from sys_menu where perms='kangaroo:operations:query');
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '设置月度目标及登记结果',menu_id,1,'#',1,0,'F','0','0','kangaroo:operations:edit','#','migration',now() from sys_menu where perms='kangaroo:operations:query'
and not exists(select 1 from (select menu_id from sys_menu where perms='kangaroo:operations:edit') p);
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '导出日报和月度总结',menu_id,2,'#',1,0,'F','0','0','kangaroo:operations:export','#','migration',now() from sys_menu where perms='kangaroo:operations:query'
and not exists(select 1 from (select menu_id from sys_menu where perms='kangaroo:operations:export') p);

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
values (105, 'operations_reports', now(), '44b2236c486c114d08ea2490eef6546f4acf86f973a39c029fb5409dd7189f26')
on duplicate key update name = 'operations_reports', applied_time = now(), checksum = '44b2236c486c114d08ea2490eef6546f4acf86f973a39c029fb5409dd7189f26';
