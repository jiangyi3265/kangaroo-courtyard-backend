-- =============================================================================
-- V87__wish（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('86');

create table if not exists kg_wish (
 wish_id bigint not null auto_increment, publisher_id bigint not null, helper_id bigint null,
 request_key varchar(64) not null, title varchar(80) not null, description varchar(2000) not null,
 category varchar(24) not null, origin_city varchar(80) not null, target_city varchar(80) not null,
 delivery varchar(20) not null, deadline date not null, images varchar(2000) not null default '',
 mode varchar(10) not null, cost_fen bigint not null, markup_fen bigint not null,
 amount_fen bigint not null, fee_fen bigint not null, net_fen bigint not null,
 status varchar(24) not null, pay_no varchar(64) null, expire_time datetime null,
 proof varchar(2000) not null default '', delivery_note varchar(500) not null default '',
 rating int null, review varchar(500) not null default '', dispute varchar(500) not null default '', audit_note varchar(500) not null default '',
 payout_status varchar(20) not null default 'none', payout_receipt varchar(100) not null default '',
 create_time datetime not null default current_timestamp, update_time datetime not null default current_timestamp on update current_timestamp,
 completed_time datetime null, paid_out_time datetime null,
 primary key(wish_id), unique key uk_wish_request(publisher_id,request_key),
 key idx_wish_discovery(status,target_city,category), key idx_wish_helper(helper_id,status), key idx_wish_owner(publisher_id,status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
create table if not exists kg_wish_credential (
 credential_id bigint not null auto_increment, user_id bigint not null, type varchar(24) not null,
 city varchar(80) not null default '', file_key varchar(255) not null, status varchar(20) not null default 'pending',
 note varchar(500) not null default '', reviewer varchar(64) not null default '',
 create_time datetime not null default current_timestamp, update_time datetime not null default current_timestamp on update current_timestamp,
 primary key(credential_id), key idx_wish_credential_user(user_id,type,status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
create table if not exists kg_wish_message (
 message_id bigint not null auto_increment, wish_id bigint not null, user_id bigint not null,
 request_key varchar(64) not null, content varchar(1000) not null,
 create_time datetime not null default current_timestamp,
 primary key(message_id), unique key uk_wish_message(user_id,request_key), key idx_wish_messages(wish_id,message_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
create table if not exists kg_wish_audit (
 audit_id bigint not null auto_increment, wish_id bigint null, credential_id bigint null,
 actor varchar(80) not null, action varchar(40) not null, note varchar(500) not null default '',
 create_time datetime not null default current_timestamp, primary key(audit_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
create table if not exists kg_wish_payout (
 payout_id bigint not null auto_increment, wish_id bigint not null, helper_id bigint not null,
 amount_fen bigint not null, receipt varchar(100) not null, credential_id bigint not null,
 operator varchar(64) not null, create_time datetime not null default current_timestamp,
 primary key(payout_id), unique key uk_wish_payout(wish_id), unique key uk_wish_receipt(receipt)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
insert into sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '心愿互助', coalesce((select menu_id from (select menu_id from sys_menu where path='kangaroo' limit 1) m),0),87,'wishes','kangaroo/wishes/index',1,0,'C','0','0','kangaroo:wish:list','peoples','admin',now()
where not exists(select 1 from sys_menu where perms='kangaroo:wish:list');
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '心愿审核与结算',menu_id,1,'',1,0,'F','0','0','kangaroo:wish:edit','#','admin',now() from sys_menu
where perms='kangaroo:wish:list' and not exists(select 1 from (select perms from sys_menu) x where x.perms='kangaroo:wish:edit');

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
values (87, 'wish', now(), '3667dc94e057f51630831a9ee633f5002c29598ed95cfc9d058c10df25d38f74')
on duplicate key update name = 'wish', applied_time = now(), checksum = '3667dc94e057f51630831a9ee633f5002c29598ed95cfc9d058c10df25d38f74';
