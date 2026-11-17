-- =============================================================================
-- V14__account_ext（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('13');

-- V14 账号与工作台补全：个人资料（自我介绍）、实名核验留痕、账号注销留痕、员工待办的责任人与备注、简历转发记录。
-- 约定同 V7–V13。实名核验只保留证件号后四位与不可还原的摘要，绝不存完整证件号。

call kg_add_col_if_absent('kg_app_user', 'intro', 'intro varchar(60) null comment ''自我介绍（个人中心，60 字以内）''');
call kg_add_col_if_absent('kg_app_user', 'id_last4', 'id_last4 char(4) null comment ''证件号后四位（实名核验后留存）''');
call kg_add_col_if_absent('kg_app_user', 'id_hash', 'id_hash varchar(64) null comment ''证件号摘要（HMAC-SHA256，用于判重，不可还原）''');
call kg_add_col_if_absent('kg_app_user', 'real_name_verified_time', 'real_name_verified_time datetime null comment ''实名核验通过时间''');
call kg_add_col_if_absent('kg_app_user', 'deactivated_time', 'deactivated_time datetime null comment ''账号注销时间''');

create table if not exists kg_todo_meta (
  id            bigint       not null auto_increment comment 'ID',
  store_id      bigint       not null                comment '门店',
  todo_key      varchar(64)  not null                comment '待办标识（业务类型:业务ID）',
  owner_id      bigint       null                    comment '责任人（门店员工）',
  owner_name    varchar(64)  null                    comment '责任人昵称',
  assigned_by   varchar(64)  null                    comment '转派人',
  assigned_time datetime     null                    comment '转派时间',
  note          varchar(200) null                    comment '备注（仅门店员工可见）',
  note_by       varchar(64)  null                    comment '备注人',
  note_time     datetime     null                    comment '备注时间',
  update_time   datetime                             comment '更新时间',
  primary key (id),
  unique key uk_todo_meta (store_id, todo_key)
) engine=innodb comment='员工待办的责任人与备注（待办本身由业务数据实时生成，不落表）';
call kg_assert_same_collation('kg_todo_meta');

create table if not exists kg_resume_forward (
  forward_id    bigint       not null auto_increment comment 'ID',
  app_id        bigint       not null                comment '报名',
  store_id      bigint       not null                comment '门店',
  from_user_id  bigint       not null                comment '转发人',
  from_name     varchar(64)  null                    comment '转发人昵称',
  target        varchar(16)  not null                comment 'manager 主理人 / store 门店群（全体在职员工）',
  suggestion    varchar(120) not null default ''     comment '审核建议',
  msg_ids       varchar(500) not null default ''     comment '生成的站内信ID，逗号分隔（用于统计已读）',
  track         char(1)      not null default '1'    comment '是否记录已读情况',
  create_time   datetime                             comment '转发时间',
  primary key (forward_id),
  key idx_forward_app (app_id)
) engine=innodb comment='简历转发记录（只在门店内部转发，不外发）';
call kg_assert_same_collation('kg_resume_forward');

create table if not exists kg_account_deletion (
  id            bigint       not null auto_increment comment 'ID',
  app_user_id   bigint       not null                comment '注销账号',
  nickname      varchar(64)  null                    comment '注销前昵称',
  reason        varchar(200) not null default ''     comment '注销原因',
  detail        varchar(500) not null default ''     comment '注销时清理与保留的说明',
  create_time   datetime                             comment '注销时间',
  primary key (id),
  key idx_account_deletion_user (app_user_id)
) engine=innodb comment='账号注销留痕（交易与资金记录依法保留，个人资料已清除）';
call kg_assert_same_collation('kg_account_deletion');

-- ---------------------------------------------------------------- 后台菜单 2650–2669
delete from sys_role_menu where menu_id between 2650 and 2669;
delete from sys_menu where menu_id between 2650 and 2669;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2650, '账号注销记录', 2400, 8, 'accountdel', 'kangaroo/ext/accountdel/index', '', 'KgExtAccountDel', 1, 0, 'C', '0', '0', 'kangaroo:accountdel:list', 'delete', 'admin', sysdate(), ''),
(2651, '注销记录查询', 2650, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:accountdel:query', '#', 'admin', sysdate(), ''),
(2660, '简历转发记录', 2600, 5, 'resumeforward', 'kangaroo/ext/resumeforward/index', '', 'KgExtResumeForward', 1, 0, 'C', '0', '0', 'kangaroo:resumeforward:list', 'share', 'admin', sysdate(), ''),
(2661, '转发记录查询', 2660, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:resumeforward:query', '#', 'admin', sysdate(), '');

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
values (14, 'account_ext', now(), '31d814acd0147e16311c416e234f8e49fd22a74baf40a5b91487ce022e700f75')
on duplicate key update name = 'account_ext', applied_time = now(), checksum = '31d814acd0147e16311c416e234f8e49fd22a74baf40a5b91487ce022e700f75';
