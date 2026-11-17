-- =============================================================================
-- V79__governance（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('77');

-- Dedicated user achievements and order-bound mediation. No changes to staff honor records.
create table if not exists kg_badge_def (
 code varchar(40) primary key, name varchar(64) not null, category varchar(24) not null,
 rule_text varchar(500) not null, metric varchar(32) not null default 'manual', threshold_value int null,
 tone varchar(16) not null default 'gold', enabled char(1) not null default '1', sort_order int not null default 0
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_badge_award (
 award_id bigint primary key auto_increment, app_user_id bigint not null, badge_code varchar(40) not null,
 source_key varchar(120) not null, source_note varchar(500) not null, city varchar(64) null,
 status varchar(16) not null default 'active', visible char(1) not null default '1', worn char(1) not null default '0',
 awarded_by varchar(64) not null, awarded_at datetime not null default current_timestamp, expires_at datetime null,
 unique key uk_badge_source(app_user_id,badge_code,source_key), key idx_badge_user(app_user_id,status),
 key idx_badge_city(badge_code,city,status)
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_badge_log (
 log_id bigint primary key auto_increment, award_id bigint null, app_user_id bigint not null,
 actor varchar(64) not null, action varchar(32) not null, note varchar(500) not null, create_time datetime not null default current_timestamp
) engine=InnoDB default charset=utf8mb4;
insert ignore into kg_badge_def(code,name,category,rule_text,metric,threshold_value,tone,sort_order) values
 ('review_gold','金牌点评鼠','评价贡献','至少一条经过核验的精华评价','quality',1,'gold',10),
 ('review_ace','袋鼠王牌点评官','评价贡献','累计十条经过核验的精华评价','quality',10,'gold',11),
 ('recorder','袋鼠记录官','评价贡献','连续三个自然月，每月至少五条有效反馈，含评价、意见箱与社区动态','recorder',3,'green',12),
 ('review_annual','年度最佳点评鼠','评价贡献','年度精华评价数量评选，平台核验后颁发','manual',null,'gold',13),
 ('referrer','袋鼠推荐官','推荐共建','推荐的朋友完成有效服务或培训，门槛由平台设置','referral',null,'green',20),
 ('referrer_gold','袋鼠金牌推荐官','推荐共建','推荐成绩突出，门槛由平台设置','referral',null,'gold',21),
 ('city_builder','城市共建人','推荐共建','平台核验城市共建身份后颁发','manual',null,'green',22),
 ('campus','校园大使','校园城市','经平台确认的校园大使','manual',null,'blue',30),
 ('campus_council','高校议事小组成员','校园城市','经平台确认的高校议事小组成员','manual',null,'blue',31),
 ('city_youth','城市青年主理人','校园城市','每城一位，由平台确认任命','manual',null,'gold',32),
 ('juror','大众评审员','社区治理','加入评审池并完成至少一次有效评议','jury',1,'blue',40),
 ('juror_gold','金牌评审员','社区治理','多次获得优质评审，门槛由平台设置','jury_quality',null,'gold',41),
 ('creator','袋鼠创作者','内容旅行','优质社区内容被官方转发','repost',1,'green',50),
 ('hot_author','本周热门作者','内容旅行','作品获本周热门评选，展示有效期为一周','hot',1,'gold',51),
 ('traveler','袋鼠旅行家','内容旅行','完成多次义工或旅居并产出优质内容，次数由平台设置','travel',null,'blue',52),
 ('annual_star','年度之星','特殊荣誉','平台年度综合评选，保留评选依据','manual',null,'gold',60),
 ('annual_person','年度人物','特殊荣誉','平台年度综合评选，保留评选依据','manual',null,'gold',61),
 ('gentle','温柔奖','特殊荣誉','平台关怀荣誉，可自行选择是否展示','manual',null,'pink',62),
 ('comfort','暖心纪念章','特殊荣誉','对应委屈奖纪念荣誉，可自行选择是否展示','manual',null,'pink',63);
create table if not exists kg_mediation_case (
 case_id bigint primary key auto_increment, case_no varchar(64) null, request_key varchar(64) not null,
 order_type varchar(24) not null, order_id bigint not null, order_no varchar(64) not null,
 user_id bigint not null, store_id bigint not null, city varchar(64) not null, initiator_id bigint not null,
 initiator_role varchar(16) not null, category varchar(40) not null, narrative text not null, claims text not null,
 incident_at datetime not null, urgent char(1) not null default '0', amount_fen bigint null, phone varchar(24) not null,
 status varchar(24) not null default 'pending', handler_id bigint null, accept_deadline datetime not null,
 accepted_at datetime null, verification text null, path varchar(16) null, decision text null, basis text null,
 measures text null, execute_deadline datetime null, credit_impact text null, jury_adopted char(1) null, jury_reason text null,
 user_confirm varchar(16) null, merchant_confirm varchar(16) null, appeal text null, hq_result text null,
 confirm_deadline datetime null, closed_at datetime null, create_time datetime not null default current_timestamp,
 update_time datetime not null default current_timestamp on update current_timestamp,
 unique key uk_mediation_request(initiator_id,request_key), unique key uk_mediation_no(case_no),
 key idx_mediation_party(user_id,store_id,status), key idx_mediation_city(city,status), key idx_mediation_deadline(status,accept_deadline)
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_mediation_file (
 file_id bigint primary key auto_increment, owner_actor varchar(64) not null, case_id bigint null,
 file_key varchar(180) not null, original_name varchar(120) not null, mime_type varchar(80) not null,
 size_bytes bigint not null, sha256 varchar(64) not null, redacted char(1) not null default '0',
 create_time datetime not null default current_timestamp, key idx_mediation_file_case(case_id,redacted)
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_mediation_log (
 log_id bigint primary key auto_increment, case_id bigint not null, actor varchar(64) not null,
 role_code varchar(24) not null, action varchar(32) not null, from_state varchar(24) not null, to_state varchar(24) not null,
 note text not null, audience varchar(16) not null default 'parties', create_time datetime not null default current_timestamp,
 key idx_mediation_log_case(case_id,log_id)
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_mediation_juror (
 app_user_id bigint primary key, kind varchar(16) not null, store_id bigint null, willing char(1) not null default '0',
 credit_score int not null default 100, violations int not null default 0, missed int not null default 0,
 suspended_until datetime null, disqualified char(1) not null default '0', update_time datetime not null default current_timestamp
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_mediation_round (
 round_id bigint primary key auto_increment, case_id bigint not null, anonymous_narrative text not null, anonymous_claims text not null,
 deadline datetime not null, status varchar(16) not null default 'open', result_json text null,
 create_time datetime not null default current_timestamp, unique key uk_mediation_round(case_id)
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_mediation_vote (
 vote_id bigint primary key auto_increment, round_id bigint not null, app_user_id bigint not null, juror_kind varchar(16) not null,
 choice varchar(24) null, reason varchar(1000) null, voted_at datetime null, quality char(1) not null default '0',
 assessed char(1) not null default '0', valid char(1) not null default '0', assessment_note varchar(500) null,
 unique key uk_mediation_vote(round_id,app_user_id), key idx_mediation_vote_user(app_user_id)
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_mediation_event (
 event_id bigint primary key auto_increment, case_id bigint not null, event_key varchar(160) not null,
 audience varchar(32) not null, content varchar(1000) not null, status varchar(32) not null default 'pending',
 create_time datetime not null default current_timestamp, unique key uk_mediation_event(event_key)
) engine=InnoDB default charset=utf8mb4;
-- Dedicated headquarters permission. Existing administrators obtain it through the standard role-menu UI.
insert into sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '调解与徽章', coalesce((select menu_id from (select menu_id from sys_menu where path='kangaroo' limit 1) m),0),98,'governance','kangaroo/governance/index',1,0,'C','0','0','kangaroo:mediation:query','peoples','migration',now()
where not exists(select 1 from sys_menu where perms='kangaroo:mediation:query');
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '总部复核与治理',menu_id,1,'#',1,0,'F','0','0','kangaroo:mediation:edit','#','migration',now() from sys_menu where perms='kangaroo:mediation:query' and not exists(select 1 from (select menu_id from sys_menu where perms='kangaroo:mediation:edit') e);

create table if not exists kg_badge_feedback (
 feedback_id bigint primary key auto_increment,app_user_id bigint not null,request_key varchar(64) not null,
 content varchar(2000) not null,status varchar(16) not null default 'pending',review_note varchar(500) null,
 reviewed_by varchar(64) null,create_time datetime not null default current_timestamp,
 unique key uk_badge_feedback(app_user_id,request_key)
) engine=InnoDB default charset=utf8mb4;

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
values (79, 'governance', now(), 'd139441fdd5fe0a3b52d7fec9423736d740e3bc0440c9e3e6f43a92e91e50239')
on duplicate key update name = 'governance', applied_time = now(), checksum = 'd139441fdd5fe0a3b52d7fec9423736d740e3bc0440c9e3e6f43a92e91e50239';
