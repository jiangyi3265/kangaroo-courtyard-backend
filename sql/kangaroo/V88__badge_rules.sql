-- =============================================================================
-- V88__badge_rules（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('79,81,87');

create table if not exists kg_badge_integrity (
 app_user_id bigint not null,
 credit_score int not null default 100,
 referral_fraud char(1) not null default '0',
 false_content char(1) not null default '0',
 update_by varchar(64) null,
 update_time datetime not null default current_timestamp,
 primary key(app_user_id)
) engine=InnoDB default charset=utf8mb4;

create table if not exists kg_badge_content_check (
 content_type varchar(16) not null,
 content_id bigint not null,
 app_user_id bigint not null,
 quality char(1) not null default '0',
 featured char(1) not null default '0',
 content_digest char(64) not null,
 note varchar(500) not null,
 checked_by varchar(64) not null,
 checked_time datetime not null default current_timestamp,
 primary key(content_type,content_id),
 key idx_badge_content_user(app_user_id,content_type)
) engine=InnoDB default charset=utf8mb4;

create table if not exists kg_badge_violation (
 violation_id bigint not null auto_increment,
 app_user_id bigint not null,
 violation_type varchar(24) not null,
 target_type varchar(16) not null,
 target_id bigint not null,
 credit_deduction int not null default 0,
 note varchar(500) not null,
 created_by varchar(64) not null,
 create_time datetime not null default current_timestamp,
 primary key(violation_id),
 unique key uk_badge_violation(app_user_id,violation_type,target_type,target_id)
) engine=InnoDB default charset=utf8mb4;

create table if not exists kg_badge_quarter_review (
 app_user_id bigint not null,
 badge_code varchar(40) not null,
 quarter_start date not null,
 qualified char(1) not null,
 metrics_json text not null,
 evaluated_at datetime not null default current_timestamp,
 primary key(app_user_id,badge_code,quarter_start)
) engine=InnoDB default charset=utf8mb4;

-- Match the business tables in both existing installations and isolated test databases.
drop procedure if exists kg_badge_align_collation;
delimiter $$
create procedure kg_badge_align_collation(in table_name_value varchar(64))
begin
 declare business_collation varchar(64);
 declare current_collation varchar(64);
 set business_collation=(select table_collation from information_schema.tables where table_schema=database() and table_name='kg_application');
 set current_collation=(select table_collation from information_schema.tables where table_schema=database() and table_name=table_name_value);
 if business_collation is not null and current_collation<>business_collation then
  set @kg_s=concat('alter table `',table_name_value,'` convert to character set utf8mb4 collate ',business_collation);
  prepare kg_st from @kg_s;
  execute kg_st;
  deallocate prepare kg_st;
 end if;
end$$
delimiter ;
call kg_badge_align_collation('kg_badge_integrity');
call kg_badge_align_collation('kg_badge_content_check');
call kg_badge_align_collation('kg_badge_violation');
call kg_badge_align_collation('kg_badge_quarter_review');
drop procedure kg_badge_align_collation;
call kg_assert_same_collation('kg_badge_integrity');
call kg_assert_same_collation('kg_badge_content_check');
call kg_assert_same_collation('kg_badge_violation');
call kg_assert_same_collation('kg_badge_quarter_review');

call kg_add_col_if_absent('kg_mediation_vote','city_adopted','city_adopted char(1) null');
call kg_add_col_if_absent('kg_mediation_vote','adopted_by','adopted_by varchar(64) null');
call kg_add_col_if_absent('kg_mediation_vote','adopted_at','adopted_at datetime null');
call kg_add_col_if_absent('kg_mediation_vote','assessment_kind','assessment_kind varchar(24) null');
call kg_add_col_if_absent('kg_mediation_round','core_priority','core_priority char(1) not null default ''0''');
call kg_add_col_if_absent('kg_badge_log','related_log_id','related_log_id bigint null');
call kg_add_col_if_absent('kg_badge_log','credit_delta','credit_delta int null');
call kg_add_col_if_absent('kg_badge_log','related_vote_id','related_vote_id bigint null');
call kg_add_idx_if_absent('kg_badge_log','uk_badge_log_related_action','unique key uk_badge_log_related_action(related_log_id,action)');
call kg_add_idx_if_absent('kg_badge_log','uk_badge_log_vote_action','unique key uk_badge_log_vote_action(app_user_id,action,related_vote_id)');
call kg_add_col_if_absent('kg_badge_award','rule_version','rule_version int not null default 0');
call kg_add_col_if_absent('kg_application','offboard_time','offboard_time datetime null');
call kg_add_col_if_absent('kg_application','offboard_by','offboard_by varchar(64) null');
call kg_add_col_if_absent('kg_application','offboard_note','offboard_note varchar(500) null');

-- Carry forward known credit deductions. Replaying the migration never resets a score.
insert ignore into kg_badge_integrity(app_user_id,credit_score,update_by)
select app_user_id,least(100,greatest(0,credit_score)),'migration:V88' from kg_mediation_juror;

update kg_badge_def set threshold_value=null,rule_text='累计推荐注册并完成首次换工、兼职或住宿体验满10人，或累计推荐注册满20人且其中满5人完成体验。信用分至少80分，无刷推荐记录。长期有效。核实刷推荐或虚假推荐后撤销，并扣回对应奖励。'
where code='referrer';
update kg_badge_def set threshold_value=null,rule_text='累计推荐完成首次体验满50人，或本季度推荐完成首次体验满20人且排名前10，或推荐用户中至少10人已成为换工、兼职或全职活跃用户，满足其中任意一项。信用分至少90分，无违规。按自然季度评估，连续两个季度不达标降为推荐官。'
where code='referrer_gold';
update kg_badge_def set threshold_value=null,rule_text='有效参与大众评审满20次且优质评审满5次，或个人意见被城市负责人采纳的比例至少70%且有效参与满10次。无泄露、偏袒和缺席记录。长期有效。泄露评审内容、连续3次敷衍投票或核实偏袒后撤销并暂停资格。'
where code='juror_gold';
update kg_badge_def set threshold_value=null,rule_text='满足以下任一条件。完成换工、兼职、全职或住宿体验满6次，且优质内容满10条、其中精华满3条。累计旅居满60天，且优质内容满10条、其中精华满3条。完成体验满3次，且内容获官方转发或本周热门满3次。信用分至少85分，无虚假内容记录。长期有效。虚假或刷内容后撤销并扣回对应积分。'
where code='traveler';

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
values (88, 'badge_rules', now(), 'd9fac196a80452a09cc75dc3082a0ed6d2ec50d2c1723f22227c91a9ced01806')
on duplicate key update name = 'badge_rules', applied_time = now(), checksum = 'd9fac196a80452a09cc75dc3082a0ed6d2ec50d2c1723f22227c91a9ced01806';
