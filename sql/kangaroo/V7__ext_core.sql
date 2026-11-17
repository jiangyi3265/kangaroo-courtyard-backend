-- =============================================================================
-- V7__ext_core（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('6');

-- 扩展模块的共享能力：支付单、评价、举报、通用收藏；后台菜单段 2100–2199「交易与内容」。
-- 可重复执行：建表 if not exists；菜单与字典按本脚本自有的 id / 类型先删后插。

create table if not exists kg_pay_order (
  pay_id        bigint       not null auto_increment comment '支付单ID',
  pay_no        varchar(32)  not null                comment '支付单号',
  biz_type      varchar(32)  not null                comment '业务类型：stay_order 旅居订单 / activity_signup 活动报名 / service_order 增值服务',
  biz_id        bigint       not null                comment '业务单主键',
  app_user_id   bigint       not null                comment '付款人（小程序账号）',
  store_id      bigint       null                    comment '收款门店',
  amount        bigint       not null                comment '应付金额（分）',
  description   varchar(128) not null                comment '商品描述',
  channel       varchar(16)  not null                comment '渠道：mock 联调 / wechat 微信支付',
  status        varchar(16)  not null default 'pending' comment 'pending 待支付 / paid 已支付 / closed 已关闭 / part_refunded 部分退款 / refunded 已退款',
  paid_time     datetime     null                    comment '到账时间',
  refund_amount bigint       not null default 0      comment '累计退款（分）',
  refund_time   datetime     null                    comment '最近退款时间',
  refund_reason varchar(255) null                    comment '最近退款原因',
  channel_txn   varchar(64)  null                    comment '渠道流水号',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  del_flag      char(1)      default '0'             comment '删除标志',
  primary key (pay_id),
  unique key uk_pay_no (pay_no),
  key idx_pay_biz (biz_type, biz_id),
  key idx_pay_user (app_user_id)
) engine=innodb comment='支付单';
call kg_assert_same_collation('kg_pay_order');

create table if not exists kg_review (
  review_id     bigint        not null auto_increment comment '评价ID',
  target_type   varchar(16)   not null                comment '评价对象：job 岗位 / store 门店 / room 房源 / activity 活动',
  target_id     bigint        not null                comment '对象主键',
  store_id      bigint        null                    comment '所属门店（便于门店查看自己的评价）',
  app_user_id   bigint        not null                comment '评价人',
  biz_type      varchar(32)   null                    comment '来源业务：application / stay_order / activity_signup',
  biz_id        bigint        null                    comment '来源业务单',
  rating        tinyint       not null                comment '评分 1–5',
  content       varchar(1000) not null default ''     comment '评价内容',
  images        varchar(1000) null                    comment '图片（逗号分隔）',
  tags          varchar(255)  null                    comment '标签（逗号分隔）',
  anonymous     char(1)       not null default '0'    comment '匿名 0 否 1 是',
  reply         varchar(500)  null                    comment '商家回复',
  reply_by      varchar(64)   null                    comment '回复人',
  reply_time    datetime      null                    comment '回复时间',
  status        char(1)       not null default '0'    comment '0 显示 1 隐藏（平台处理违规）',
  featured      char(1)       not null default '0'    comment '0 普通 1 精选',
  create_by     varchar(64)   default ''              comment '创建者',
  create_time   datetime                              comment '创建时间',
  update_by     varchar(64)   default ''              comment '更新者',
  update_time   datetime                              comment '更新时间',
  del_flag      char(1)       default '0'             comment '删除标志',
  primary key (review_id),
  unique key uk_review_biz (biz_type, biz_id, target_type, app_user_id),
  key idx_review_target (target_type, target_id, status),
  key idx_review_store (store_id)
) engine=innodb comment='评价（岗位/门店/房源/活动共用）';
call kg_assert_same_collation('kg_review');

create table if not exists kg_report (
  report_id     bigint       not null auto_increment comment '举报ID',
  target_type   varchar(16)  not null                comment '对象：post 动态 / comment 评论 / user 用户 / vlog / chat 会话 / review 评价',
  target_id     bigint       not null                comment '对象主键',
  reporter_id   bigint       not null                comment '举报人',
  reason        varchar(64)  not null                comment '举报类型',
  detail        varchar(500) null                    comment '补充说明',
  status        varchar(16)  not null default 'pending' comment 'pending 待处理 / upheld 已处理（违规）/ rejected 不成立',
  handle_note   varchar(255) null                    comment '处理说明',
  handled_by    varchar(64)  null                    comment '处理人',
  handled_time  datetime     null                    comment '处理时间',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  del_flag      char(1)      default '0'             comment '删除标志',
  primary key (report_id),
  unique key uk_report_once (target_type, target_id, reporter_id),
  key idx_report_status (status)
) engine=innodb comment='举报';
call kg_assert_same_collation('kg_report');

create table if not exists kg_favorite (
  fav_id        bigint      not null auto_increment comment '收藏ID',
  target_type   varchar(16) not null                comment '对象：room 房源 / post 动态 / activity 活动 / store 门店',
  target_id     bigint      not null                comment '对象主键',
  app_user_id   bigint      not null                comment '收藏人',
  create_time   datetime                            comment '收藏时间',
  primary key (fav_id),
  unique key uk_fav (target_type, target_id, app_user_id),
  key idx_fav_user (app_user_id, target_type)
) engine=innodb comment='通用收藏/关注';
call kg_assert_same_collation('kg_favorite');

-- ---------------------------------------------------------------- 字典
delete from sys_dict_data where dict_type in ('kg_pay_status', 'kg_biz_type', 'kg_review_target', 'kg_report_status');
delete from sys_dict_type where dict_type in ('kg_pay_status', 'kg_biz_type', 'kg_review_target', 'kg_report_status');
insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark) values
('袋鼠-支付状态', 'kg_pay_status',    '0', 'admin', sysdate(), '支付单状态'),
('袋鼠-业务类型', 'kg_biz_type',      '0', 'admin', sysdate(), '支付单/评价的来源业务'),
('袋鼠-评价对象', 'kg_review_target', '0', 'admin', sysdate(), '评价对象类型'),
('袋鼠-举报状态', 'kg_report_status', '0', 'admin', sysdate(), '举报处理状态');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) values
(1, '待支付',   'pending',       'kg_pay_status', '', 'warning', 'Y', '0', 'admin', sysdate(), ''),
(2, '已支付',   'paid',          'kg_pay_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(3, '已关闭',   'closed',        'kg_pay_status', '', 'info',    'N', '0', 'admin', sysdate(), ''),
(4, '部分退款', 'part_refunded', 'kg_pay_status', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(5, '已退款',   'refunded',      'kg_pay_status', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(1, '旅居订单', 'stay_order',      'kg_biz_type', '', 'primary', 'Y', '0', 'admin', sysdate(), ''),
(2, '活动报名', 'activity_signup', 'kg_biz_type', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(3, '增值服务', 'service_order',   'kg_biz_type', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(4, '岗位报名', 'application',     'kg_biz_type', '', 'info',    'N', '0', 'admin', sysdate(), ''),
(1, '岗位', 'job',      'kg_review_target', '', 'primary', 'Y', '0', 'admin', sysdate(), ''),
(2, '门店', 'store',    'kg_review_target', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(3, '房源', 'room',     'kg_review_target', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(4, '活动', 'activity', 'kg_review_target', '', 'info',    'N', '0', 'admin', sysdate(), ''),
(1, '待处理',   'pending',  'kg_report_status', '', 'warning', 'Y', '0', 'admin', sysdate(), ''),
(2, '已处理',   'upheld',   'kg_report_status', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(3, '不成立',   'rejected', 'kg_report_status', '', 'info',    'N', '0', 'admin', sysdate(), '');

-- ---------------------------------------------------------------- 菜单 2100–2199
delete from sys_menu where menu_id between 2100 and 2199;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2100, '交易与内容', 0, 11, 'kgtrade', null, '', '', 1, 0, 'M', '0', '0', '', 'money', 'admin', sysdate(), '支付单、评价、举报'),
(2110, '支付单',   2100, 1, 'payorder', 'kangaroo/ext/payorder/index', '', 'KgExtPayOrder', 1, 0, 'C', '0', '0', 'kangaroo:payorder:list', 'money',   'admin', sysdate(), ''),
(2111, '支付单查询', 2110, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:payorder:query',  '#', 'admin', sysdate(), ''),
(2112, '支付单退款', 2110, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:payorder:refund', '#', 'admin', sysdate(), ''),
(2120, '评价管理', 2100, 2, 'review',   'kangaroo/ext/review/index',   '', 'KgExtReview',   1, 0, 'C', '0', '0', 'kangaroo:review:list',   'star',    'admin', sysdate(), ''),
(2121, '评价查询', 2120, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:review:query',  '#', 'admin', sysdate(), ''),
(2122, '评价修改', 2120, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:review:edit',   '#', 'admin', sysdate(), ''),
(2123, '评价删除', 2120, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:review:remove', '#', 'admin', sysdate(), ''),
(2130, '举报处理', 2100, 3, 'report',   'kangaroo/ext/report/index',   '', 'KgExtReport',   1, 0, 'C', '0', '0', 'kangaroo:report:list',   'bug',     'admin', sysdate(), ''),
(2131, '举报查询', 2130, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:report:query',  '#', 'admin', sysdate(), ''),
(2132, '举报处理操作', 2130, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:report:handle', '#', 'admin', sysdate(), '');

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
values (7, 'ext_core', now(), '536a92f2b62c43c49f5c67e927eb9b38aa62e5b295599673d9d55b56791609df')
on duplicate key update name = 'ext_core', applied_time = now(), checksum = '536a92f2b62c43c49f5c67e927eb9b38aa62e5b295599673d9d55b56791609df';
