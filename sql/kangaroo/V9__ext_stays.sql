-- =============================================================================
-- V9__ext_stays（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('8');

-- 旅居：房源、房型、房态价格日历、早鸟特价、旅居订单（含核销码、退款申请）、增值服务与服务订单。
-- 金额一律整数分（_fen）。后台菜单段 2200–2299「旅居」。可重复执行。

create table if not exists kg_stay (
  stay_id           bigint        not null auto_increment comment '房源ID',
  store_id          bigint        not null                comment '门店',
  stay_name         varchar(64)   not null                comment '房源名称',
  city              varchar(32)   not null default ''     comment '城市',
  area              varchar(32)   not null default ''     comment '商圈 / 区域',
  area_desc         varchar(64)   not null default ''     comment '位置描述（例：喜洲古镇 · 步行3分钟到稻田）',
  address           varchar(128)  not null default ''     comment '详细地址',
  latitude          decimal(10,6) null                    comment '纬度（门店录入，用于打开地图）',
  longitude         decimal(10,6) null                    comment '经度',
  cover             varchar(255)  not null default ''     comment '封面',
  images            varchar(1000) null                    comment '图集（逗号分隔）',
  tags              varchar(255)  null                    comment '标签（逗号分隔）',
  features          varchar(255)  null                    comment '特色（逗号分隔，最多 3 项展示）',
  facilities        varchar(255)  null                    comment '设施（字典 kg_stay_facility 的值，逗号分隔）',
  room_kind         varchar(16)   not null default 'private' comment '房型大类 whole 整院 / private 独立房间 / shared 多人间',
  cookable          char(1)       not null default '0'    comment '可做饭 1 是',
  kitchen           char(1)       not null default '0'    comment '带厨房 1 是',
  scenic_distance_m int           null                    comment '距核心景点距离（米，门店录入，不做用户定位）',
  long_stay_nights  int           not null default 0      comment '长住优惠起始晚数（0 不设）',
  long_stay_rate    int           not null default 100    comment '长住折扣（百分比，90 = 9 折）',
  slogan            varchar(64)   null                    comment '标语',
  slogan_sub        varchar(128)  null                    comment '副标语',
  host_name         varchar(32)   null                    comment '主理人对外称呼',
  host_avatar       varchar(255)  null                    comment '主理人头像',
  host_intro        varchar(128)  null                    comment '主理人一句话',
  host_story        varchar(1000) null                    comment '小院故事',
  checkin_time      varchar(5)    not null default '15:00' comment '入住时间',
  checkout_time     varchar(5)    not null default '12:00' comment '退房时间',
  cancel_policy     varchar(16)   not null default 'moderate' comment '取消政策 flexible / moderate / strict / nonrefundable',
  group_name        varchar(64)   null                    comment '住客群名称',
  group_qr          varchar(255)  null                    comment '住客群二维码图片',
  group_qr_expire   date          null                    comment '群码有效期至',
  status            varchar(16)   not null default 'draft' comment 'draft 草稿 / pending 待审核 / online 已上线 / offline 已下架 / rejected 已驳回',
  audit_reason      varchar(255)  null                    comment '驳回 / 下架原因',
  rating            decimal(2,1)  null                    comment '评分（评价模块回写）',
  review_count      int           not null default 0      comment '评价数',
  completed_orders  int           not null default 0      comment '累计完成订单',
  sort              int           not null default 0      comment '排序',
  create_by         varchar(64)   default ''              comment '创建者',
  create_time       datetime                              comment '创建时间',
  update_by         varchar(64)   default ''              comment '更新者',
  update_time       datetime                              comment '更新时间',
  del_flag          char(1)       default '0'             comment '删除标志',
  remark            varchar(255)  null                    comment '备注',
  primary key (stay_id),
  key idx_stay_city (city, status),
  key idx_stay_store (store_id)
) engine=innodb comment='旅居房源';
call kg_assert_same_collation('kg_stay');

create table if not exists kg_stay_room_type (
  room_type_id   bigint       not null auto_increment comment '房型ID',
  stay_id        bigint       not null                comment '房源',
  type_name      varchar(64)  not null                comment '房型名称',
  room_kind      varchar(16)  not null default 'private' comment 'whole 整院 / private 独立房间 / shared 多人间',
  summary        varchar(128) not null default ''     comment '一句话描述',
  bed_desc       varchar(64)  not null default ''     comment '床型',
  area_sqm       int          null                    comment '面积（平方米）',
  capacity       int          not null default 2      comment '可住人数',
  unit_label     varchar(8)   not null default '间'   comment '库存单位：间 / 床位',
  stock          int          not null default 1      comment '默认每晚库存',
  base_price_fen bigint       not null                comment '基础晚价（分）',
  cover          varchar(255) not null default ''     comment '房型图',
  features       varchar(255) null                    comment '房型亮点（逗号分隔）',
  status         char(1)      not null default '0'    comment '0 上架 1 下架',
  sort           int          not null default 0      comment '排序',
  create_by      varchar(64)  default ''              comment '创建者',
  create_time    datetime                             comment '创建时间',
  update_by      varchar(64)  default ''              comment '更新者',
  update_time    datetime                             comment '更新时间',
  del_flag       char(1)      default '0'             comment '删除标志',
  primary key (room_type_id),
  key idx_rt_stay (stay_id)
) engine=innodb comment='房型';
call kg_assert_same_collation('kg_stay_room_type');

create table if not exists kg_stay_calendar (
  cal_id       bigint   not null auto_increment comment 'ID',
  room_type_id bigint   not null                comment '房型',
  stay_id      bigint   not null                comment '房源',
  stay_date    date     not null                comment '日期（当晚）',
  price_fen    bigint   null                    comment '当晚价（空 = 房型基础价）',
  stock        int      null                    comment '当晚库存（空 = 房型默认库存）',
  occupied     int      not null default 0      comment '已占用（待支付 + 已确认）',
  closed       char(1)  not null default '0'    comment '1 关房',
  create_by    varchar(64) default ''           comment '创建者',
  create_time  datetime                         comment '创建时间',
  update_by    varchar(64) default ''           comment '更新者',
  update_time  datetime                         comment '更新时间',
  primary key (cal_id),
  unique key uk_cal_day (room_type_id, stay_date),
  constraint ck_cal_occupied check (occupied >= 0)
) engine=innodb comment='房态价格日历（一房型一晚一行）';
call kg_assert_same_collation('kg_stay_calendar');

create table if not exists kg_stay_promo (
  promo_id     bigint       not null auto_increment comment '特价ID',
  stay_id      bigint       not null                comment '房源',
  room_type_id bigint       not null                comment '房型',
  title        varchar(64)  not null default ''     comment '标题',
  price_fen    bigint       not null                comment '特价晚价（分）',
  quota        int          not null default 0      comment '可售间夜单数',
  sold         int          not null default 0      comment '已售单数',
  stay_start   date         not null                comment '适用入住日起',
  stay_end     date         not null                comment '适用入住日止',
  sale_end     datetime     not null                comment '售卖截止',
  refundable   char(1)      not null default '0'    comment '1 可取消（按房源政策） 0 不可退',
  status       char(1)      not null default '0'    comment '0 启用 1 停用',
  create_by    varchar(64)  default ''              comment '创建者',
  create_time  datetime                             comment '创建时间',
  update_by    varchar(64)  default ''              comment '更新者',
  update_time  datetime                             comment '更新时间',
  del_flag     char(1)      default '0'             comment '删除标志',
  primary key (promo_id),
  key idx_promo_stay (stay_id)
) engine=innodb comment='早鸟特价';
call kg_assert_same_collation('kg_stay_promo');

create table if not exists kg_stay_order (
  order_id          bigint        not null auto_increment comment '订单ID',
  order_no          varchar(32)   not null                comment '订单号',
  stay_id           bigint        not null                comment '房源',
  store_id          bigint        not null                comment '门店',
  room_type_id      bigint        not null                comment '房型',
  promo_id          bigint        null                    comment '早鸟特价',
  app_user_id       bigint        not null                comment '下单人',
  check_in          date          not null                comment '入住日',
  check_out         date          not null                comment '离店日',
  nights            int           not null                comment '晚数',
  guests            int           not null default 1      comment '入住人数',
  units             int           not null default 1      comment '间数 / 床位数',
  guest_name        varchar(32)   not null                comment '入住人',
  guest_phone       varchar(20)   not null                comment '联系电话',
  arrive_time       varchar(16)   null                    comment '预计到店',
  remark            varchar(200)  null                    comment '特殊需求',
  room_fen          bigint        not null default 0      comment '房费（分）',
  addon_fen         bigint        not null default 0      comment '加购（分）',
  discount_fen      bigint        not null default 0      comment '优惠（分）',
  discount_label    varchar(64)   null                    comment '优惠说明',
  service_fee_fen   bigint        not null default 0      comment '平台服务费（分）',
  pay_fen           bigint        not null                comment '应付（分）',
  nightly           varchar(2000) null                    comment '逐晚价格快照（JSON）',
  policy_snapshot   varchar(1000) null                    comment '取消政策快照（JSON）',
  stay_snapshot     varchar(1000) null                    comment '房源房型快照（JSON）',
  agreement_version varchar(16)   null                    comment '同意的预订须知版本',
  status            varchar(20)   not null default 'pending_payment' comment 'pending_payment / confirmed / checked_in / completed / no_show / closed / refund_pending / refunding / cancelled',
  pay_no            varchar(32)   null                    comment '支付单号',
  paid_time         datetime      null                    comment '支付时间',
  expire_time       datetime      null                    comment '待支付截止',
  verify_code       varchar(16)   null                    comment '核销码（已支付后生成）',
  verify_time       datetime      null                    comment '核销时间',
  verified_by       varchar(64)   null                    comment '核销员工',
  refund_fen        bigint        not null default 0      comment '退款金额（分）',
  refund_reason     varchar(200)  null                    comment '退款原因',
  refund_apply_time datetime      null                    comment '申请退款时间',
  refund_note       varchar(200)  null                    comment '退款处理说明',
  refund_by         varchar(64)   null                    comment '退款处理人',
  refund_time       datetime      null                    comment '退款完成时间',
  close_time        datetime      null                    comment '关闭时间',
  close_reason      varchar(64)   null                    comment '关闭原因',
  create_by         varchar(64)   default ''              comment '创建者',
  create_time       datetime                              comment '创建时间',
  update_by         varchar(64)   default ''              comment '更新者',
  update_time       datetime                              comment '更新时间',
  del_flag          char(1)       default '0'             comment '删除标志',
  primary key (order_id),
  unique key uk_stay_order_no (order_no),
  unique key uk_stay_verify (verify_code),
  key idx_so_user (app_user_id, status),
  key idx_so_store (store_id, status),
  key idx_so_expire (status, expire_time)
) engine=innodb comment='旅居订单';
call kg_assert_same_collation('kg_stay_order');

create table if not exists kg_shop_service (
  service_id       bigint       not null auto_increment comment '服务ID',
  store_id         bigint       not null                comment '门店',
  category         varchar(16)  not null                comment '类目（字典 kg_shop_category）',
  service_name     varchar(64)  not null                comment '服务名称',
  cover            varchar(255) not null default ''     comment '封面',
  price_fen        bigint       not null                comment '单价（分）',
  unit_label       varchar(8)   not null default '人'   comment '单位',
  spec_label       varchar(32)  not null default '标准' comment '规格',
  duration_min     int          null                    comment '时长（分钟）',
  content          varchar(1000) null                   comment '服务内容',
  location         varchar(128) null                    comment '服务地点',
  badges           varchar(255) null                    comment '徽标（逗号分隔）',
  advance_hours    int          not null default 2      comment '需提前预约小时数',
  time_slots       varchar(255) null                    comment '可约时段（逗号分隔，如 10:00-11:30）',
  slot_capacity    int          not null default 10     comment '每时段名额',
  reschedule_limit int          not null default 1      comment '可免费改期次数',
  refundable       char(1)      not null default '1'    comment '1 未核销可退款',
  sold_total       int          not null default 0      comment '累计售出份数',
  status           char(1)      not null default '0'    comment '0 上架 1 下架',
  sort             int          not null default 0      comment '排序',
  create_by        varchar(64)  default ''              comment '创建者',
  create_time      datetime                             comment '创建时间',
  update_by        varchar(64)  default ''              comment '更新者',
  update_time      datetime                             comment '更新时间',
  del_flag         char(1)      default '0'             comment '删除标志',
  primary key (service_id),
  key idx_svc_store (store_id, category)
) engine=innodb comment='增值服务（入住期间可预订）';
call kg_assert_same_collation('kg_shop_service');

create table if not exists kg_shop_order (
  order_id          bigint       not null auto_increment comment '订单ID',
  order_no          varchar(32)  not null                comment '订单号',
  service_id        bigint       not null                comment '服务',
  store_id          bigint       not null                comment '门店',
  stay_order_no     varchar(32)  not null                comment '关联的入住订单',
  app_user_id       bigint       not null                comment '下单人',
  qty               int          not null default 1      comment '数量',
  unit_fen          bigint       not null                comment '单价（分）',
  pay_fen           bigint       not null                comment '应付（分）',
  user_name         varchar(32)  not null                comment '使用人',
  user_phone        varchar(20)  not null                comment '联系电话（沿用入住单）',
  use_date          date         not null                comment '使用日期',
  time_slot         varchar(16)  not null                comment '时段',
  remark            varchar(100) null                    comment '备注',
  status            varchar(20)  not null default 'pending_payment' comment 'pending_payment / unused / used / refund_pending / refunded / closed',
  pay_no            varchar(32)  null                    comment '支付单号',
  paid_time         datetime     null                    comment '支付时间',
  expire_time       datetime     null                    comment '待支付截止',
  verify_code       varchar(16)  null                    comment '核销码',
  verify_time       datetime     null                    comment '核销时间',
  verified_by       varchar(64)  null                    comment '核销员工',
  reschedule_count  int          not null default 0      comment '已改期次数',
  refund_fen        bigint       not null default 0      comment '退款金额（分）',
  refund_reason     varchar(200) null                    comment '退款原因',
  refund_apply_time datetime     null                    comment '申请退款时间',
  refund_note       varchar(200) null                    comment '退款处理说明',
  refund_by         varchar(64)  null                    comment '退款处理人',
  refund_time       datetime     null                    comment '退款完成时间',
  service_snapshot  varchar(1000) null                   comment '服务快照（JSON）',
  create_by         varchar(64)  default ''              comment '创建者',
  create_time       datetime                             comment '创建时间',
  update_by         varchar(64)  default ''              comment '更新者',
  update_time       datetime                             comment '更新时间',
  del_flag          char(1)      default '0'             comment '删除标志',
  primary key (order_id),
  unique key uk_shop_order_no (order_no),
  unique key uk_shop_verify (verify_code),
  key idx_sho_user (app_user_id, status),
  key idx_sho_store (store_id, status),
  key idx_sho_slot (service_id, use_date, time_slot)
) engine=innodb comment='增值服务订单';
call kg_assert_same_collation('kg_shop_order');

create table if not exists kg_stay_vlog (
  vlog_id          bigint       not null auto_increment comment 'Vlog ID',
  stay_id          bigint       not null                comment '房源',
  store_id         bigint       not null                comment '门店',
  title            varchar(64)  not null                comment '标题',
  cover            varchar(255) not null default ''     comment '封面',
  description      varchar(255) null                    comment '简介',
  platform         varchar(16)  not null default 'channels' comment 'channels 视频号 / douyin 抖音 / xhs 小红书',
  link             varchar(500) null                    comment '外部链接（抖音 / 小红书：复制后到对应 App 打开，小程序内不打开外链）',
  finder_user_name varchar(64)  null                    comment '视频号 ID（小程序与视频号同主体时可直接打开）',
  feed_id          varchar(128) null                    comment '视频号视频 ID',
  author_name      varchar(32)  not null default ''     comment '作者显示名',
  author_avatar    varchar(255) null                    comment '作者头像',
  author_role      varchar(16)  not null default 'host' comment 'host 主理人 / guest 住客 / staff 员工',
  duration_sec     int          null                    comment '时长（秒，提交时填写）',
  orientation      varchar(8)   null                    comment '竖版 / 横版（提交时填写）',
  quality          varchar(8)   null                    comment '画质（提交时填写）',
  scenes           varchar(128) null                    comment '场景（提交时填写）',
  highlights       varchar(128) null                    comment '亮点（提交时填写）',
  authorized       char(1)      not null default '0'    comment '1 已取得出镜住客授权',
  authorizer       varchar(64)  null                    comment '授权人',
  authorized_date  date         null                    comment '授权日期',
  order_no         varchar(32)  null                    comment '关联订单（住客投稿必填）',
  published_time   datetime     null                    comment '原平台发布时间',
  status           varchar(16)  not null default 'pending' comment 'pending 待审核 / online 已上线 / rejected 已驳回 / offline 已下架',
  audit_reason     varchar(255) null                    comment '驳回 / 下架原因',
  views            int          not null default 0      comment '小程序内打开次数',
  likes            int          not null default 0      comment '点赞数（小程序内）',
  sort             int          not null default 0      comment '排序',
  create_by        varchar(64)  default ''              comment '创建者',
  create_time      datetime                             comment '创建时间',
  update_by        varchar(64)  default ''              comment '更新者',
  update_time      datetime                             comment '更新时间',
  del_flag         char(1)      default '0'             comment '删除标志',
  primary key (vlog_id),
  key idx_vlog_stay (stay_id, status),
  key idx_vlog_store (store_id, status)
) engine=innodb comment='房源 Vlog（外部平台视频的引用，经平台审核后展示）';
call kg_assert_same_collation('kg_stay_vlog');

call kg_add_col_if_absent('kg_stay_vlog', 'duration_sec', 'duration_sec int null comment ''时长（秒，提交时填写）''');
call kg_add_col_if_absent('kg_stay_vlog', 'orientation', 'orientation varchar(8) null comment ''竖版 / 横版''');
call kg_add_col_if_absent('kg_stay_vlog', 'quality', 'quality varchar(8) null comment ''画质''');
call kg_add_col_if_absent('kg_stay_vlog', 'scenes', 'scenes varchar(128) null comment ''场景''');
call kg_add_col_if_absent('kg_stay_vlog', 'highlights', 'highlights varchar(128) null comment ''亮点''');
call kg_add_col_if_absent('kg_stay_vlog', 'authorized', 'authorized char(1) not null default ''0'' comment ''1 已取得出镜住客授权''');
call kg_add_col_if_absent('kg_stay_vlog', 'authorizer', 'authorizer varchar(64) null comment ''授权人''');
call kg_add_col_if_absent('kg_stay_vlog', 'authorized_date', 'authorized_date date null comment ''授权日期''');
call kg_add_col_if_absent('kg_stay_vlog', 'order_no', 'order_no varchar(32) null comment ''关联订单''');
call kg_add_col_if_absent('kg_stay_vlog', 'published_time', 'published_time datetime null comment ''原平台发布时间''');

create table if not exists kg_stay_vlog_event (
  event_id  bigint      not null auto_increment comment 'ID',
  vlog_id   bigint      not null                comment 'Vlog',
  store_id  bigint      not null                comment '门店',
  event     varchar(16) not null                comment 'open 打开 / like 点赞 / detail 进房源详情 / order 下单',
  day       date        not null                comment '日期',
  cnt       int         not null default 0      comment '次数',
  primary key (event_id),
  unique key uk_vlog_event (vlog_id, event, day),
  key idx_vlog_event_store (store_id, day)
) engine=innodb comment='Vlog 小程序内行为按天计数（外部平台的播放数据需接平台接口，不在此表）';
call kg_assert_same_collation('kg_stay_vlog_event');

create table if not exists kg_stay_vlog_comment (
  comment_id  bigint       not null auto_increment comment '评论ID',
  vlog_id     bigint       not null                comment 'Vlog',
  store_id    bigint       not null                comment '门店',
  app_user_id bigint       not null                comment '评论人',
  content     varchar(200) not null                comment '内容',
  reply       varchar(200) null                    comment '门店回复',
  reply_by    varchar(64)  null                    comment '回复人',
  reply_time  datetime     null                    comment '回复时间',
  status      varchar(16)  not null default 'visible' comment 'visible 显示 / hidden 已隐藏',
  reported    int          not null default 0      comment '被举报次数',
  handle_note varchar(128) null                    comment '处理记录',
  handled_by  varchar(64)  null                    comment '处理人',
  handled_time datetime    null                    comment '处理时间',
  create_time datetime                             comment '评论时间',
  primary key (comment_id),
  key idx_vc_vlog (vlog_id, status),
  key idx_vc_store (store_id, create_time)
) engine=innodb comment='Vlog 评论';
call kg_assert_same_collation('kg_stay_vlog_comment');

call kg_add_col_if_absent('kg_stay', 'submit_time', 'submit_time datetime null comment ''最近一次提交审核时间''');
call kg_add_col_if_absent('kg_stay', 'audit_time', 'audit_time datetime null comment ''最近一次审核时间''');

create table if not exists kg_stay_qualification (
  qual_id     bigint       not null auto_increment comment '资质ID',
  stay_id     bigint       not null                comment '房源',
  store_id    bigint       not null                comment '门店',
  qual_type   varchar(16)  not null                comment 'license 营业执照 / fire 消防安全检查合格证 / special 特种行业许可证 / idcard 负责人身份证',
  file_key    varchar(128) not null                comment '私有存储文件键（不公开访问，只能经签名链接查看）',
  file_name   varchar(128) null                    comment '原文件名',
  file_size   bigint       null                    comment '文件大小（字节）',
  expire_date date         null                    comment '有效期至',
  status      char(1)      not null default '0'    comment '0 有效 1 已被新文件替换',
  create_by   varchar(64)  default ''              comment '上传人',
  create_time datetime                             comment '上传时间',
  update_by   varchar(64)  default ''              comment '更新者',
  update_time datetime                             comment '更新时间',
  primary key (qual_id),
  key idx_qual_stay (stay_id, qual_type, status)
) engine=innodb comment='房源资质证照（私有存储）';
call kg_assert_same_collation('kg_stay_qualification');

-- 字典
delete from sys_dict_data where dict_type in ('kg_stay_status', 'kg_stay_order_status', 'kg_shop_order_status', 'kg_shop_category',
  'kg_cancel_policy', 'kg_room_kind', 'kg_stay_facility');
delete from sys_dict_type where dict_type in ('kg_stay_status', 'kg_stay_order_status', 'kg_shop_order_status', 'kg_shop_category',
  'kg_cancel_policy', 'kg_room_kind', 'kg_stay_facility');
insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark) values
('袋鼠-房源状态',     'kg_stay_status',       '0', 'admin', sysdate(), ''),
('袋鼠-旅居订单状态', 'kg_stay_order_status', '0', 'admin', sysdate(), ''),
('袋鼠-服务订单状态', 'kg_shop_order_status', '0', 'admin', sysdate(), ''),
('袋鼠-增值服务类目', 'kg_shop_category',     '0', 'admin', sysdate(), '字典项"停用"= 该类目暂未开放（资质未齐）'),
('袋鼠-取消政策',     'kg_cancel_policy',     '0', 'admin', sysdate(), ''),
('袋鼠-房型大类',     'kg_room_kind',         '0', 'admin', sysdate(), ''),
('袋鼠-房源设施',     'kg_stay_facility',     '0', 'admin', sysdate(), '');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) values
(1, '草稿',   'draft',    'kg_stay_status', '', 'info',    'Y', '0', 'admin', sysdate(), ''),
(2, '待审核', 'pending',  'kg_stay_status', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(3, '已上线', 'online',   'kg_stay_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(4, '已下架', 'offline',  'kg_stay_status', '', 'info',    'N', '0', 'admin', sysdate(), ''),
(5, '已驳回', 'rejected', 'kg_stay_status', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(1, '待支付',   'pending_payment', 'kg_stay_order_status', '', 'warning', 'Y', '0', 'admin', sysdate(), ''),
(2, '待入住',   'confirmed',       'kg_stay_order_status', '', 'primary', 'N', '0', 'admin', sysdate(), ''),
(3, '已入住',   'checked_in',      'kg_stay_order_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(4, '已完成',   'completed',       'kg_stay_order_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(5, '未到店',   'no_show',         'kg_stay_order_status', '', 'info',    'N', '0', 'admin', sysdate(), ''),
(6, '已关闭',   'closed',          'kg_stay_order_status', '', 'info',    'N', '0', 'admin', sysdate(), ''),
(7, '退款审核中', 'refund_pending', 'kg_stay_order_status', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(8, '退款中',   'refunding',       'kg_stay_order_status', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(9, '已取消退款', 'cancelled',      'kg_stay_order_status', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(1, '待支付',     'pending_payment', 'kg_shop_order_status', '', 'warning', 'Y', '0', 'admin', sysdate(), ''),
(2, '待使用',     'unused',          'kg_shop_order_status', '', 'primary', 'N', '0', 'admin', sysdate(), ''),
(3, '已核销',     'used',            'kg_shop_order_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(4, '退款审核中', 'refund_pending',  'kg_shop_order_status', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(5, '已退款',     'refunded',        'kg_shop_order_status', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(6, '已关闭',     'closed',          'kg_shop_order_status', '', 'info',    'N', '0', 'admin', sysdate(), ''),
(1, '出行服务', 'transport', 'kg_shop_category', '', '', 'N', '1', 'admin', sysdate(), '租车/接站需道路运输类资质，默认关闭'),
(2, '生活服务', 'life',      'kg_shop_category', '', '', 'Y', '0', 'admin', sysdate(), ''),
(3, '体验课程', 'course',    'kg_shop_category', '', '', 'N', '0', 'admin', sysdate(), ''),
(4, '餐饮服务', 'food',      'kg_shop_category', '', '', 'N', '1', 'admin', sysdate(), '需食品经营许可，默认关闭'),
(5, '设备用品', 'goods',     'kg_shop_category', '', '', 'N', '1', 'admin', sysdate(), '实物销售需电商资质，默认关闭'),
(1, '灵活（入住前1天免费取消）', 'flexible',      'kg_cancel_policy', '', '', 'N', '0', 'admin', sysdate(), ''),
(2, '适中（入住前7天免费，之后扣首晚）', 'moderate', 'kg_cancel_policy', '', '', 'Y', '0', 'admin', sysdate(), ''),
(3, '严格（入住前7天免费，之后扣50%）', 'strict',   'kg_cancel_policy', '', '', 'N', '0', 'admin', sysdate(), ''),
(4, '不可退',   'nonrefundable', 'kg_cancel_policy', '', '', 'N', '0', 'admin', sysdate(), ''),
(1, '整院',     'whole',   'kg_room_kind', '', '', 'N', '0', 'admin', sysdate(), ''),
(2, '独立房间', 'private', 'kg_room_kind', '', '', 'Y', '0', 'admin', sysdate(), ''),
(3, '多人间',   'shared',  'kg_room_kind', '', '', 'N', '0', 'admin', sysdate(), ''),
(1, '独立卫浴', 'bath',    'kg_stay_facility', '', '', 'N', '0', 'admin', sysdate(), ''),
(2, '空调',     'ac',      'kg_stay_facility', '', '', 'N', '0', 'admin', sysdate(), ''),
(3, 'Wi-Fi',    'wifi',    'kg_stay_facility', '', '', 'N', '0', 'admin', sysdate(), ''),
(4, '洗衣机',   'washer',  'kg_stay_facility', '', '', 'N', '0', 'admin', sysdate(), ''),
(5, '停车',     'parking', 'kg_stay_facility', '', '', 'N', '0', 'admin', sysdate(), ''),
(6, '阳台',     'balcony', 'kg_stay_facility', '', '', 'N', '0', 'admin', sysdate(), ''),
(7, '热水',     'hotwater', 'kg_stay_facility', '', '', 'N', '0', 'admin', sysdate(), '');

-- 菜单 2200–2299
delete from sys_menu where menu_id between 2200 and 2299;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2200, '旅居', 0, 9, 'kgstays', null, '', '', 1, 0, 'M', '0', '0', '', 'guide', 'admin', sysdate(), '房源、房型、房态、订单、增值服务'),
(2210, '房源管理', 2200, 1, 'stay', 'kangaroo/ext/stay/index', '', 'KgExtStay', 1, 0, 'C', '0', '0', 'kangaroo:stay:list', 'build', 'admin', sysdate(), ''),
(2211, '房源查询', 2210, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stay:query',  '#', 'admin', sysdate(), ''),
(2212, '房源新增', 2210, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stay:add',    '#', 'admin', sysdate(), ''),
(2213, '房源修改', 2210, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stay:edit',   '#', 'admin', sysdate(), ''),
(2214, '房源删除', 2210, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stay:remove', '#', 'admin', sysdate(), ''),
(2215, '房源审核', 2210, 5, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stay:audit',  '#', 'admin', sysdate(), ''),
(2220, '房型管理', 2200, 2, 'roomtype', 'kangaroo/ext/roomtype/index', '', 'KgExtRoomType', 1, 0, 'C', '0', '0', 'kangaroo:roomtype:list', 'tree-table', 'admin', sysdate(), ''),
(2221, '房型查询', 2220, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:roomtype:query',  '#', 'admin', sysdate(), ''),
(2222, '房型新增', 2220, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:roomtype:add',    '#', 'admin', sysdate(), ''),
(2223, '房型修改', 2220, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:roomtype:edit',   '#', 'admin', sysdate(), ''),
(2224, '房型删除', 2220, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:roomtype:remove', '#', 'admin', sysdate(), ''),
(2230, '房态价格', 2200, 3, 'staycal', 'kangaroo/ext/staycal/index', '', 'KgExtStayCal', 1, 0, 'C', '0', '0', 'kangaroo:staycal:list', 'date', 'admin', sysdate(), ''),
(2231, '房态查询', 2230, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staycal:query', '#', 'admin', sysdate(), ''),
(2232, '房态修改', 2230, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staycal:edit',  '#', 'admin', sysdate(), ''),
(2240, '早鸟特价', 2200, 4, 'staypromo', 'kangaroo/ext/staypromo/index', '', 'KgExtStayPromo', 1, 0, 'C', '0', '0', 'kangaroo:staypromo:list', 'star', 'admin', sysdate(), ''),
(2241, '特价查询', 2240, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staypromo:query',  '#', 'admin', sysdate(), ''),
(2242, '特价新增', 2240, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staypromo:add',    '#', 'admin', sysdate(), ''),
(2243, '特价修改', 2240, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staypromo:edit',   '#', 'admin', sysdate(), ''),
(2244, '特价删除', 2240, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staypromo:remove', '#', 'admin', sysdate(), ''),
(2250, '旅居订单', 2200, 5, 'stayorder', 'kangaroo/ext/stayorder/index', '', 'KgExtStayOrder', 1, 0, 'C', '0', '0', 'kangaroo:stayorder:list', 'shopping', 'admin', sysdate(), ''),
(2251, '订单查询', 2250, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stayorder:query',  '#', 'admin', sysdate(), ''),
(2252, '退款处理', 2250, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stayorder:refund', '#', 'admin', sysdate(), ''),
(2260, '增值服务', 2200, 6, 'shopservice', 'kangaroo/ext/shopservice/index', '', 'KgExtShopService', 1, 0, 'C', '0', '0', 'kangaroo:shopservice:list', 'shopping', 'admin', sysdate(), ''),
(2261, '服务查询', 2260, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:shopservice:query',  '#', 'admin', sysdate(), ''),
(2262, '服务新增', 2260, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:shopservice:add',    '#', 'admin', sysdate(), ''),
(2263, '服务修改', 2260, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:shopservice:edit',   '#', 'admin', sysdate(), ''),
(2264, '服务删除', 2260, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:shopservice:remove', '#', 'admin', sysdate(), ''),
(2270, '服务订单', 2200, 7, 'shoporder', 'kangaroo/ext/shoporder/index', '', 'KgExtShopOrder', 1, 0, 'C', '0', '0', 'kangaroo:shoporder:list', 'list', 'admin', sysdate(), ''),
(2271, '订单查询', 2270, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:shoporder:query',  '#', 'admin', sysdate(), ''),
(2272, '退款处理', 2270, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:shoporder:refund', '#', 'admin', sysdate(), ''),
(2280, 'Vlog 审核', 2200, 8, 'stayvlog', 'kangaroo/ext/stayvlog/index', '', 'KgExtStayVlog', 1, 0, 'C', '0', '0', 'kangaroo:stayvlog:list', 'eye-open', 'admin', sysdate(), ''),
(2281, 'Vlog 查询', 2280, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stayvlog:query',  '#', 'admin', sysdate(), ''),
(2282, 'Vlog 修改', 2280, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stayvlog:edit',   '#', 'admin', sysdate(), ''),
(2283, 'Vlog 删除', 2280, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stayvlog:remove', '#', 'admin', sysdate(), ''),
(2284, 'Vlog 审核', 2280, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stayvlog:audit',  '#', 'admin', sysdate(), ''),
(2290, '房源资质', 2200, 9, 'stayqual', 'kangaroo/ext/stayqual/index', '', 'KgExtStayQual', 1, 0, 'C', '0', '0', 'kangaroo:stayqual:list', 'documentation', 'admin', sysdate(), ''),
(2291, '资质查询', 2290, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stayqual:query', '#', 'admin', sysdate(), ''),
(2292, '查看文件', 2290, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:stayqual:view',  '#', 'admin', sysdate(), '');

-- 演示数据：三家门店各一处已上线房源 + 房型 + 一档早鸟 + 两项增值服务（只在不存在时插入，可重复执行）
insert into kg_stay (stay_id, store_id, stay_name, city, area, area_desc, address, latitude, longitude, cover, images, tags, features, facilities,
  room_kind, cookable, kitchen, scenic_distance_m, long_stay_nights, long_stay_rate, slogan, slogan_sub, host_name, host_avatar, host_intro, host_story,
  checkin_time, checkout_time, cancel_policy, group_name, status, sort, create_by, create_time)
select 1, 110, '古城拾光 · 院子客房', '大理', '大理古城', '大理古城 · 步行5分钟到人民路', '大理古城复兴路 88 号', 25.694000, 100.163000,
  '/static/media/rooms-cover-1.jpg', '/static/media/rooms-cover-1.jpg,/static/media/rooms-cover-2.jpg', '安静,近古城,可长住', '咖啡馆楼上,手冲咖啡免费,苍山日落', 'bath,hotwater,wifi,ac,washer',
  'private', '0', '0', 800, 7, 90, '住在咖啡香里', '每天早上一杯手冲', '阿拾', '/static/media/profile-avatar.jpg', '做了十年咖啡的店主', '院子是老宅改的，楼下是咖啡馆，楼上三间客房。',
  '14:00', '12:00', 'moderate', '古城拾光 住客群', 'online', 1, 'V9', now() from dual where not exists (select 1 from kg_stay where stay_id = 1);
insert into kg_stay (stay_id, store_id, stay_name, city, area, area_desc, address, latitude, longitude, cover, images, tags, features, facilities,
  room_kind, cookable, kitchen, scenic_distance_m, long_stay_nights, long_stay_rate, slogan, slogan_sub, host_name, host_avatar, host_intro, host_story,
  checkin_time, checkout_time, cancel_policy, group_name, status, sort, create_by, create_time)
select 2, 111, '双廊听海 · 海景民宿', '大理', '双廊古镇', '双廊古镇 · 推窗见洱海', '大理双廊镇玉几岛路 12 号', 25.915000, 100.195000,
  '/static/media/rooms-cover-2.jpg', '/static/media/rooms-cover-2.jpg,/static/media/rooms-cover-3.jpg', '海景,日出,可做饭', '推窗见海,公共厨房,露台日出', 'bath,hotwater,wifi,balcony,parking',
  'private', '1', '1', 300, 5, 88, '听一整晚的海', '在露台看洱海日出', '阿海', '/static/media/profile-avatar.jpg', '洱海边长大的主理人', '民宿就在海边，公共厨房可以自己做饭。',
  '15:00', '12:00', 'strict', '双廊听海 住客群', 'online', 2, 'V9', now() from dual where not exists (select 1 from kg_stay where stay_id = 2);
insert into kg_stay (stay_id, store_id, stay_name, city, area, area_desc, address, latitude, longitude, cover, images, tags, features, facilities,
  room_kind, cookable, kitchen, scenic_distance_m, long_stay_nights, long_stay_rate, slogan, slogan_sub, host_name, host_avatar, host_intro, host_story,
  checkin_time, checkout_time, cancel_policy, group_name, status, sort, create_by, create_time)
select 3, 112, '喜洲花间 · 稻田小院', '大理', '喜洲古镇', '喜洲古镇 · 步行3分钟到稻田', '大理喜洲镇城北村 6 号', 25.851000, 100.130000,
  '/static/media/rooms-cover-3.jpg', '/static/media/rooms-cover-3.jpg,/static/media/rooms-cover-4.jpg', '稻田,手作,整院', '稻田边,花艺手作,整院包下', 'bath,hotwater,wifi,washer,parking',
  'whole', '1', '1', 200, 7, 85, '住进稻田里', '白天做花，晚上看星', '小花', '/static/media/profile-avatar.jpg', '花艺手作店主理人', '整座院子可以包下来，适合一家人或朋友一起住。',
  '15:00', '11:00', 'flexible', '喜洲花间 住客群', 'online', 3, 'V9', now() from dual where not exists (select 1 from kg_stay where stay_id = 3);

insert into kg_stay_room_type (room_type_id, stay_id, type_name, room_kind, summary, bed_desc, area_sqm, capacity, unit_label, stock, base_price_fen, cover, features, status, sort, create_by, create_time)
select 1, 1, '院景大床房', 'private', '朝院子，早上有咖啡香', '1.8米大床', 22, 2, '间', 2, 19800, '/static/media/rooms-cover-1.jpg', '独立卫浴,院景', '0', 1, 'V9', now() from dual where not exists (select 1 from kg_stay_room_type where room_type_id = 1);
insert into kg_stay_room_type (room_type_id, stay_id, type_name, room_kind, summary, bed_desc, area_sqm, capacity, unit_label, stock, base_price_fen, cover, features, status, sort, create_by, create_time)
select 2, 1, '阁楼双床房', 'private', '斜顶阁楼，可看苍山', '1.2米双床', 18, 2, '间', 1, 16800, '/static/media/rooms-cover-2.jpg', '独立卫浴,山景', '0', 2, 'V9', now() from dual where not exists (select 1 from kg_stay_room_type where room_type_id = 2);
insert into kg_stay_room_type (room_type_id, stay_id, type_name, room_kind, summary, bed_desc, area_sqm, capacity, unit_label, stock, base_price_fen, cover, features, status, sort, create_by, create_time)
select 3, 2, '海景露台房', 'private', '独立露台正对洱海', '1.8米大床', 30, 2, '间', 2, 32800, '/static/media/rooms-cover-3.jpg', '海景,露台', '0', 1, 'V9', now() from dual where not exists (select 1 from kg_stay_room_type where room_type_id = 3);
insert into kg_stay_room_type (room_type_id, stay_id, type_name, room_kind, summary, bed_desc, area_sqm, capacity, unit_label, stock, base_price_fen, cover, features, status, sort, create_by, create_time)
select 4, 2, '青旅四人间床位', 'shared', '男女分住，公共厨房', '上下铺', 20, 1, '床位', 8, 6800, '/static/media/rooms-cover-4.jpg', '公共卫浴,储物柜', '0', 2, 'V9', now() from dual where not exists (select 1 from kg_stay_room_type where room_type_id = 4);
insert into kg_stay_room_type (room_type_id, stay_id, type_name, room_kind, summary, bed_desc, area_sqm, capacity, unit_label, stock, base_price_fen, cover, features, status, sort, create_by, create_time)
select 5, 3, '整院（三室）', 'whole', '整座院子包下，可住 6 人', '2张大床+2张单床', 160, 6, '间', 1, 88800, '/static/media/rooms-cover-4.jpg', '厨房,院子,停车', '0', 1, 'V9', now() from dual where not exists (select 1 from kg_stay_room_type where room_type_id = 5);

insert into kg_stay_promo (promo_id, stay_id, room_type_id, title, price_fen, quota, sold, stay_start, stay_end, sale_end, refundable, status, create_by, create_time)
select 1, 2, 3, '双廊海景房 · 早鸟价', 25800, 5, 0, date_add(curdate(), interval 10 day), date_add(curdate(), interval 40 day), date_add(now(), interval 7 day), '0', '0', 'V9', now()
from dual where not exists (select 1 from kg_stay_promo where promo_id = 1);

insert into kg_stay_vlog (vlog_id, stay_id, store_id, title, cover, description, platform, link, author_name, author_role, duration_sec, orientation,
  authorized, status, published_time, create_by, create_time)
select 1, 1, 110, '咖啡馆楼上的一天', '/static/media/stays-vlog-1.jpg', '早上在楼下喝一杯手冲，下午去古城转一圈。', 'douyin', 'https://www.douyin.com/', '阿拾', 'host', 88, '竖版',
  '0', 'online', date_sub(now(), interval 3 day), 'V9', now() from dual where not exists (select 1 from kg_stay_vlog where vlog_id = 1);
insert into kg_stay_vlog (vlog_id, stay_id, store_id, title, cover, description, platform, link, author_name, author_role, duration_sec, orientation,
  authorized, status, published_time, create_by, create_time)
select 2, 2, 111, '双廊露台看日出', '/static/media/stays-vlog-2.jpg', '推开露台门就是洱海。', 'xhs', 'https://www.xiaohongshu.com/', '阿海', 'host', 58, '竖版',
  '0', 'online', date_sub(now(), interval 5 day), 'V9', now() from dual where not exists (select 1 from kg_stay_vlog where vlog_id = 2);

insert into kg_shop_service (service_id, store_id, category, service_name, cover, price_fen, unit_label, spec_label, duration_min, content, location, badges,
  advance_hours, time_slots, slot_capacity, reschedule_limit, refundable, status, sort, create_by, create_time)
select 1, 110, 'course', '手冲咖啡体验课', '/static/media/interview-cafe.jpg', 16800, '人', '成人票', 90, '从认识豆子到手冲出一杯咖啡，带走一包自己挑的豆子。', '古城拾光咖啡馆一楼吧台', '可选日期,时长90分钟,小班教学,手冲器具',
  4, '10:00-11:30,14:00-15:30', 6, 1, '1', '0', 1, 'V9', now() from dual where not exists (select 1 from kg_shop_service where service_id = 1);
insert into kg_shop_service (service_id, store_id, category, service_name, cover, price_fen, unit_label, spec_label, duration_min, content, location, badges,
  advance_hours, time_slots, slot_capacity, reschedule_limit, refundable, status, sort, create_by, create_time)
select 2, 110, 'life', '衣物洗烘服务', '/static/media/rooms-cover-2.jpg', 3000, '袋', '标准袋', null, '一袋衣物（约 5 件）洗涤烘干，当天送回房间。', '前台收取', '当天送回,烘干',
  2, '09:00-12:00,14:00-18:00', 10, 2, '1', '0', 2, 'V9', now() from dual where not exists (select 1 from kg_shop_service where service_id = 2);
insert into kg_shop_service (service_id, store_id, category, service_name, cover, price_fen, unit_label, spec_label, duration_min, content, location, badges,
  advance_hours, time_slots, slot_capacity, reschedule_limit, refundable, status, sort, create_by, create_time)
select 3, 112, 'course', '花艺手作体验', '/static/media/activity-pottery.jpg', 12800, '人', '成人票', 120, '用当季花材做一束自己的花。', '喜洲花间手作工作室', '材料全包,小班教学',
  6, '10:00-12:00,15:00-17:00', 8, 1, '1', '0', 1, 'V9', now() from dual where not exists (select 1 from kg_shop_service where service_id = 3);

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
values (9, 'ext_stays', now(), 'deb8d8fceeb3f9d8606d5a8d0e8760d015634edc364c664b6220685d64be7366')
on duplicate key update name = 'ext_stays', applied_time = now(), checksum = 'deb8d8fceeb3f9d8606d5a8d0e8760d015634edc364c664b6220685d64be7366';
