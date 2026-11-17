-- =============================================================================
-- V111__insurance_payment（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('110');

-- Insurance principal is separate from commission/member balances. Historical applications remain unpaid legacy records.
create table if not exists kg_insurance_payment (
 apply_id bigint not null, payer_user_id bigint not null, store_id bigint not null,
 request_key varchar(80) not null, request_hash varchar(64) not null,
 pay_no varchar(64) null, amount bigint not null, method varchar(16) not null,
 status varchar(16) not null default 'pending', refund_amount bigint not null default 0,
 refund_required tinyint not null default 0, expire_time datetime not null,
 paid_at datetime null, created_at datetime not null default current_timestamp,
 primary key(apply_id), unique key uk_ins_payment_request(payer_user_id,request_key),
 unique key uk_ins_payment_pay(pay_no), key idx_ins_payment_status(status,expire_time),
 constraint ck_ins_payment_amount check(amount>0 and refund_amount>=0 and refund_amount<=amount)
) engine=innodb;
create table if not exists kg_insurance_account (
 user_id bigint not null, balance bigint not null default 0,
 updated_at datetime not null default current_timestamp,
 primary key(user_id), constraint ck_ins_account_balance check(balance>=0)
) engine=innodb;
create table if not exists kg_insurance_entry (
 entry_id bigint not null auto_increment, user_id bigint not null, business_key varchar(100) not null,
 apply_id bigint not null, refund_id bigint null, type varchar(24) not null,
 amount bigint not null, balance_after bigint not null, created_at datetime not null default current_timestamp,
 primary key(entry_id), unique key uk_ins_entry_business(business_key), key idx_ins_entry_user(user_id,entry_id)
) engine=innodb;
create table if not exists kg_insurance_refund (
 refund_id bigint not null auto_increment, apply_id bigint not null, store_id bigint not null,
 payer_user_id bigint not null, request_key varchar(80) not null, request_hash varchar(64) not null,
 amount bigint not null, approved_amount bigint null, destination varchar(16) not null,
 status varchar(16) not null default 'pending', prior_status varchar(16) not null,
 reason varchar(500) not null, handle_note varchar(500) null, handled_by varchar(64) null,
 channel_refund_no varchar(64) null, created_at datetime not null default current_timestamp,
 handled_at datetime null, completed_at datetime null,
 active_apply_id bigint generated always as (case when status in ('pending','approved','processing') then apply_id else null end) stored,
 primary key(refund_id), unique key uk_ins_refund_request(payer_user_id,request_key),
 unique key uk_ins_refund_active(active_apply_id), unique key uk_ins_refund_channel(channel_refund_no),
 key idx_ins_refund_apply(apply_id,refund_id), constraint ck_ins_refund_amount check(amount>0 and (approved_amount is null or (approved_amount>0 and approved_amount<=amount)))
) engine=innodb;
call kg_assert_same_collation('kg_insurance_payment');
call kg_assert_same_collation('kg_insurance_account');
call kg_assert_same_collation('kg_insurance_entry');
call kg_assert_same_collation('kg_insurance_refund');


create table if not exists kg_insurance_request (
 payer_user_id bigint not null, request_kind varchar(16) not null, request_key varchar(80) not null, request_hash varchar(64) not null,
 apply_id bigint null, refund_id bigint null, created_at datetime not null default current_timestamp,
 primary key(payer_user_id,request_kind,request_key), unique key uk_ins_request_apply(apply_id), unique key uk_ins_request_refund(refund_id)
) engine=innodb;
call kg_assert_same_collation('kg_insurance_request');

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
values (111, 'insurance_payment', now(), '4f4edc8d3570fdc184c6a9309fbff9fd4c64002a0b596d24a2a967fa65529d7f')
on duplicate key update name = 'insurance_payment', applied_time = now(), checksum = '4f4edc8d3570fdc184c6a9309fbff9fd4c64002a0b596d24a2a967fa65529d7f';
