-- =============================================================================
-- V81__merchant_settlement_delivery（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('80');
create table if not exists kg_merchant_settlement (
 settlement_id bigint primary key auto_increment,store_id bigint not null,fee_month varchar(7) not null,
 gross_fen bigint null,penalty_bp int not null default 0,deduction_fen bigint null,net_fen bigint null,
 penalty_case_id bigint null,basis varchar(2000) null,status varchar(16) not null default 'draft',
 prepared_by varchar(64) null,reviewed_by varchar(64) null,paid_by varchar(64) null,
 paid_proof varchar(500) null,paid_at datetime null,version bigint not null default 0,
 create_time datetime not null default current_timestamp,update_time datetime not null default current_timestamp,
 unique key uk_merchant_fee_month(store_id,fee_month)
) engine=InnoDB;
create table if not exists kg_merchant_settlement_log (
 log_id bigint primary key auto_increment,settlement_id bigint not null,action varchar(32) not null,
 actor varchar(64) not null,note text not null,create_time datetime not null default current_timestamp,
 key idx_merchant_fee_log(settlement_id,log_id)
) engine=InnoDB;
create table if not exists kg_merchant_case_review (
 case_id bigint not null,review_id bigint not null,member_case_id bigint null,
 primary key(case_id,review_id),key idx_merchant_review_member(member_case_id)
) engine=InnoDB;
create table if not exists kg_governance_sms (
 sms_id bigint primary key auto_increment,msg_id bigint not null,app_user_id bigint not null,biz_key varchar(32) not null,
 status varchar(24) not null default 'queued',template_id varchar(64) null,sdk_app_id varchar(64) null,
 serial_no varchar(128) null,phone varchar(20) null,detail varchar(255) null,
 sent_at datetime null,received_at datetime null,create_time datetime not null default current_timestamp,
 update_time datetime not null default current_timestamp,
 unique key uk_governance_sms_msg(msg_id),key idx_governance_sms_status(status,update_time)
) engine=InnoDB;
-- Historical confirmed sanctions become unpriced drafts; no money is invented.
insert ignore into kg_merchant_settlement(store_id,fee_month,penalty_case_id,penalty_bp)
 select store_id,fee_month,min(case_id),5000 from kg_merchant_case
 where fee_state='awaiting_settlement_basis' and fee_month is not null group by store_id,fee_month;
update kg_merchant_case c join kg_merchant_settlement f on f.store_id=c.store_id and cast(f.fee_month as binary)=cast(c.fee_month as binary)
 set c.fee_state='settlement_linked' where c.fee_state='awaiting_settlement_basis';
call kg_assert_same_collation('kg_merchant_settlement');
call kg_assert_same_collation('kg_merchant_settlement_log');
call kg_assert_same_collation('kg_merchant_case_review');
call kg_assert_same_collation('kg_governance_sms');
-- HQ finance and delivery permissions are independent from adjudication.
insert into sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '商家分成结算',coalesce((select menu_id from (select menu_id from sys_menu where path='kangaroo' limit 1) m),0),99,'merchantFinance','kangaroo/governance/Finance',1,0,'C','0','0','kangaroo:merchantfinance:query','money','migration',now()
where not exists(select 1 from sys_menu where perms='kangaroo:merchantfinance:query');
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '分成录入复核与付款登记',menu_id,1,'#',1,0,'F','0','0','kangaroo:merchantfinance:edit','#','migration',now() from sys_menu where perms='kangaroo:merchantfinance:query' and not exists(select 1 from (select menu_id from sys_menu where perms='kangaroo:merchantfinance:edit') e);
insert into sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '通知送达记录',coalesce((select menu_id from (select menu_id from sys_menu where path='kangaroo' limit 1) m),0),100,'deliveries','kangaroo/governance/Deliveries',1,0,'C','0','0','kangaroo:delivery:query','message','migration',now()
where not exists(select 1 from sys_menu where perms='kangaroo:delivery:query');
-- No user/role assignment and no production prices are changed by this migration.
insert into sys_role(role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,remark) select '会员权益管理员','kg_member_ops',99,'1','0','0','migration',now(),'专用权限角色；须由管理员分配到指定后台账号，不自动分配用户' where not exists(select 1 from sys_role where role_key='kg_member_ops');
insert ignore into sys_role_menu(role_id,menu_id) select r.role_id,m.menu_id from sys_role r join sys_menu m on m.perms in ('kangaroo:memberlevel:query','kangaroo:memberlevel:edit','kangaroo:delivery:query') where r.role_key='kg_member_ops' and r.del_flag='0';
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.parent_id from (select role_id,menu_id from sys_role_menu) rm join sys_role r on r.role_id=rm.role_id join sys_menu m on m.menu_id=rm.menu_id join sys_menu parent on parent.menu_id=m.parent_id and parent.menu_type='M' where r.role_key='kg_member_ops';
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.parent_id from (select role_id,menu_id from sys_role_menu) rm join sys_role r on r.role_id=rm.role_id join sys_menu m on m.menu_id=rm.menu_id join sys_menu parent on parent.menu_id=m.parent_id and parent.menu_type='M' where r.role_key='kg_member_ops';
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.parent_id from (select role_id,menu_id from sys_role_menu) rm join sys_role r on r.role_id=rm.role_id join sys_menu m on m.menu_id=rm.menu_id join sys_menu parent on parent.menu_id=m.parent_id and parent.menu_type='M' where r.role_key='kg_member_ops';
insert into sys_role(role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,remark) select '纠纷与合作复核员','kg_mediation_ops',99,'1','0','0','migration',now(),'专用权限角色；须由管理员分配到指定后台账号，不自动分配用户' where not exists(select 1 from sys_role where role_key='kg_mediation_ops');
insert ignore into sys_role_menu(role_id,menu_id) select r.role_id,m.menu_id from sys_role r join sys_menu m on m.perms in ('kangaroo:mediation:query','kangaroo:mediation:edit','kangaroo:delivery:query') where r.role_key='kg_mediation_ops' and r.del_flag='0';
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.parent_id from (select role_id,menu_id from sys_role_menu) rm join sys_role r on r.role_id=rm.role_id join sys_menu m on m.menu_id=rm.menu_id join sys_menu parent on parent.menu_id=m.parent_id and parent.menu_type='M' where r.role_key='kg_mediation_ops';
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.parent_id from (select role_id,menu_id from sys_role_menu) rm join sys_role r on r.role_id=rm.role_id join sys_menu m on m.menu_id=rm.menu_id join sys_menu parent on parent.menu_id=m.parent_id and parent.menu_type='M' where r.role_key='kg_mediation_ops';
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.parent_id from (select role_id,menu_id from sys_role_menu) rm join sys_role r on r.role_id=rm.role_id join sys_menu m on m.menu_id=rm.menu_id join sys_menu parent on parent.menu_id=m.parent_id and parent.menu_type='M' where r.role_key='kg_mediation_ops';
insert into sys_role(role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,remark) select '商家分成财务员','kg_merchant_finance',99,'1','0','0','migration',now(),'专用权限角色；须由管理员分配到指定后台账号，不自动分配用户' where not exists(select 1 from sys_role where role_key='kg_merchant_finance');
insert ignore into sys_role_menu(role_id,menu_id) select r.role_id,m.menu_id from sys_role r join sys_menu m on m.perms in ('kangaroo:merchantfinance:query','kangaroo:merchantfinance:edit','kangaroo:delivery:query') where r.role_key='kg_merchant_finance' and r.del_flag='0';
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.parent_id from (select role_id,menu_id from sys_role_menu) rm join sys_role r on r.role_id=rm.role_id join sys_menu m on m.menu_id=rm.menu_id join sys_menu parent on parent.menu_id=m.parent_id and parent.menu_type='M' where r.role_key='kg_merchant_finance';
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.parent_id from (select role_id,menu_id from sys_role_menu) rm join sys_role r on r.role_id=rm.role_id join sys_menu m on m.menu_id=rm.menu_id join sys_menu parent on parent.menu_id=m.parent_id and parent.menu_type='M' where r.role_key='kg_merchant_finance';
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.parent_id from (select role_id,menu_id from sys_role_menu) rm join sys_role r on r.role_id=rm.role_id join sys_menu m on m.menu_id=rm.menu_id join sys_menu parent on parent.menu_id=m.parent_id and parent.menu_type='M' where r.role_key='kg_merchant_finance';
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
insert into kg_schema_version(version,name,applied_time,checksum) values(81,'merchant_settlement_delivery',now(),'85d51c270e23c3ac622e2012428f6197d5a709ecff13df735212ffdb5b174dbe') on duplicate key update name=values(name),checksum=values(checksum),applied_time=now();
