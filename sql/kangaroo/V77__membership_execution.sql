-- =============================================================================
-- V77__membership_execution（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('76');

-- Membership execution. Existing points and completed historical services are preserved.
create table if not exists kg_member_execution (
 id bigint primary key, enabled char(1) not null default '1', starts_at datetime not null
) engine=innodb;
insert ignore into kg_member_execution values(1,'1',now());

create table if not exists kg_member_wallet (
 wallet_id bigint primary key auto_increment,
 app_user_id bigint not null, issuer_user_id bigint not null,
 entitlement_year int not null, benefit_code varchar(24) not null,
 source varchar(16) not null, source_key varchar(96) not null,
 points_cost int not null default 0, amount_fen bigint not null default 0,
 status varchar(16) not null default 'available', order_id bigint null,
 used_by varchar(64) null, used_time datetime null,
 expires_at datetime not null, create_time datetime not null default current_timestamp,
 unique key uk_member_wallet_source(source_key), key idx_member_wallet_user(app_user_id,entitlement_year,status), key idx_member_wallet_order(order_id)
) engine=innodb;
create table if not exists kg_member_request (
 request_id bigint primary key auto_increment, app_user_id bigint not null,
 wallet_id bigint null, request_key varchar(64) not null, request_type varchar(20) not null,
 status varchar(16) not null default 'pending', content varchar(1000) not null,
 proof varchar(1000) null, review_note varchar(500) null,
 reviewed_by varchar(64) null, reviewed_time datetime null, create_time datetime not null default current_timestamp,
 unique key uk_member_request(app_user_id,request_key), key idx_member_request_status(status,request_id)
) engine=innodb;
create table if not exists kg_member_gift (
 gift_id bigint primary key auto_increment, app_user_id bigint not null, recipient_id bigint not null,
 entitlement_year int not null, nights int not null, create_time datetime not null default current_timestamp,
 unique key uk_member_gift(app_user_id,entitlement_year)
) engine=innodb;
create table if not exists kg_member_calendar_year (
 calendar_year int primary key, confirmed char(1) not null default '0', update_by varchar(64), update_time datetime
) engine=innodb;
create table if not exists kg_member_holiday (
 holiday_date date primary key, title varchar(80) not null, create_by varchar(64), create_time datetime not null default current_timestamp
) engine=innodb;
-- 2026 national holiday calendar, 国办发明电〔2025〕7号.
-- Source: https://www.beijing.gov.cn/cs/gncs/zcwj/202603/t20260327_4568275.html
-- Seed only a new year record; never overwrite an administrator's calendar.
set @kg_seed_member_2026=(select count(*)=0 from kg_member_calendar_year where calendar_year=2026);
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-01-01','元旦','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-01-02','元旦','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-01-03','元旦','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-02-15','春节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-02-16','春节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-02-17','春节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-02-18','春节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-02-19','春节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-02-20','春节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-02-21','春节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-02-22','春节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-02-23','春节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-04-04','清明节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-04-05','清明节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-04-06','清明节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-05-01','劳动节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-05-02','劳动节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-05-03','劳动节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-05-04','劳动节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-05-05','劳动节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-06-19','端午节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-06-20','端午节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-06-21','端午节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-09-25','中秋节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-09-26','中秋节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-09-27','中秋节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-10-01','国庆节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-10-02','国庆节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-10-03','国庆节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-10-04','国庆节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-10-05','国庆节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-10-06','国庆节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_holiday(holiday_date,title,create_by) select '2026-10-07','国庆节','official:2026' where @kg_seed_member_2026=1;
insert ignore into kg_member_calendar_year(calendar_year,confirmed,update_by,update_time) values(2026,'1','official:2026',now());

create table if not exists kg_member_service_completion (
 completion_id bigint primary key auto_increment, biz_type varchar(24) not null, biz_id bigint not null,
 app_user_id bigint not null, actual_days int not null default 0, proof varchar(1000) not null,
 completed_by varchar(64) not null, completed_time datetime not null default current_timestamp,
 unique key uk_member_completion(biz_type,biz_id)
) engine=innodb;
create table if not exists kg_member_review_check (
 review_id bigint primary key, quality char(1) not null default '0', reason varchar(500) not null,
 image_verified char(1) not null default '0', image_note varchar(500) null,
 checked_by varchar(64) null, checked_time datetime, revoked char(1) not null default '0', archived char(1) not null default '0'
) engine=innodb;
create table if not exists kg_member_pin (
 pin_id bigint primary key auto_increment, app_user_id bigint not null, post_id bigint not null,
 week_start date not null, starts_at datetime not null, ends_at datetime not null,
 request_key varchar(64) not null, unique key uk_member_pin(app_user_id,request_key), key idx_member_pin_week(app_user_id,week_start), key idx_member_pin_post(post_id,ends_at)
) engine=innodb;
create table if not exists kg_member_hot (
 hot_id bigint primary key auto_increment, week_start date not null, post_id bigint not null, score decimal(12,6) not null,
 unique key uk_member_hot(week_start,post_id)
) engine=innodb;
create table if not exists kg_member_case (
 case_id bigint primary key auto_increment, app_user_id bigint not null, severity int not null, review_id bigint null,
 facts varchar(2000) not null, status varchar(20) not null default 'notified', appeal varchar(2000) null,
 response varchar(2000) null, created_by varchar(64) not null, create_time datetime not null default current_timestamp,
 appeal_deadline datetime not null, executed_at datetime null, reviewed_by varchar(64) null, reviewed_time datetime null,
 key idx_member_case_due(status,appeal_deadline),key idx_member_case_user(app_user_id)
) engine=innodb;
create table if not exists kg_member_restriction (
 app_user_id bigint primary key, points_blocked_until datetime null, reviews_blocked_until datetime null,
 frozen char(1) not null default '0', level_penalty int not null default 0
) engine=innodb;
call kg_add_col_if_absent('kg_stay_room_type','member_room_code','member_room_code varchar(16) null comment ''空或auto默认开放并按房型匹配；night4/night2/night1指定；disabled停用''');
call kg_add_col_if_absent('kg_stay_room_type','member_price_fen','member_price_fen bigint null comment ''会员底价（分）''');
call kg_add_col_if_absent('kg_activity','member_training','member_training char(1) not null default ''0'' comment ''全职培训''');
call kg_add_col_if_absent('kg_activity','member_free','member_free char(1) not null default ''0'' comment ''可用会员免费活动权益''');
call kg_add_col_if_absent('kg_post','official_repost_time','official_repost_time datetime null');
call kg_add_col_if_absent('kg_post','official_repost_by','official_repost_by varchar(64) null');
call kg_add_col_if_absent('kg_stay_order','member_discount_fen','member_discount_fen bigint not null default 0');
call kg_add_col_if_absent('kg_stay_order','member_mode','member_mode varchar(16) null');
call kg_add_col_if_absent('kg_activity_signup','member_wallet_id','member_wallet_id bigint null');
create table if not exists kg_member_audit (
 audit_id bigint primary key auto_increment, action varchar(40) not null,
 operator varchar(64) not null, target_id bigint null, note varchar(500) not null,
 create_time datetime not null default current_timestamp
) engine=innodb;
call kg_assert_same_collation('kg_member_wallet');

-- Required reward provenance fields (existing values are preserved).
call kg_add_col_if_absent('kg_application', 'volunteer_done_time', 'volunteer_done_time datetime null comment ''义工完成时间（门店确认，或计划结束 3 天后系统自动完成）''');
call kg_add_col_if_absent('kg_application', 'volunteer_days', 'volunteer_days int null comment ''计积分的义工天数（门店填的实际天数，没填按报名天数，最多 180）''');
call kg_add_col_if_absent('kg_application', 'volunteer_done_by', 'volunteer_done_by varchar(64) null comment ''确认人：staff:员工账号ID / system 自动 / migration 上线前已结束（不计积分）''');
call kg_add_col_if_absent('kg_app_user', 'invite_code', 'invite_code varchar(8) null comment ''我的邀请码（第一次打开「我的邀请」时生成）''');
call kg_add_col_if_absent('kg_app_user', 'invited_by', 'invited_by bigint null comment ''邀请人（kg_app_user.user_id），只能绑定一次''');
call kg_add_col_if_absent('kg_app_user', 'invited_time', 'invited_time datetime null comment ''绑定邀请人的时间''');
call kg_add_col_if_absent('kg_member_review_check','revoke_base','revoke_base char(1) not null default ''0''');
call kg_add_col_if_absent('kg_stay_order','checked_out_time','checked_out_time datetime null');
call kg_add_col_if_absent('kg_member_benefit','intro','intro text null comment ''合作店介绍''');
call kg_assert_same_collation('kg_member_execution');
call kg_assert_same_collation('kg_member_request');
call kg_assert_same_collation('kg_member_gift');
call kg_assert_same_collation('kg_member_calendar_year');
call kg_assert_same_collation('kg_member_holiday');
call kg_assert_same_collation('kg_member_service_completion');
call kg_assert_same_collation('kg_member_review_check');
call kg_assert_same_collation('kg_member_pin');
call kg_assert_same_collation('kg_member_hot');
call kg_assert_same_collation('kg_member_case');
call kg_assert_same_collation('kg_member_restriction');
call kg_assert_same_collation('kg_member_audit');

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
values (77, 'membership_execution', now(), 'e941ac869bfd521a17c09ab781312d475bc6163c01b4f8aa0b454f6e464177b0')
on duplicate key update name = 'membership_execution', applied_time = now(), checksum = 'e941ac869bfd521a17c09ab781312d475bc6163c01b4f8aa0b454f6e464177b0';
