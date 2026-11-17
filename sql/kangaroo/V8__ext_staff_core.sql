-- =============================================================================
-- V8__ext_staff_core（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('7');

-- 员工端核心：面试日程（改期 / 员工评分）、员工发布岗位与分享追踪、调度（需求调整 / 紧急标记 / 日志）、投保申请。
-- 后台菜单段 2500–2599「员工运营」。可重复执行。

call kg_add_col_if_absent('kg_interview', 'staff_rating', 'staff_rating tinyint null comment ''员工对候选人的面试评分 1–5''');
call kg_add_col_if_absent('kg_interview', 'staff_note', 'staff_note varchar(500) null comment ''员工面试评语''');
call kg_add_col_if_absent('kg_interview', 'reschedule_count', 'reschedule_count int not null default 0 comment ''改期次数''');
call kg_add_col_if_absent('kg_interview', 'rescheduled_time', 'rescheduled_time datetime null comment ''最近一次改期时间''');

call kg_add_col_if_absent('kg_job', 'urgent_level', 'urgent_level tinyint not null default 0 comment ''紧急等级 0 无 1 一般 2 较急 3 紧急 4 特急''');
call kg_add_col_if_absent('kg_job', 'urgent_deadline', 'urgent_deadline datetime null comment ''紧急招募截止时间''');
call kg_add_col_if_absent('kg_job', 'share_visits', 'share_visits int not null default 0 comment ''分享链接累计访问''');
call kg_add_col_if_absent('kg_job', 'source', 'source varchar(16) not null default ''admin'' comment ''发布来源 admin 平台 / staff 门店员工（待平台审核）''');

call kg_add_col_if_absent('kg_application', 'share_code', 'share_code varchar(16) null comment ''报名来源的分享码''');

create table if not exists kg_job_share (
  share_id      bigint      not null auto_increment comment '分享ID',
  share_code    varchar(16) not null                comment '分享码',
  job_id        bigint      not null                comment '岗位',
  store_id      bigint      not null                comment '门店',
  staff_user_id bigint      not null                comment '分享人（员工小程序账号）',
  channel       varchar(16) not null default 'link' comment '渠道 friend 微信好友 / group 微信群 / moments 朋友圈 / link 链接 / poster 海报',
  shares        int         not null default 0      comment '分享次数（点分享/复制/海报）',
  visits        int         not null default 0      comment '访问人次（同一访客同一天只记一次）',
  create_time   datetime                            comment '创建时间',
  primary key (share_id),
  unique key uk_share_code (share_code),
  unique key uk_share_owner (job_id, staff_user_id, channel)
) engine=innodb comment='岗位分享码（分享追踪）';
call kg_assert_same_collation('kg_job_share');
call kg_add_col_if_absent('kg_job_share', 'shares', 'shares int not null default 0 comment ''分享次数（点分享/复制/海报）''');

create table if not exists kg_job_share_visit (
  visit_id      bigint      not null auto_increment comment '访问ID',
  share_code    varchar(16) not null                comment '分享码',
  visitor_key   varchar(64) not null                comment '访客（u:账号ID / ip:IP摘要，不存原始 IP）',
  visit_date    date        not null                comment '访问日期',
  create_time   datetime                            comment '首次访问时间',
  primary key (visit_id),
  unique key uk_share_visit (share_code, visitor_key, visit_date),
  key idx_share_visit_date (share_code, visit_date)
) engine=innodb comment='岗位分享访问（按访客按天去重）';
call kg_assert_same_collation('kg_job_share_visit');

create table if not exists kg_dispatch_log (
  log_id        bigint       not null auto_increment comment '日志ID',
  store_id      bigint       not null                comment '门店',
  job_id        bigint       null                    comment '岗位',
  op_type       varchar(16)  not null                comment 'adjust 数值调整 / urgent 紧急标记',
  operator_id   bigint       null                    comment '操作员工（小程序账号）',
  operator_name varchar(64)  not null default ''     comment '操作人显示名',
  before_value  varchar(128) null                    comment '调整前',
  after_value   varchar(128) null                    comment '调整后',
  reason        varchar(200) null                    comment '原因',
  valid_from    date         null                    comment '有效期起',
  valid_to      date         null                    comment '有效期止',
  delta         int          null                    comment '名额增减（adjust）',
  applied_time  datetime     null                    comment '生效时间（有效期开始当天）',
  reverted_time datetime     null                    comment '恢复/撤销时间',
  create_time   datetime                             comment '操作时间',
  primary key (log_id),
  key idx_dispatch_store (store_id, create_time)
) engine=innodb comment='调度日志（需求调整 / 紧急标记）';
call kg_assert_same_collation('kg_dispatch_log');
call kg_add_col_if_absent('kg_dispatch_log', 'delta', 'delta int null comment ''名额增减（adjust）''');
call kg_add_col_if_absent('kg_dispatch_log', 'applied_time', 'applied_time datetime null comment ''生效时间（有效期开始当天）''');
call kg_add_col_if_absent('kg_dispatch_log', 'reverted_time', 'reverted_time datetime null comment ''恢复/撤销时间''');

call kg_add_col_if_absent('kg_store', 'op_type', 'op_type varchar(16) not null default ''direct'' comment ''运营类型 direct 直营 / managed 代运营 / franchise 加盟''');

create table if not exists kg_staff_op_log (
  log_id        bigint       not null auto_increment comment '日志ID',
  store_id      bigint       null                    comment '门店',
  app_user_id   bigint       not null                comment '操作员工（小程序账号）',
  operator_name varchar(64)  not null default ''     comment '操作人显示名',
  role_code     varchar(16)  not null default ''     comment '操作时的角色',
  action        varchar(32)  not null                comment '动作编码',
  action_label  varchar(32)  not null                comment '动作名称',
  category      varchar(16)  not null default 'general' comment 'general 一般 / export 数据导出 / fund 资金 / grant 授权',
  detail        varchar(255) null                    comment '内容',
  device        varchar(64)  null                    comment '设备（由 User-Agent 粗分）',
  ok            char(1)      not null default '1'    comment '1 成功 0 失败',
  fail_reason   varchar(128) null                    comment '失败原因',
  create_time   datetime                             comment '操作时间',
  primary key (log_id),
  key idx_op_store (store_id, create_time),
  key idx_op_user (app_user_id, create_time)
) engine=innodb comment='员工操作与权限日志';
call kg_assert_same_collation('kg_staff_op_log');

create table if not exists kg_insurance_plan (
  plan_id       bigint       not null auto_increment comment '方案ID',
  plan_name     varchar(64)  not null                comment '方案名称',
  plan_desc     varchar(255) not null default ''     comment '保障内容',
  insurer       varchar(64)  not null default ''     comment '承保公司（与合作保险公司签约后填写）',
  price         bigint       not null default 0      comment '参考保费（分）',
  days          int          not null default 30     comment '保障天数',
  sort          int          not null default 0      comment '排序',
  status        char(1)      not null default '0'    comment '0 上架 1 下架',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  del_flag      char(1)      default '0'             comment '删除标志',
  remark        varchar(255) null                    comment '备注',
  primary key (plan_id)
) engine=innodb comment='保险方案（平台维护）';
call kg_assert_same_collation('kg_insurance_plan');

create table if not exists kg_insurance_apply (
  apply_id      bigint       not null auto_increment comment '投保申请ID',
  app_id        bigint       not null                comment '报名',
  app_user_id   bigint       not null                comment '被保险人（小程序账号）',
  store_id      bigint       not null                comment '门店',
  plan_id       bigint       not null                comment '方案',
  insured_name  varchar(32)  not null default ''     comment '被保险人姓名',
  start_date    date         not null                comment '起保日期',
  end_date      date         not null                comment '止保日期',
  price         bigint       not null default 0      comment '参考保费（分）',
  status        varchar(16)  not null default 'pending' comment 'pending 待处理 / effective 已生效 / rejected 已驳回',
  policy_no     varchar(64)  null                    comment '保单号（保险公司出具后由平台登记）',
  handle_note   varchar(255) null                    comment '处理说明',
  applied_by    bigint       not null                comment '申请员工（小程序账号）',
  handled_by    varchar(64)  null                    comment '处理人',
  handled_time  datetime     null                    comment '处理时间',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  del_flag      char(1)      default '0'             comment '删除标志',
  primary key (apply_id),
  key idx_ins_store (store_id, status),
  key idx_ins_app (app_id)
) engine=innodb comment='投保申请（平台人工对接保险公司后登记保单）';
call kg_assert_same_collation('kg_insurance_apply');

delete from sys_dict_data where dict_type in ('kg_ins_status', 'kg_urgent_level', 'kg_store_op_type');
delete from sys_dict_type where dict_type in ('kg_ins_status', 'kg_urgent_level', 'kg_store_op_type');
insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark) values
('袋鼠-投保状态', 'kg_ins_status',   '0', 'admin', sysdate(), '投保申请状态'),
('袋鼠-紧急等级', 'kg_urgent_level', '0', 'admin', sysdate(), '岗位紧急招募等级'),
('袋鼠-门店运营类型', 'kg_store_op_type', '0', 'admin', sysdate(), '门店运营类型');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) values
(1, '待处理', 'pending',   'kg_ins_status', '', 'warning', 'Y', '0', 'admin', sysdate(), ''),
(2, '已生效', 'effective', 'kg_ins_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(3, '已驳回', 'rejected',  'kg_ins_status', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(1, '无',   '0', 'kg_urgent_level', '', 'info',    'Y', '0', 'admin', sysdate(), ''),
(2, '一般', '1', 'kg_urgent_level', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(3, '较急', '2', 'kg_urgent_level', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(4, '紧急', '3', 'kg_urgent_level', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(5, '特急', '4', 'kg_urgent_level', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(1, '直营',   'direct',    'kg_store_op_type', '', 'success', 'Y', '0', 'admin', sysdate(), ''),
(2, '代运营', 'managed',   'kg_store_op_type', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(3, '加盟',   'franchise', 'kg_store_op_type', '', 'info',    'N', '0', 'admin', sysdate(), '');

delete from sys_menu where menu_id between 2500 and 2599;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2500, '员工运营', 0, 12, 'kgstaff', null, '', '', 1, 0, 'M', '0', '0', '', 'peoples', 'admin', sysdate(), '保险、调度等门店员工侧业务'),
(2510, '保险方案', 2500, 1, 'insplan',  'kangaroo/ext/insplan/index',  '', 'KgExtInsPlan',  1, 0, 'C', '0', '0', 'kangaroo:insplan:list',  'list',    'admin', sysdate(), ''),
(2511, '方案查询', 2510, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insplan:query',  '#', 'admin', sysdate(), ''),
(2512, '方案新增', 2510, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insplan:add',    '#', 'admin', sysdate(), ''),
(2513, '方案修改', 2510, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insplan:edit',   '#', 'admin', sysdate(), ''),
(2514, '方案删除', 2510, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insplan:remove', '#', 'admin', sysdate(), ''),
(2520, '投保申请', 2500, 2, 'insapply', 'kangaroo/ext/insapply/index', '', 'KgExtInsApply', 1, 0, 'C', '0', '0', 'kangaroo:insapply:list', 'form',    'admin', sysdate(), ''),
(2521, '投保查询', 2520, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insapply:query',  '#', 'admin', sysdate(), ''),
(2522, '投保处理', 2520, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insapply:handle', '#', 'admin', sysdate(), ''),
(2530, '调度日志', 2500, 3, 'dispatch', 'kangaroo/ext/dispatch/index', '', 'KgExtDispatch', 1, 0, 'C', '0', '0', 'kangaroo:dispatch:list', 'log',     'admin', sysdate(), ''),
(2531, '调度查询', 2530, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:dispatch:query',  '#', 'admin', sysdate(), ''),
(2540, '分享追踪', 2500, 4, 'jobshare', 'kangaroo/ext/jobshare/index', '', 'KgExtJobShare', 1, 0, 'C', '0', '0', 'kangaroo:jobshare:list', 'link',    'admin', sysdate(), ''),
(2541, '分享查询', 2540, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:jobshare:query',  '#', 'admin', sysdate(), ''),
(2550, '员工操作日志', 2500, 5, 'staffop', 'kangaroo/ext/staffop/index', '', 'KgExtStaffOp', 1, 0, 'C', '0', '0', 'kangaroo:staffop:list', 'logininfor', 'admin', sysdate(), ''),
(2551, '日志查询', 2550, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staffop:query',  '#', 'admin', sysdate(), '');

-- 两个演示保险方案（平台可在后台改；保险公司签约前承保公司留空，页面据此如实提示）
insert into kg_insurance_plan (plan_id, plan_name, plan_desc, insurer, price, days, sort, status, create_by, create_time)
select 1, '用工意外险（基础版）', '意外身故/伤残、意外医疗', '', 15000, 30, 1, '0', 'V8', now() from dual
 where not exists (select 1 from kg_insurance_plan where plan_id = 1);
insert into kg_insurance_plan (plan_id, plan_name, plan_desc, insurer, price, days, sort, status, create_by, create_time)
select 2, '用工意外险（尊享版）', '意外身故/伤残、意外医疗、住院津贴', '', 26000, 30, 2, '0', 'V8', now() from dual
 where not exists (select 1 from kg_insurance_plan where plan_id = 2);

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
values (8, 'ext_staff_core', now(), '02efd37a0c384dbacbc832f464dce9967622f68f865fb5fc514a3feba14e24b0')
on duplicate key update name = 'ext_staff_core', applied_time = now(), checksum = '02efd37a0c384dbacbc832f464dce9967622f68f865fb5fc514a3feba14e24b0';
