-- =============================================================================
-- 袋鼠小院业务表 V1 —— 账号 / 门店 / 员工授权 / 岗位 / 报名履约 / 消息
--
-- 执行方式：mysql -uroot -p dkxy < V1__kangaroo_core.sql
-- 前置：库中已存在若依核心表（sql/ry_20250522.sql）
-- 回退：执行同目录 V1__rollback.sql
--
-- !! 这是一个只能在空库上执行的脚本 !!
-- 下面每一张表前面都有 drop table if exists —— 在已有数据的库上重跑，
-- 门店、账号、报名单与全部履约流水会被物理删除且无法恢复。
-- 这一行原先写的是"不修改、不删除任何既有业务数据"，与代码正好相反；
-- 而项目里没有迁移状态账本（没有 flyway，也没有 schema_version 表），
-- "这个库跑过 V1 没有"只能靠人记 —— 所以下面加了一道机器守卫。
--
-- 守卫：只要任意一张 kg_ 表里已经有数据，脚本会在任何 drop 之前 SIGNAL 中止。
-- 确实要在有数据的库上重建（只应发生在开发库），先自行 drop 或清空。
--
-- 约定：
--   * 金额字段统一 decimal(12,2)，单位：元（人民币）。
--   * 时间字段 datetime，应用层统一 GMT+8。
--   * del_flag 沿用若依风格 char(1)：'0' 存在 / '2' 删除。
--   * 业务状态使用可读 varchar 编码，便于排查与前端映射。
-- =============================================================================

-- ----------------------------
-- 守卫：库里已经有业务数据就不许往下走
--
-- 放在所有 drop 之前。用存储过程是因为 MySQL 的 SIGNAL 只能出现在存储程序里，
-- 而这个脚本是直接喂给 mysql 客户端的。
-- ----------------------------
drop procedure if exists kg_v1_abort_if_not_empty;
delimiter $$
create procedure kg_v1_abort_if_not_empty()
begin
  declare v_tables int default 0;
  declare v_rows   bigint default 0;

  select count(*) into v_tables
    from information_schema.tables
   where table_schema = database() and table_name like 'kg\_%';

  if v_tables > 0 then
    -- 只统计最能代表"有真实业务"的三张表；它们任意一张非空就中止
    set @c := 0;
    select count(*) into @c from information_schema.tables
     where table_schema = database() and table_name = 'kg_app_user';
    if @c > 0 then
      set @s := 'select count(*) into @n from kg_app_user';
      prepare st from @s; execute st; deallocate prepare st;
      set v_rows = v_rows + ifnull(@n, 0);
    end if;
    select count(*) into @c from information_schema.tables
     where table_schema = database() and table_name = 'kg_store';
    if @c > 0 then
      set @s := 'select count(*) into @n from kg_store';
      prepare st from @s; execute st; deallocate prepare st;
      set v_rows = v_rows + ifnull(@n, 0);
    end if;
    select count(*) into @c from information_schema.tables
     where table_schema = database() and table_name = 'kg_application';
    if @c > 0 then
      set @s := 'select count(*) into @n from kg_application';
      prepare st from @s; execute st; deallocate prepare st;
      set v_rows = v_rows + ifnull(@n, 0);
    end if;
  end if;

  if v_rows > 0 then
    signal sqlstate '45000' set message_text =
      'V1 是建库脚本：它会 drop 掉全部 kg_ 表。当前库里已经有业务数据（账号/门店/报名合计非空），已中止。要重建请先自行清空。';
  end if;
end $$
delimiter ;
call kg_v1_abort_if_not_empty();
drop procedure if exists kg_v1_abort_if_not_empty;

-- ----------------------------
-- 小程序账号（与 sys_user 完全独立，不共用凭证）
-- ----------------------------
drop table if exists kg_app_user;
create table kg_app_user (
  user_id           bigint(20)      not null auto_increment    comment '小程序账号ID',
  openid            varchar(64)     not null                   comment '微信 openid（小程序内唯一）',
  unionid           varchar(64)     default null               comment '微信 unionid',
  nickname          varchar(64)     default ''                 comment '昵称',
  avatar            varchar(255)    default ''                 comment '头像地址',
  phone             varchar(20)     default null               comment '手机号',
  real_name         varchar(40)     default null               comment '真实姓名',
  gender            char(1)         default '0'                comment '性别（0未知 1男 2女）',
  city              varchar(32)     default null               comment '常驻城市',
  status            char(1)         default '0'                comment '账号状态（0正常 1停用）',
  register_time     datetime                                   comment '注册时间',
  last_login_time   datetime                                   comment '最后登录时间',
  last_login_ip     varchar(64)     default ''                 comment '最后登录IP',
  del_flag          char(1)         default '0'                comment '删除标志（0存在 2删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (user_id),
  unique key uk_app_user_openid (openid),
  key idx_app_user_phone (phone)
) engine=innodb auto_increment=1000 comment = '小程序账号';

-- ----------------------------
-- 门店 / 商家
-- ----------------------------
drop table if exists kg_store;
create table kg_store (
  store_id          bigint(20)      not null auto_increment    comment '门店ID',
  store_name        varchar(64)     not null                   comment '门店名称',
  city              varchar(32)     not null                   comment '城市',
  address           varchar(255)    default ''                 comment '详细地址',
  cover             varchar(255)    default ''                 comment '封面图',
  intro             varchar(1000)   default ''                 comment '门店简介',
  contact_phone     varchar(20)     default null               comment '对外联系电话（签约后才向用户下发）',
  safe_cert         char(1)         default '1'                comment '安心商家认证（0已认证 1未认证）',
  cert_time         datetime                                   comment '认证通过时间',
  cert_by           varchar(64)     default null               comment '认证操作人（后台账号）',
  rating            decimal(3,2)    default null               comment '综合评分（由评价聚合）',
  review_count      int(11)         default 0                  comment '累计评价数',
  status            char(1)         default '0'                comment '门店状态（0正常 1停用）',
  del_flag          char(1)         default '0'                comment '删除标志（0存在 2删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (store_id),
  key idx_store_city (city),
  key idx_store_status (status, del_flag)
) engine=innodb auto_increment=100 comment = '门店/商家';

-- ----------------------------
-- 员工授权：小程序账号 -> 门店 的员工身份
-- 唯一可信来源。hasStaffAccess 由该表推导，禁止前端自行判定。
-- ----------------------------
drop table if exists kg_staff_grant;
create table kg_staff_grant (
  grant_id          bigint(20)      not null auto_increment    comment '授权ID',
  app_user_id       bigint(20)      not null                   comment '被授权的小程序账号ID',
  store_id          bigint(20)      not null                   comment '门店ID',
  role_code         varchar(32)     not null default 'staff'   comment '员工角色（manager主理人 keeper门店管家 staff员工）',
  status            char(1)         default '0'                comment '授权状态（0生效 1停用）',
  granted_by        varchar(64)     default ''                 comment '授权人（后台账号）',
  granted_time      datetime                                   comment '授权时间',
  revoked_by        varchar(64)     default null               comment '撤销人',
  revoked_time      datetime                                   comment '撤销时间',
  del_flag          char(1)         default '0'                comment '删除标志（0存在 2删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (grant_id),
  unique key uk_grant_user_store (app_user_id, store_id),
  key idx_grant_store (store_id, status)
) engine=innodb auto_increment=1 comment = '员工授权';

-- ----------------------------
-- 岗位
-- ----------------------------
drop table if exists kg_job;
create table kg_job (
  job_id            bigint(20)      not null auto_increment    comment '岗位ID',
  store_id          bigint(20)      not null                   comment '所属门店',
  job_type          varchar(16)     not null                   comment '岗位分类（volunteer/parttime/fulltime/online）',
  title             varchar(128)    not null                   comment '岗位标题',
  cover             varchar(255)    default ''                 comment '封面图',
  city              varchar(32)     not null                   comment '城市',
  address           varchar(255)    default ''                 comment '工作地点',
  salary_type       varchar(16)     default 'none'             comment '计薪方式（daily/monthly/piece/none）',
  salary_min        decimal(12,2)   default null               comment '薪资下限（元）',
  salary_max        decimal(12,2)   default null               comment '薪资上限（元）',
  salary_unit       varchar(16)     default null               comment '薪资单位文案（元/天、元/月、元/篇）',
  settle_desc       varchar(128)    default null               comment '结算方式说明',
  start_date        date                                       comment '可开始日期',
  min_days          int(11)         default null               comment '最短停留天数',
  daily_hours       varchar(32)     default null               comment '每日工时描述',
  shift_desc        varchar(128)    default null               comment '排班方式',
  board_type        varchar(16)     default 'none'             comment '食宿（all/room/meal/part/none）',
  board_desc        varchar(255)    default null               comment '食宿说明',
  insurance_flag    char(1)         default '1'                comment '是否含保险（0含 1不含）',
  headcount         int(11)         default 1                  comment '招聘名额',
  hired_count       int(11)         default 0                  comment '已录用人数',
  applied_count     int(11)         default 0                  comment '累计报名人数',
  tags              varchar(255)    default null               comment '标签，英文逗号分隔',
  content           text                                       comment '具体工作内容',
  requirement       text                                       comment '任职要求',
  status            varchar(16)     default 'draft'            comment '岗位状态（draft/published/paused/closed）',
  publish_time      datetime                                   comment '发布时间',
  close_time        datetime                                   comment '关闭时间',
  del_flag          char(1)         default '0'                comment '删除标志（0存在 2删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (job_id),
  key idx_job_query (status, del_flag, job_type, city),
  key idx_job_store (store_id)
) engine=innodb auto_increment=10000 comment = '岗位';

-- ----------------------------
-- 岗位收藏
-- ----------------------------
drop table if exists kg_job_favorite;
create table kg_job_favorite (
  fav_id            bigint(20)      not null auto_increment    comment '收藏ID',
  app_user_id       bigint(20)      not null                   comment '小程序账号ID',
  job_id            bigint(20)      not null                   comment '岗位ID',
  create_time       datetime                                   comment '收藏时间',
  primary key (fav_id),
  unique key uk_fav_user_job (app_user_id, job_id),
  key idx_fav_job (job_id)
) engine=innodb auto_increment=1 comment = '岗位收藏';

-- ----------------------------
-- 报名申请（岗位履约主单）
-- 状态机（严格单向，非法流转由服务端拒绝）：
--   submitted 已提交
--     -> review_passed 审核通过 | review_rejected 审核未通过 | withdrawn 用户撤回
--   review_passed     -> interview_booked 已预约面试
--   interview_booked  -> interview_done   面试已完成
--   interview_done    -> user_confirmed   用户已确认意向 | terminated 已终止
--   user_confirmed    -> store_confirmed  商家已确认录用 | terminated 已终止
--   store_confirmed   -> contract_signed  已签署责任书
--   contract_signed   -> onboarded        已入职
-- ----------------------------
drop table if exists kg_application;
create table kg_application (
  app_id            bigint(20)      not null auto_increment    comment '报名ID',
  app_no            varchar(32)     not null                   comment '报名单号',
  job_id            bigint(20)      not null                   comment '岗位ID',
  store_id          bigint(20)      not null                   comment '门店ID（提交时快照）',
  app_user_id       bigint(20)      not null                   comment '报名人账号ID',
  real_name         varchar(40)     not null                   comment '姓名',
  age               int(11)         not null                   comment '年龄',
  phone             varchar(20)     not null                   comment '联系手机号',
  skills            varchar(500)    default null               comment '技能与经历',
  arrival_date      date            not null                   comment '预计到岗日期',
  stay_days         int(11)         not null                   comment '可停留天数',
  note              varchar(500)    default null               comment '补充说明',
  status            varchar(24)     not null default 'submitted' comment '申请状态',
  submit_time       datetime                                   comment '提交时间',
  audit_time        datetime                                   comment '审核时间',
  audit_by          varchar(64)     default null               comment '审核人',
  audit_by_type     varchar(16)     default null               comment '审核人类型（staff门店员工 admin后台）',
  reject_reason     varchar(255)    default null               comment '未通过原因',
  withdraw_time     datetime                                   comment '撤回时间',
  user_intent       varchar(16)     default null               comment '面试后意向（accept/pending/reject）',
  user_confirm_time datetime                                   comment '用户确认意向时间',
  store_confirm_time datetime                                  comment '商家确认时间',
  confirm_deadline  datetime                                   comment '商家确认截止时间（用户确认 +24 小时）',
  terminate_reason  varchar(255)    default null               comment '终止原因',
  onboard_time      datetime                                   comment '入职时间',
  del_flag          char(1)         default '0'                comment '删除标志（0存在 2删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (app_id),
  unique key uk_app_no (app_no),
  key idx_app_user (app_user_id, status),
  key idx_app_store (store_id, status),
  key idx_app_job (job_id, status)
) engine=innodb auto_increment=1 comment = '岗位报名申请';

-- ----------------------------
-- 报名状态流转日志（任何状态变更都要落一条）
-- ----------------------------
drop table if exists kg_application_log;
create table kg_application_log (
  log_id            bigint(20)      not null auto_increment    comment '日志ID',
  app_id            bigint(20)      not null                   comment '报名ID',
  from_status       varchar(24)     default null               comment '原状态',
  to_status         varchar(24)     not null                   comment '新状态',
  operator_type     varchar(16)     not null                   comment '操作方（user/staff/admin/system）',
  operator_id       varchar(64)     default null               comment '操作人标识',
  operator_name     varchar(64)     default null               comment '操作人名称',
  reason            varchar(255)    default null               comment '原因/备注',
  create_time       datetime                                   comment '发生时间',
  primary key (log_id),
  key idx_applog_app (app_id, log_id)
) engine=innodb auto_increment=1 comment = '报名状态流转日志';

-- ----------------------------
-- 面试时段（由门店发布可选时段）
-- ----------------------------
drop table if exists kg_interview_slot;
create table kg_interview_slot (
  slot_id           bigint(20)      not null auto_increment    comment '时段ID',
  store_id          bigint(20)      not null                   comment '门店ID',
  job_id            bigint(20)      default null               comment '限定岗位（为空表示门店通用时段）',
  start_time        datetime        not null                   comment '开始时间',
  end_time          datetime        not null                   comment '结束时间',
  status            varchar(16)     default 'open'             comment '状态（open/booked/closed）',
  booked_app_id     bigint(20)      default null               comment '占用该时段的报名ID',
  del_flag          char(1)         default '0'                comment '删除标志（0存在 2删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (slot_id),
  unique key uk_slot_booked (booked_app_id),
  key idx_slot_store (store_id, status, start_time)
) engine=innodb auto_increment=1 comment = '面试时段';

-- ----------------------------
-- 面试预约
-- ----------------------------
drop table if exists kg_interview;
create table kg_interview (
  interview_id      bigint(20)      not null auto_increment    comment '面试ID',
  app_id            bigint(20)      not null                   comment '报名ID',
  slot_id           bigint(20)      not null                   comment '时段ID',
  start_time        datetime        not null                   comment '面试开始时间',
  end_time          datetime        not null                   comment '面试结束时间',
  status            varchar(16)     default 'booked'           comment '状态（booked/done/canceled）',
  finish_time       datetime                                   comment '实际结束时间',
  duration_seconds  int(11)         default null               comment '通话时长（秒）',
  understand_job    varchar(64)     default null               comment '面试后确认：岗位理解',
  accept_board      varchar(64)     default null               comment '面试后确认：食宿条件',
  confirm_arrival   varchar(64)     default null               comment '面试后确认：到岗日期',
  feedback_note     varchar(200)    default null               comment '面试后备注',
  del_flag          char(1)         default '0'                comment '删除标志（0存在 2删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (interview_id),
  unique key uk_interview_app (app_id),
  key idx_interview_slot (slot_id)
) engine=innodb auto_increment=1 comment = '面试预约';

-- ----------------------------
-- 站内消息（小程序消息中心唯一数据源）
-- ----------------------------
drop table if exists kg_message;
create table kg_message (
  msg_id            bigint(20)      not null auto_increment    comment '消息ID',
  recipient_id      bigint(20)      not null                   comment '接收人（kg_app_user.user_id）',
  mode              varchar(16)     not null default 'user'    comment '身份（user用户端 staff员工端）',
  category          varchar(24)     not null                   comment '分类（system/apply/interview/contract/order/chat）',
  title             varchar(128)    not null                   comment '标题',
  content           varchar(500)    default ''                 comment '正文',
  biz_type          varchar(24)     default null               comment '关联业务类型',
  biz_id            bigint(20)      default null               comment '关联业务ID',
  route             varchar(128)    default null               comment '点击跳转的小程序路由',
  read_flag         char(1)         default '0'                comment '已读标志（0未读 1已读）',
  read_time         datetime                                   comment '阅读时间',
  del_flag          char(1)         default '0'                comment '删除标志（0存在 2删除）',
  create_time       datetime                                   comment '创建时间',
  primary key (msg_id),
  key idx_msg_recipient (recipient_id, mode, read_flag, del_flag),
  key idx_msg_biz (biz_type, biz_id)
) engine=innodb auto_increment=1 comment = '站内消息';

-- ----------------------------
-- 幂等键（关键写操作防重复提交）
-- ----------------------------
drop table if exists kg_idempotency;
create table kg_idempotency (
  id                bigint(20)      not null auto_increment    comment '主键',
  idem_key          varchar(128)    not null                   comment '幂等键（业务维度唯一）',
  biz_type          varchar(32)     not null                   comment '业务类型',
  biz_id            bigint(20)      default null               comment '首次成功产生的业务主键',
  create_time       datetime                                   comment '创建时间',
  primary key (id),
  unique key uk_idem_key (idem_key)
) engine=innodb auto_increment=1 comment = '幂等键';
