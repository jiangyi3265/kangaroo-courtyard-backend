-- =============================================================================
-- V13__ext_staff（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('12');

-- V13 员工端扩展：评价运营（回复 / 精选 / 内部备注）、人才库（收藏 / 隐藏 / 岗位邀请）、线上任务（任务扩展、成果提交、线下结算）。
-- 约定同 V7–V12：金额一律分；线上任务的佣金由门店线下支付给接单人并登记凭证，平台不经手资金、不做自动转账。

call kg_add_col_if_absent('kg_review', 'internal_note', 'internal_note varchar(200) null comment ''内部备注（仅门店员工可见）''');
call kg_add_col_if_absent('kg_review', 'feature_reasons', 'feature_reasons varchar(200) null comment ''精选理由，逗号分隔''');
call kg_add_col_if_absent('kg_review', 'feature_note', 'feature_note varchar(200) null comment ''精选备注''');
call kg_add_col_if_absent('kg_review', 'featured_by', 'featured_by varchar(64) null comment ''精选操作人''');
call kg_add_col_if_absent('kg_review', 'featured_time', 'featured_time datetime null comment ''精选时间''');

create table if not exists kg_talent_mark (
  id            bigint       not null auto_increment comment 'ID',
  store_id      bigint       not null                comment '门店',
  app_user_id   bigint       not null                comment '人才（小程序用户）',
  favorite      char(1)      not null default '0'    comment '收藏',
  hidden        char(1)      not null default '0'    comment '本店不感兴趣（隐藏）',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  primary key (id),
  unique key uk_talent_mark (store_id, app_user_id)
) engine=innodb comment='人才库标记（门店维度）';
call kg_assert_same_collation('kg_talent_mark');

create table if not exists kg_talent_invite (
  invite_id     bigint       not null auto_increment comment '邀请ID',
  store_id      bigint       not null                comment '门店',
  app_user_id   bigint       not null                comment '被邀请人',
  job_id        bigint       not null                comment '邀请报名的岗位',
  status        varchar(16)  not null default 'sent' comment 'sent 已发送 / applied 已报名',
  invited_by    bigint       null                    comment '邀请员工',
  create_time   datetime                             comment '邀请时间',
  update_time   datetime                             comment '更新时间',
  primary key (invite_id),
  unique key uk_talent_invite (store_id, app_user_id, job_id)
) engine=innodb comment='人才库岗位邀请（对方看不到门店以外的信息，门店在对方报名前也看不到联系方式）';
call kg_assert_same_collation('kg_talent_invite');

create table if not exists kg_online_task (
  job_id          bigint       not null                comment '岗位（kg_job，job_type = online）',
  task_type       varchar(32)  not null default ''     comment '任务类型',
  deliverables    varchar(100) not null default ''     comment '交付物',
  deadline_time   datetime     null                    comment '截止时间',
  commission_fen  bigint       not null default 0      comment '单人佣金（分）',
  criteria        varchar(200) not null default ''     comment '验收标准',
  announcement    varchar(300) not null default ''     comment '任务公告',
  material_url    varchar(255) null                    comment '参考素材',
  material_name   varchar(64)  null                    comment '素材名称',
  create_by       varchar(64)  default ''              comment '创建者',
  create_time     datetime                             comment '创建时间',
  update_by       varchar(64)  default ''              comment '更新者',
  update_time     datetime                             comment '更新时间',
  primary key (job_id)
) engine=innodb comment='线上任务扩展';
call kg_assert_same_collation('kg_online_task');

create table if not exists kg_online_submission (
  submission_id   bigint        not null auto_increment comment '提交ID',
  app_id          bigint        not null                comment '报名（接单）',
  round           int           not null                comment '第几轮',
  link            varchar(255)  not null default ''     comment '成果链接',
  images          varchar(1000) not null default ''     comment '成果图片',
  note            varchar(300)  not null default ''     comment '补充说明',
  status          varchar(16)   not null default 'submitted' comment 'submitted 待验收 / accepted 已通过 / returned 已退回',
  review_comment  varchar(200)  null                    comment '验收评语 / 退回原因',
  reviewed_by     varchar(64)   null                    comment '验收人',
  reviewed_time   datetime      null                    comment '验收时间',
  create_time     datetime                              comment '提交时间',
  primary key (submission_id),
  unique key uk_online_round (app_id, round)
) engine=innodb comment='线上任务成果提交';
call kg_assert_same_collation('kg_online_submission');

create table if not exists kg_online_settlement (
  settle_id        bigint       not null auto_increment comment '结算ID',
  settle_no        varchar(32)  not null                comment '结算单号',
  app_id           bigint       not null                comment '报名（接单）',
  job_id           bigint       not null                comment '任务',
  store_id         bigint       not null                comment '门店',
  app_user_id      bigint       not null                comment '接单人',
  amount_fen       bigint       not null                comment '结算金额（分）',
  status           varchar(24)  not null default 'pending_payout' comment 'pending_payout 待门店付款 / payout_registered 已登记付款 / confirmed 已确认收款 / disputed 申诉中',
  payout_method    varchar(16)  null                    comment '线下付款方式',
  payout_ref       varchar(64)  null                    comment '流水号',
  payout_voucher   varchar(255) null                    comment '付款凭证',
  registered_by    varchar(64)  null                    comment '登记人',
  registered_time  datetime     null                    comment '登记时间',
  confirmed_time   datetime     null                    comment '确认收款时间',
  dispute_reason   varchar(200) null                    comment '申诉原因',
  create_time      datetime                             comment '生成时间',
  update_by        varchar(64)  default ''              comment '更新者',
  update_time      datetime                             comment '更新时间',
  primary key (settle_id),
  unique key uk_online_settle_app (app_id),
  unique key uk_online_settle_no (settle_no)
) engine=innodb comment='线上任务线下结算单';
call kg_assert_same_collation('kg_online_settlement');

-- ---------------------------------------------------------------- 后台菜单 2600–2649
delete from sys_role_menu where menu_id between 2600 and 2649;
delete from sys_menu where menu_id between 2600 and 2649;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2600, '门店运营扩展', 0, 13, 'kgstaffext', null, '', '', 1, 0, 'M', '0', '0', '', 'peoples', 'admin', sysdate(), '线上任务、成果、结算、人才邀请'),
(2610, '线上任务', 2600, 1, 'onlinetask', 'kangaroo/ext/onlinetask/index', '', 'KgExtOnlineTask', 1, 0, 'C', '0', '0', 'kangaroo:onlinetask:list', 'job', 'admin', sysdate(), ''),
(2611, '任务查询', 2610, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:onlinetask:query', '#', 'admin', sysdate(), ''),
(2620, '成果提交', 2600, 2, 'onlinesubmit', 'kangaroo/ext/onlinesubmit/index', '', 'KgExtOnlineSubmit', 1, 0, 'C', '0', '0', 'kangaroo:onlinesubmit:list', 'upload', 'admin', sysdate(), ''),
(2621, '提交查询', 2620, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:onlinesubmit:query', '#', 'admin', sysdate(), ''),
(2630, '线下结算', 2600, 3, 'onlinesettle', 'kangaroo/ext/onlinesettle/index', '', 'KgExtOnlineSettle', 1, 0, 'C', '0', '0', 'kangaroo:onlinesettle:list', 'money', 'admin', sysdate(), ''),
(2631, '结算查询', 2630, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:onlinesettle:query', '#', 'admin', sysdate(), ''),
(2640, '人才邀请', 2600, 4, 'talentinvite', 'kangaroo/ext/talentinvite/index', '', 'KgExtTalentInvite', 1, 0, 'C', '0', '0', 'kangaroo:talentinvite:list', 'user', 'admin', sysdate(), ''),
(2641, '邀请查询', 2640, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:talentinvite:query', '#', 'admin', sysdate(), '');

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
values (13, 'ext_staff', now(), 'ff82095f19098589aa214d620f16e3c170fd082ea33da9b09c4db1fbddf59613')
on duplicate key update name = 'ext_staff', applied_time = now(), checksum = 'ff82095f19098589aa214d620f16e3c170fd082ea33da9b09c4db1fbddf59613';
