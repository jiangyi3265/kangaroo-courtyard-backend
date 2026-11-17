-- =============================================================================
-- V12__ext_manage（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('11');

-- V12 经营模块：代运营周报、每日经营报表、门店任务与积分、主理人钱包（分佣 / 结算申请 / 收款账户）、荣誉与关怀（荣誉 / 里程碑 / 生日礼物 / 申报）。
-- 约定同 V7–V11：金额一律分；不接任何支付 / 转账能力，结算 = 申请 → 后台审核 → 线下打款登记凭证。

create table if not exists kg_weekly_report (
  report_id       bigint        not null auto_increment comment '周报ID',
  store_id        bigint        not null                comment '门店',
  week_start      date          not null                comment '周一',
  status          varchar(16)   not null default 'draft' comment 'draft 草稿 / returned 已退回 / submitted 已提交',
  note            varchar(300)  not null default ''     comment '员工补充说明',
  risk            varchar(200)  not null default ''     comment '风险与问题',
  proofs          varchar(2000) not null default ''     comment '凭证图片，逗号分隔',
  snapshot        text                                  comment '提交时冻结的系统汇总（JSON）',
  version         int           not null default 0      comment '乐观锁版本',
  return_reason   varchar(200)  null                    comment '退回原因',
  submitted_by    bigint        null                    comment '提交人',
  submitted_name  varchar(64)   null                    comment '提交人昵称',
  submitted_time  datetime      null                    comment '提交时间',
  create_by       varchar(64)   default ''              comment '创建者',
  create_time     datetime                              comment '创建时间',
  update_by       varchar(64)   default ''              comment '更新者',
  update_time     datetime                              comment '更新时间',
  primary key (report_id),
  unique key uk_weekly_store_week (store_id, week_start)
) engine=innodb comment='代运营周报';
call kg_assert_same_collation('kg_weekly_report');

create table if not exists kg_weekly_item (
  item_id     bigint       not null auto_increment comment 'ID',
  report_id   bigint       not null                comment '周报',
  item_type   varchar(8)   not null                comment 'fee 费用 / coop 待协同',
  name        varchar(64)  not null default ''     comment '费用类别 / 协同事项',
  amount_fen  bigint       not null default 0      comment '金额（分，费用）',
  remark      varchar(120) not null default ''     comment '说明',
  dept        varchar(32)  not null default ''     comment '协同部门',
  done_flag   char(1)      not null default '0'    comment '协同是否完成',
  sort        int          not null default 0      comment '排序',
  primary key (item_id),
  key idx_weekly_item_report (report_id)
) engine=innodb comment='周报费用与待协同';
call kg_assert_same_collation('kg_weekly_item');

create table if not exists kg_daily_entry (
  entry_id     bigint       not null auto_increment comment '明细ID',
  store_id     bigint       not null                comment '门店',
  biz_date     date         not null                comment '经营日期',
  direction    varchar(8)   not null                comment 'income 收入 / expense 支出',
  category     varchar(32)  not null                comment '分类',
  amount_fen   bigint       not null                comment '金额（分）',
  pay_method   varchar(16)  not null default ''     comment '收付方式（只是记账字段，不发生支付）',
  remark       varchar(120) not null default ''     comment '说明',
  proofs       varchar(1000) not null default ''    comment '凭证图片',
  status       char(1)      not null default '0'    comment '0 有效 1 作废',
  creator_id   bigint       null                    comment '录入员工',
  creator_name varchar(64)  null                    comment '录入员工昵称',
  create_time  datetime                             comment '录入时间',
  void_by      varchar(64)  null                    comment '作废人',
  void_time    datetime     null                    comment '作废时间',
  primary key (entry_id),
  key idx_daily_store_date (store_id, biz_date)
) engine=innodb comment='每日经营手工明细';
call kg_assert_same_collation('kg_daily_entry');

create table if not exists kg_task (
  task_id      bigint       not null auto_increment comment '任务ID',
  store_id     bigint       not null                comment '门店',
  title        varchar(64)  not null                comment '标题',
  description  varchar(300) not null default ''     comment '说明',
  role_name    varchar(32)  not null default ''     comment '任务身份名称（不含任何系统权限）',
  scope_items  varchar(600) not null default ''     comment '任务职责范围，换行分隔',
  place        varchar(64)  not null default ''     comment '地点',
  priority     varchar(8)   not null default 'medium' comment 'high / medium / low',
  task_date    date         not null                comment '任务日期',
  start_time   varchar(5)   not null default ''     comment '开始 HH:mm',
  end_time     varchar(5)   not null default ''     comment '结束 HH:mm',
  est_hours    decimal(4,1) not null default 0      comment '预估工时',
  skills       varchar(120) not null default ''     comment '所需技能，逗号分隔',
  assign_mode  varchar(16)  not null default 'staff' comment 'staff 指定员工 / volunteer 开放给义工',
  quota        int          not null default 1      comment '名额',
  accept_rule  varchar(300) not null default ''     comment '验收标准',
  note         varchar(300) not null default ''     comment '补充说明',
  status       varchar(16)  not null default 'draft' comment 'draft 草稿 / published 已发布 / cancelled 已取消',
  creator_id   bigint       null                    comment '发布人',
  creator_name varchar(64)  null                    comment '发布人昵称',
  create_time  datetime                             comment '创建时间',
  update_time  datetime                             comment '更新时间',
  primary key (task_id),
  key idx_task_store_date (store_id, task_date)
) engine=innodb comment='门店任务';
call kg_assert_same_collation('kg_task');

create table if not exists kg_task_assignee (
  id            bigint       not null auto_increment comment 'ID',
  task_id       bigint       not null                comment '任务',
  app_user_id   bigint       not null                comment '执行人',
  nickname      varchar(64)  null                    comment '执行人昵称',
  source        varchar(16)  not null                comment 'assigned 指定 / claimed 认领',
  status        varchar(16)  not null                comment 'pending 待接受 / accepted 进行中 / done 已验收 / cancelled 已取消',
  actual_hours  decimal(4,1) null                    comment '实际工时',
  rating        int          null                    comment '验收评分 1–5',
  comment       varchar(200) null                    comment '验收评语',
  points        int          not null default 0      comment '折算积分',
  checked_by    bigint       null                    comment '验收人',
  checked_name  varchar(64)  null                    comment '验收人昵称',
  checked_time  datetime     null                    comment '验收时间',
  proofs        varchar(1000) not null default ''    comment '验收凭证',
  exp_written   char(1)      not null default '0'    comment '是否已写入经历档案',
  create_time   datetime                             comment '认领 / 指定时间',
  update_time   datetime                             comment '更新时间',
  primary key (id),
  unique key uk_task_user (task_id, app_user_id),
  key idx_task_assignee_user (app_user_id)
) engine=innodb comment='任务执行人';
call kg_assert_same_collation('kg_task_assignee');

create table if not exists kg_point_entry (
  id           bigint       not null auto_increment comment 'ID',
  app_user_id  bigint       not null                comment '用户',
  delta        int          not null                comment '积分变动',
  reason       varchar(64)  not null                comment '原因',
  biz_type     varchar(32)  not null                comment '来源类型',
  biz_id       bigint       not null                comment '来源ID',
  create_time  datetime                             comment '时间',
  primary key (id),
  unique key uk_point_biz (biz_type, biz_id, app_user_id)
) engine=innodb comment='积分流水';
call kg_assert_same_collation('kg_point_entry');

create table if not exists kg_commission_rule (
  rule_id         bigint       not null auto_increment comment '规则ID',
  payee_user_id   bigint       not null                comment '收款人（小程序用户ID）',
  store_id        bigint       not null                comment '门店',
  source_type     varchar(16)  not null                comment 'stay 房源营业额 / shop 二消服务 / activity 活动报名',
  rate_bp         int          not null default 0      comment '固定费率（基点，200 = 2%）；有阶梯时按阶梯',
  tiers           varchar(300) not null default ''     comment '阶梯：当月累计营业额上限元:费率基点，分号分隔，最后一档上限写 0 表示以上',
  status          char(1)      not null default '0'    comment '0 生效 1 停用',
  effective_from  date         not null                comment '生效日期（此日之后完成的订单才计佣）',
  remark          varchar(200) not null default ''     comment '说明（结算协议编号等）',
  create_by       varchar(64)  default ''              comment '创建者',
  create_time     datetime                             comment '创建时间',
  update_by       varchar(64)  default ''              comment '更新者',
  update_time     datetime                             comment '更新时间',
  del_flag        char(1)      not null default '0'    comment '删除标志',
  primary key (rule_id),
  key idx_commission_payee (payee_user_id)
) engine=innodb comment='分佣规则';
call kg_assert_same_collation('kg_commission_rule');

create table if not exists kg_wallet_entry (
  entry_id        bigint       not null auto_increment comment '流水ID',
  app_user_id     bigint       not null                comment '收款人',
  store_id        bigint       null                    comment '门店',
  rule_id         bigint       null                    comment '分佣规则',
  entry_type      varchar(16)  not null                comment 'credit 入账 / reverse 退款冲回 / adjust 人工调整',
  source_type     varchar(16)  not null                comment 'stay / shop / activity / adjust',
  biz_no          varchar(64)  not null                comment '订单号 / 调整单号',
  biz_title       varchar(64)  not null default ''     comment '订单摘要',
  base_fen        bigint       not null default 0      comment '计佣基数（分）',
  rate_bp         int          not null default 0      comment '费率（基点）',
  amount_fen      bigint       not null                comment '金额（分，冲回为负）',
  available_date  date         not null                comment '可结算日期（结算日）',
  remark          varchar(200) not null default ''     comment '说明',
  create_by       varchar(64)  default ''              comment '创建者',
  create_time     datetime                             comment '入账时间',
  update_by       varchar(64)  default ''              comment '更新者',
  update_time     datetime                             comment '更新时间',
  primary key (entry_id),
  unique key uk_wallet_biz (app_user_id, biz_no, entry_type),
  key idx_wallet_user (app_user_id, available_date)
) engine=innodb comment='钱包流水';
call kg_assert_same_collation('kg_wallet_entry');

create table if not exists kg_wallet_payee (
  payee_id        bigint       not null auto_increment comment 'ID',
  app_user_id     bigint       not null                comment '小程序用户',
  payee_name      varchar(32)  not null                comment '收款人姓名',
  bank_name       varchar(32)  not null                comment '开户行 / 收款方式',
  account_masked  varchar(32)  not null                comment '账号（脱敏，只存尾号）',
  remark          varchar(200) not null default ''     comment '结算协议说明',
  create_by       varchar(64)  default ''              comment '创建者',
  create_time     datetime                             comment '创建时间',
  update_by       varchar(64)  default ''              comment '更新者',
  update_time     datetime                             comment '更新时间',
  primary key (payee_id),
  unique key uk_payee_user (app_user_id)
) engine=innodb comment='收款账户（平台财务按结算协议登记）';
call kg_assert_same_collation('kg_wallet_payee');

create table if not exists kg_wallet_withdraw (
  withdraw_id     bigint       not null auto_increment comment '结算申请ID',
  withdraw_no     varchar(32)  not null                comment '申请单号',
  app_user_id     bigint       not null                comment '申请人',
  amount_fen      bigint       not null                comment '金额（分）',
  payee_snapshot  varchar(128) not null                comment '收款账户快照',
  status          varchar(16)  not null default 'pending' comment 'pending 待审核 / approved 待打款 / paid 已打款 / rejected 已驳回',
  audit_note      varchar(200) null                    comment '审核说明',
  audited_by      varchar(64)  null                    comment '审核人',
  audited_time    datetime     null                    comment '审核时间',
  paid_voucher    varchar(255) null                    comment '线下打款凭证（流水号 / 图片）',
  paid_by         varchar(64)  null                    comment '打款登记人',
  paid_time       datetime     null                    comment '打款登记时间',
  idem_key        varchar(64)  not null                comment '幂等键',
  create_time     datetime                             comment '申请时间',
  update_by       varchar(64)  default ''              comment '更新者',
  update_time     datetime                             comment '更新时间',
  primary key (withdraw_id),
  unique key uk_withdraw_no (withdraw_no),
  unique key uk_withdraw_idem (app_user_id, idem_key),
  key idx_withdraw_user (app_user_id)
) engine=innodb comment='结算申请';
call kg_assert_same_collation('kg_wallet_withdraw');

create table if not exists kg_honor_def (
  honor_id      bigint       not null auto_increment comment '荣誉ID',
  name          varchar(32)  not null                comment '名称',
  badge         varchar(255) not null default ''     comment '徽章图',
  basis         varchar(200) not null default ''     comment '评选依据',
  rule_metric   varchar(32)  not null default ''     comment '可量化的解锁指标：good_rate_90d / activities_90d / revenue_month / tasks_done_90d；空 = 只由平台评定',
  rule_threshold bigint      not null default 0      comment '门槛（好评率用基点，营收用分）',
  rule_text     varchar(100) not null default ''     comment '解锁条件文案',
  status        char(1)      not null default '0'    comment '0 启用 1 停用',
  sort          int          not null default 0      comment '排序',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  primary key (honor_id)
) engine=innodb comment='荣誉定义';
call kg_assert_same_collation('kg_honor_def');

create table if not exists kg_honor_award (
  award_id      bigint       not null auto_increment comment 'ID',
  app_user_id   bigint       not null                comment '获得者',
  honor_id      bigint       not null                comment '荣誉',
  store_id      bigint       null                    comment '门店',
  awarded_date  date         not null                comment '获得日期',
  basis         varchar(200) not null default ''     comment '评选依据',
  create_by     varchar(64)  default ''              comment '授予人',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  del_flag      char(1)      not null default '0'    comment '删除标志（撤销）',
  primary key (award_id),
  key idx_honor_award_user (app_user_id)
) engine=innodb comment='荣誉授予（平台评定）';
call kg_assert_same_collation('kg_honor_award');

create table if not exists kg_growth_milestone (
  milestone_id  bigint       not null auto_increment comment 'ID',
  app_user_id   bigint       not null                comment '主理人',
  store_id      bigint       null                    comment '门店',
  happened_on   varchar(7)   not null                comment '年月 yyyy.MM',
  title         varchar(64)  not null                comment '内容',
  source        varchar(16)  not null default 'admin' comment 'system 系统写入 / admin 后台补录',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  del_flag      char(1)      not null default '0'    comment '删除标志',
  primary key (milestone_id),
  unique key uk_milestone (app_user_id, happened_on, title)
) engine=innodb comment='成长里程碑';
call kg_assert_same_collation('kg_growth_milestone');

create table if not exists kg_gift_option (
  gift_id       bigint       not null auto_increment comment '礼物ID',
  name          varchar(32)  not null                comment '名称',
  subtitle      varchar(32)  not null default ''     comment '副标题',
  image         varchar(255) not null default ''     comment '图片',
  stock         int          not null default 0      comment '库存',
  status        char(1)      not null default '0'    comment '0 上架 1 下架',
  sort          int          not null default 0      comment '排序',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  primary key (gift_id)
) engine=innodb comment='生日礼物选项';
call kg_assert_same_collation('kg_gift_option');

create table if not exists kg_gift_claim (
  claim_id      bigint       not null auto_increment comment '领取ID',
  app_user_id   bigint       not null                comment '主理人',
  gift_id       bigint       not null                comment '礼物',
  gift_name     varchar(32)  not null                comment '礼物名称快照',
  claim_year    int          not null                comment '年份（每年一次）',
  receiver      varchar(32)  not null                comment '收货人（本次领取单专用，不存到账号）',
  phone         varchar(20)  not null                comment '手机号',
  address       varchar(200) not null                comment '地址',
  status        varchar(16)  not null default 'pending' comment 'pending 待发货 / shipped 已发货',
  express_no    varchar(64)  null                    comment '快递单号',
  create_time   datetime                             comment '领取时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '发货时间',
  primary key (claim_id),
  unique key uk_gift_claim_year (app_user_id, claim_year)
) engine=innodb comment='生日礼物领取';
call kg_assert_same_collation('kg_gift_claim');

create table if not exists kg_appeal (
  appeal_id     bigint       not null auto_increment comment '申报ID',
  app_user_id   bigint       not null                comment '申报人',
  appeal_type   varchar(32)  not null                comment '事件类型',
  biz_type      varchar(32)  null                    comment '关联类型',
  biz_id        bigint       null                    comment '关联ID',
  biz_label     varchar(100) null                    comment '关联摘要',
  content       varchar(500) not null                comment '事情经过',
  proofs        varchar(1000) not null default ''    comment '凭证',
  help_wanted   varchar(200) not null                comment '希望获得的帮助',
  status        varchar(16)  not null default 'pending' comment 'pending 待处理 / resolved 已处理 / rejected 不予受理',
  reply         varchar(500) null                    comment '处理结果',
  handled_by    varchar(64)  null                    comment '处理人',
  handled_time  datetime     null                    comment '处理时间',
  create_time   datetime                             comment '申报时间',
  primary key (appeal_id),
  key idx_appeal_user (app_user_id)
) engine=innodb comment='委屈奖 / 申诉申报';
call kg_assert_same_collation('kg_appeal');

call kg_add_col_if_absent('kg_app_user', 'birthday', 'birthday varchar(5) null comment ''生日 MM-dd（自愿填写，可清除）''');

-- ---------------------------------------------------------------- 初始数据（荣誉定义、生日礼物；已存在则不覆盖）
insert into kg_honor_def (name, badge, basis, rule_metric, rule_threshold, rule_text, status, sort, create_by, create_time)
select * from (select '袋鼠之星' n, '/static/media/honor-badge-1.jpg' b, '近 90 天好评率 ≥ 95%，积极优化体验' ba, 'good_rate_90d' m, 9500 t, '近 90 天好评率 ≥ 95%' rt, '0' st, 1 so, 'V12' cb, now() ct) x
where not exists (select 1 from kg_honor_def where name = '袋鼠之星');
insert into kg_honor_def (name, badge, basis, rule_metric, rule_threshold, rule_text, status, sort, create_by, create_time)
select * from (select '活动大师' n, '/static/media/honor-badge-2.jpg' b, '近 90 天成功举办 3 场以上活动' ba, 'activities_90d' m, 3 t, '近 90 天举办活动 ≥ 3 场' rt, '0' st, 2 so, 'V12' cb, now() ct) x
where not exists (select 1 from kg_honor_def where name = '活动大师');
insert into kg_honor_def (name, badge, basis, rule_metric, rule_threshold, rule_text, status, sort, create_by, create_time)
select * from (select '温柔奖' n, '/static/media/honor-heart.jpg' b, '近 90 天好评率 ≥ 97%，并帮助伙伴完成 20 次任务' ba, 'tasks_done_90d' m, 20 t, '近 90 天验收完成任务 ≥ 20 人次' rt, '0' st, 3 so, 'V12' cb, now() ct) x
where not exists (select 1 from kg_honor_def where name = '温柔奖');
insert into kg_honor_def (name, badge, basis, rule_metric, rule_threshold, rule_text, status, sort, create_by, create_time)
select * from (select '经营新星' n, '/static/media/honor-stamp.jpg' b, '单月营收首次突破 ¥5,000' ba, 'revenue_month' m, 500000 t, '本月营收 ≥ ¥5,000' rt, '0' st, 4 so, 'V12' cb, now() ct) x
where not exists (select 1 from kg_honor_def where name = '经营新星');

insert into kg_gift_option (name, subtitle, image, stock, status, sort, create_by, create_time)
select * from (select '手作礼盒' n, '温暖心意' s, '/static/media/honor-gift-1.jpg' i, 50 st, '0' ss, 1 so, 'V12' cb, now() ct) x
where not exists (select 1 from kg_gift_option where name = '手作礼盒');
insert into kg_gift_option (name, subtitle, image, stock, status, sort, create_by, create_time)
select * from (select '定制围裙' n, '小院专属' s, '/static/media/honor-gift-2.jpg' i, 50 st, '0' ss, 2 so, 'V12' cb, now() ct) x
where not exists (select 1 from kg_gift_option where name = '定制围裙');
insert into kg_gift_option (name, subtitle, image, stock, status, sort, create_by, create_time)
select * from (select '香氛蜡烛' n, '舒缓时光' s, '/static/media/honor-gift-3.jpg' i, 50 st, '0' ss, 3 so, 'V12' cb, now() ct) x
where not exists (select 1 from kg_gift_option where name = '香氛蜡烛');

-- ---------------------------------------------------------------- 后台菜单 2500–2599
delete from sys_role_menu where menu_id between 2500 and 2599;
delete from sys_menu where menu_id between 2500 and 2599;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2500, '经营管理', 0, 12, 'kgmanage', null, '', '', 1, 0, 'M', '0', '0', '', 'chart', 'admin', sysdate(), '周报、日报、任务、分佣与结算、荣誉关怀、申报'),
(2510, '代运营周报', 2500, 1, 'weeklyreport', 'kangaroo/ext/weeklyreport/index', '', 'KgExtWeeklyReport', 1, 0, 'C', '0', '0', 'kangaroo:weeklyreport:list', 'form', 'admin', sysdate(), ''),
(2511, '周报查询', 2510, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:weeklyreport:query', '#', 'admin', sysdate(), ''),
(2512, '周报退回', 2510, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:weeklyreport:audit', '#', 'admin', sysdate(), ''),
(2520, '日报明细', 2500, 2, 'dailyentry', 'kangaroo/ext/dailyentry/index', '', 'KgExtDailyEntry', 1, 0, 'C', '0', '0', 'kangaroo:dailyentry:list', 'list', 'admin', sysdate(), ''),
(2521, '明细查询', 2520, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:dailyentry:query', '#', 'admin', sysdate(), ''),
(2530, '门店任务', 2500, 3, 'task', 'kangaroo/ext/task/index', '', 'KgExtTask', 1, 0, 'C', '0', '0', 'kangaroo:task:list', 'job', 'admin', sysdate(), ''),
(2531, '任务查询', 2530, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:task:query', '#', 'admin', sysdate(), ''),
(2535, '任务执行记录', 2500, 4, 'taskassignee', 'kangaroo/ext/taskassignee/index', '', 'KgExtTaskAssignee', 1, 0, 'C', '0', '0', 'kangaroo:taskassignee:list', 'peoples', 'admin', sysdate(), ''),
(2536, '执行记录查询', 2535, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:taskassignee:query', '#', 'admin', sysdate(), ''),
(2540, '分佣规则', 2500, 5, 'commission', 'kangaroo/ext/commission/index', '', 'KgExtCommission', 1, 0, 'C', '0', '0', 'kangaroo:commission:list', 'money', 'admin', sysdate(), ''),
(2541, '规则查询', 2540, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:commission:query', '#', 'admin', sysdate(), ''),
(2542, '规则新增', 2540, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:commission:add', '#', 'admin', sysdate(), ''),
(2543, '规则修改', 2540, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:commission:edit', '#', 'admin', sysdate(), ''),
(2544, '规则删除', 2540, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:commission:remove', '#', 'admin', sysdate(), ''),
(2550, '钱包流水', 2500, 6, 'walletentry', 'kangaroo/ext/walletentry/index', '', 'KgExtWalletEntry', 1, 0, 'C', '0', '0', 'kangaroo:walletentry:list', 'log', 'admin', sysdate(), ''),
(2551, '流水查询', 2550, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:walletentry:query', '#', 'admin', sysdate(), ''),
(2552, '人工调整', 2550, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:walletentry:add', '#', 'admin', sysdate(), ''),
(2555, '收款账户', 2500, 7, 'payee', 'kangaroo/ext/payee/index', '', 'KgExtPayee', 1, 0, 'C', '0', '0', 'kangaroo:payee:list', 'user', 'admin', sysdate(), ''),
(2556, '账户查询', 2555, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:payee:query', '#', 'admin', sysdate(), ''),
(2557, '账户登记', 2555, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:payee:add', '#', 'admin', sysdate(), ''),
(2558, '账户修改', 2555, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:payee:edit', '#', 'admin', sysdate(), ''),
(2560, '结算申请', 2500, 8, 'withdraw', 'kangaroo/ext/withdraw/index', '', 'KgExtWithdraw', 1, 0, 'C', '0', '0', 'kangaroo:withdraw:list', 'shopping', 'admin', sysdate(), ''),
(2561, '申请查询', 2560, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:withdraw:query', '#', 'admin', sysdate(), ''),
(2562, '审核与打款登记', 2560, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:withdraw:audit', '#', 'admin', sysdate(), ''),
(2570, '荣誉定义', 2500, 9, 'honordef', 'kangaroo/ext/honordef/index', '', 'KgExtHonorDef', 1, 0, 'C', '0', '0', 'kangaroo:honordef:list', 'star', 'admin', sysdate(), ''),
(2571, '荣誉查询', 2570, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:honordef:query', '#', 'admin', sysdate(), ''),
(2572, '荣誉新增', 2570, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:honordef:add', '#', 'admin', sysdate(), ''),
(2573, '荣誉修改', 2570, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:honordef:edit', '#', 'admin', sysdate(), ''),
(2575, '荣誉授予', 2500, 10, 'honoraward', 'kangaroo/ext/honoraward/index', '', 'KgExtHonorAward', 1, 0, 'C', '0', '0', 'kangaroo:honoraward:list', 'education', 'admin', sysdate(), ''),
(2576, '授予查询', 2575, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:honoraward:query', '#', 'admin', sysdate(), ''),
(2577, '授予荣誉', 2575, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:honoraward:add', '#', 'admin', sysdate(), ''),
(2578, '修改授予', 2575, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:honoraward:edit', '#', 'admin', sysdate(), ''),
(2579, '撤销授予', 2575, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:honoraward:remove', '#', 'admin', sysdate(), ''),
(2580, '成长里程碑', 2500, 11, 'milestone', 'kangaroo/ext/milestone/index', '', 'KgExtMilestone', 1, 0, 'C', '0', '0', 'kangaroo:milestone:list', 'date', 'admin', sysdate(), ''),
(2581, '里程碑查询', 2580, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:milestone:query', '#', 'admin', sysdate(), ''),
(2582, '里程碑补录', 2580, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:milestone:add', '#', 'admin', sysdate(), ''),
(2583, '里程碑修改', 2580, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:milestone:edit', '#', 'admin', sysdate(), ''),
(2584, '里程碑删除', 2580, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:milestone:remove', '#', 'admin', sysdate(), ''),
(2585, '生日礼物', 2500, 12, 'giftoption', 'kangaroo/ext/giftoption/index', '', 'KgExtGiftOption', 1, 0, 'C', '0', '0', 'kangaroo:giftoption:list', 'shopping', 'admin', sysdate(), ''),
(2586, '礼物查询', 2585, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:giftoption:query', '#', 'admin', sysdate(), ''),
(2587, '礼物新增', 2585, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:giftoption:add', '#', 'admin', sysdate(), ''),
(2588, '礼物修改', 2585, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:giftoption:edit', '#', 'admin', sysdate(), ''),
(2590, '礼物领取', 2500, 13, 'giftclaim', 'kangaroo/ext/giftclaim/index', '', 'KgExtGiftClaim', 1, 0, 'C', '0', '0', 'kangaroo:giftclaim:list', 'guide', 'admin', sysdate(), ''),
(2591, '领取查询', 2590, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:giftclaim:query', '#', 'admin', sysdate(), ''),
(2592, '发货登记', 2590, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:giftclaim:audit', '#', 'admin', sysdate(), ''),
(2595, '申报处理', 2500, 14, 'appeal', 'kangaroo/ext/appeal/index', '', 'KgExtAppeal', 1, 0, 'C', '0', '0', 'kangaroo:appeal:list', 'message', 'admin', sysdate(), ''),
(2596, '申报查询', 2595, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:appeal:query', '#', 'admin', sysdate(), ''),
(2597, '申报处理', 2595, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:appeal:audit', '#', 'admin', sysdate(), '');

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
values (12, 'ext_manage', now(), 'e16f186b55cf8dfbed8e06a4fe1a6e29e840633ebbbf2697a9cc6ddd24a7099f')
on duplicate key update name = 'ext_manage', applied_time = now(), checksum = 'e16f186b55cf8dfbed8e06a4fe1a6e29e840633ebbbf2697a9cc6ddd24a7099f';
