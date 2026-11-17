-- =============================================================================
-- V10__ext_community（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('9');

-- V10 社区：动态 / 评论 / 关注与拉黑 / 活动与报名 / 行程找搭子 / 私信群聊 / 敏感词 / 规则同意
-- 约定同 V7–V9：金额一律分；状态机更新走 CAS（where status = 旧值）；手机号永不向其他用户展示。

create table if not exists kg_post (
  post_id       bigint        not null auto_increment comment '动态ID',
  app_user_id   bigint        not null                comment '作者',
  kind          varchar(16)   not null default 'daily' comment 'daily 旅居日常 / buddy 找搭子 / diary 义工日记',
  city          varchar(32)   not null default ''     comment '城市',
  title         varchar(64)   not null default ''     comment '标题',
  content       varchar(1000) not null default ''     comment '正文',
  images        varchar(2000) null                    comment '图片（逗号分隔，最多 9 张）',
  tags          varchar(128)  null                    comment '话题（逗号分隔）',
  category      varchar(16)   null                    comment '找搭子分类：hike 徒步登山 / photo 摄影 / food 咖啡美食 / ride 骑行 / other 其他',
  event_date    date          null                    comment '找搭子日期',
  event_days    varchar(8)    null                    comment '同行天数：半天 / 1天 / 2-3天 / 一周以上',
  ref_type      varchar(16)   null                    comment '关联：application 岗位报名 / stay_order 住宿订单',
  ref_id        bigint        null                    comment '关联对象ID',
  activity_id   bigint        null                    comment '找搭子同步生成的用户自发活动',
  status        varchar(16)   not null default 'pending' comment 'pending 待审核 / online 已发布 / rejected 已驳回 / hidden 已隐藏',
  audit_reason  varchar(255)  null                    comment '驳回 / 隐藏原因',
  review_mode   varchar(16)   null                    comment '审核方式：manual 人工 / mock 本地模拟放行',
  likes         int           not null default 0      comment '点赞数',
  comments      int           not null default 0      comment '评论数',
  favorites     int           not null default 0      comment '收藏数',
  views         int           not null default 0      comment '浏览数',
  pinned        char(1)       not null default '0'    comment '置顶',
  reported      int           not null default 0      comment '被举报次数',
  create_by     varchar(64)   default ''              comment '创建者',
  create_time   datetime                              comment '创建时间',
  update_by     varchar(64)   default ''              comment '更新者',
  update_time   datetime                              comment '更新时间',
  del_flag      char(1)       default '0'             comment '删除标志',
  primary key (post_id),
  key idx_post_feed (status, city, post_id),
  key idx_post_user (app_user_id)
) engine=innodb comment='社区动态';
call kg_assert_same_collation('kg_post');

create table if not exists kg_post_like (
  like_id       bigint   not null auto_increment comment 'ID',
  post_id       bigint   not null                comment '动态',
  app_user_id   bigint   not null                comment '点赞人',
  create_time   datetime                         comment '时间',
  primary key (like_id),
  unique key uk_post_like (post_id, app_user_id)
) engine=innodb comment='动态点赞';
call kg_assert_same_collation('kg_post_like');

create table if not exists kg_post_comment (
  comment_id    bigint        not null auto_increment comment '评论ID',
  post_id       bigint        not null                comment '动态',
  app_user_id   bigint        not null                comment '评论人',
  parent_id     bigint        not null default 0      comment '上级评论（0 = 一级评论）',
  reply_user_id bigint        null                    comment '回复的人',
  content       varchar(500)  not null                comment '内容',
  likes         int           not null default 0      comment '点赞数',
  status        varchar(16)   not null default 'visible' comment 'visible 可见 / pending 待审核 / hidden 已隐藏',
  reported      int           not null default 0      comment '被举报次数',
  handle_note   varchar(255)  null                    comment '处理说明',
  create_time   datetime                              comment '创建时间',
  update_time   datetime                              comment '更新时间',
  del_flag      char(1)       default '0'             comment '删除标志',
  primary key (comment_id),
  key idx_pc_post (post_id, status, comment_id),
  key idx_pc_parent (parent_id)
) engine=innodb comment='动态评论';
call kg_assert_same_collation('kg_post_comment');

create table if not exists kg_post_comment_like (
  id            bigint   not null auto_increment comment 'ID',
  comment_id    bigint   not null                comment '评论',
  app_user_id   bigint   not null                comment '点赞人',
  create_time   datetime                         comment '时间',
  primary key (id),
  unique key uk_pcl (comment_id, app_user_id)
) engine=innodb comment='评论点赞';
call kg_assert_same_collation('kg_post_comment_like');

create table if not exists kg_user_follow (
  id            bigint   not null auto_increment comment 'ID',
  follower_id   bigint   not null                comment '关注人',
  followee_id   bigint   not null                comment '被关注人',
  create_time   datetime                         comment '时间',
  primary key (id),
  unique key uk_follow (follower_id, followee_id),
  key idx_followee (followee_id)
) engine=innodb comment='用户关注';
call kg_assert_same_collation('kg_user_follow');

create table if not exists kg_user_block (
  id            bigint   not null auto_increment comment 'ID',
  user_id       bigint   not null                comment '拉黑人',
  blocked_id    bigint   not null                comment '被拉黑的人',
  create_time   datetime                         comment '时间',
  primary key (id),
  unique key uk_block (user_id, blocked_id),
  key idx_blocked (blocked_id)
) engine=innodb comment='用户拉黑（双方互不可见私信、评论互不打扰）';
call kg_assert_same_collation('kg_user_block');

create table if not exists kg_activity (
  activity_id    bigint        not null auto_increment comment '活动ID',
  source         varchar(16)   not null                comment 'yard 小院活动 / merchant 商家活动 / user 用户自发',
  store_id       bigint        null                    comment '主办门店（小院 / 商家活动）',
  app_user_id    bigint        null                    comment '发起人账号（用户自发；员工创建时为员工账号）',
  organizer_name varchar(64)   not null default ''     comment '主办方名称',
  category       varchar(16)   null                    comment '手作课程 / 生活体验 / 小型聚会 / 户外活动 / 讲座沙龙 / 其他',
  title          varchar(64)   not null                comment '标题',
  slogan         varchar(128)  null                    comment '一句话介绍',
  cover          varchar(255)  not null default ''     comment '封面',
  city           varchar(32)   not null default ''     comment '城市',
  place          varchar(128)  not null default ''     comment '集合地点',
  start_time     datetime      not null                comment '开始时间',
  end_time       datetime      null                    comment '结束时间',
  deadline       datetime      null                    comment '报名截止',
  capacity       int           not null default 10     comment '名额',
  fee_fen        bigint        not null default 0      comment '费用（分/人）',
  fee_mode       varchar(16)   not null default 'free' comment 'free 免费 / online 在线支付（仅小院活动）/ offline 到场支付',
  fee_includes   varchar(128)  null                    comment '费用包含',
  insured        char(1)       not null default '0'    comment '平台已为参与者投保（只能后台设置）',
  insurance_desc varchar(128)  null                    comment '保障说明（insured=1 时展示）',
  intensity      varchar(64)   null                    comment '强度 / 路线说明',
  intro          varchar(1000) null                    comment '活动介绍',
  safety_note    varchar(200)  null                    comment '安全说明',
  status         varchar(16)   not null default 'pending' comment 'draft 草稿 / pending 待审核 / open 报名中 / paused 暂停报名 / closed 报名结束 / ended 已结束 / cancelled 已取消 / rejected 已驳回',
  audit_reason   varchar(255)  null                    comment '驳回 / 取消原因',
  review_mode    varchar(16)   null                    comment '审核方式',
  signed         int           not null default 0      comment '已确认人数（含待支付占位）',
  waitlist       int           not null default 0      comment '候补人数',
  views          int           not null default 0      comment '浏览量',
  post_id        bigint        null                    comment '来源动态（找搭子同步）',
  conv_id        bigint        null                    comment '活动群',
  pinned         char(1)       not null default '0'    comment '置顶',
  create_by      varchar(64)   default ''              comment '创建者',
  create_time    datetime                              comment '创建时间',
  update_by      varchar(64)   default ''              comment '更新者',
  update_time    datetime                              comment '更新时间',
  del_flag       char(1)       default '0'             comment '删除标志',
  primary key (activity_id),
  key idx_act_list (status, city, start_time),
  key idx_act_store (store_id),
  key idx_act_user (app_user_id),
  constraint chk_act_signed check (signed >= 0 and waitlist >= 0)
) engine=innodb comment='社区活动';
call kg_assert_same_collation('kg_activity');

create table if not exists kg_activity_signup (
  signup_id      bigint        not null auto_increment comment '报名ID',
  signup_no      varchar(32)   not null                comment '报名单号',
  activity_id    bigint        not null                comment '活动',
  app_user_id    bigint        not null                comment '报名人',
  name           varchar(64)   not null default ''     comment '报名人昵称（快照）',
  phone          varchar(20)   null                    comment '报名人手机号（仅领队在活动前后 24 小时可查看，留痕）',
  status         varchar(16)   not null                comment 'pending_payment 待支付 / confirmed 已确认 / waitlist 候补 / cancelled 已取消 / refund_pending 退款待审批 / refunding 退款中 / refunded 已退款 / closed 超时关闭',
  waitlist_no    int           null                    comment '候补序号',
  fee_fen        bigint        not null default 0      comment '应付（分）',
  pay_no         varchar(32)   null                    comment '支付单号',
  paid_time      datetime      null                    comment '支付时间',
  expire_time    datetime      null                    comment '待支付截止',
  checkin_code   varchar(16)   null                    comment '签到码',
  checkin_time   datetime      null                    comment '签到时间',
  channel        varchar(16)   not null default 'other' comment '来源：home 小院首页 / group 群聊 / share 好友分享 / community 社区 / other 其他',
  remind         varchar(8)    not null default '1d20' comment '集合提醒：1d20 前一天 20:00 / 2h 前 2 小时 / off 关闭',
  cancel_reason  varchar(255)  null                    comment '取消原因',
  cancel_time    datetime      null                    comment '取消时间',
  refund_fen     bigint        not null default 0      comment '已退（分）',
  refund_note    varchar(255)  null                    comment '退款审批说明',
  refund_time    datetime      null                    comment '退款时间',
  create_time    datetime                              comment '报名时间',
  update_time    datetime                              comment '更新时间',
  del_flag       char(1)       default '0'             comment '删除标志',
  primary key (signup_id),
  unique key uk_signup_no (signup_no),
  unique key uk_signup_once (activity_id, app_user_id),
  key idx_signup_user (app_user_id, status)
) engine=innodb comment='活动报名';
call kg_assert_same_collation('kg_activity_signup');

create table if not exists kg_activity_stat (
  activity_id   bigint      not null comment '活动',
  stat_day      date        not null comment '日期',
  channel       varchar(16) not null comment '来源渠道',
  views         int         not null default 0 comment '浏览',
  signups       int         not null default 0 comment '报名',
  primary key (activity_id, stat_day, channel)
) engine=innodb comment='活动浏览 / 报名日统计';
call kg_assert_same_collation('kg_activity_stat');

create table if not exists kg_activity_subscribe (
  id            bigint      not null auto_increment comment 'ID',
  app_user_id   bigint      not null comment '订阅人',
  city          varchar(32) not null comment '城市',
  create_time   datetime             comment '时间',
  primary key (id),
  unique key uk_act_sub (app_user_id, city),
  key idx_act_sub_city (city)
) engine=innodb comment='新活动提醒订阅';
call kg_assert_same_collation('kg_activity_subscribe');

create table if not exists kg_trip_plan (
  plan_id        bigint        not null auto_increment comment '行程ID',
  app_user_id    bigint        not null                comment '发布人',
  city           varchar(32)   not null                comment '城市',
  start_date     date          not null                comment '开始日期',
  end_date       date          not null                comment '结束日期',
  activities     varchar(128)  null                    comment '主要活动（逗号分隔）',
  intro          varchar(255)  null                    comment '公开简介',
  hide_stay      char(1)       not null default '1'    comment '隐藏精确住宿信息',
  hide_transport char(1)       not null default '1'    comment '隐藏交通出行信息',
  status         varchar(16)   not null default 'pending' comment 'pending 待审核 / online 公开中 / rejected 已驳回 / closed 已关闭',
  audit_reason   varchar(255)  null                    comment '驳回原因',
  review_mode    varchar(16)   null                    comment '审核方式',
  create_time    datetime                              comment '创建时间',
  update_time    datetime                              comment '更新时间',
  del_flag       char(1)       default '0'             comment '删除标志',
  primary key (plan_id),
  key idx_trip_match (status, city, start_date),
  key idx_trip_user (app_user_id)
) engine=innodb comment='公开行程（找搭子）';
call kg_assert_same_collation('kg_trip_plan');

create table if not exists kg_chat_conv (
  conv_id       bigint        not null auto_increment comment '会话ID',
  conv_type     varchar(16)   not null                comment 'private 私信 / store 联系门店 / group 活动群',
  pair_key      varchar(64)   null                    comment '私信：小ID-大ID；联系门店：门店-用户',
  activity_id   bigint        null                    comment '活动群对应的活动',
  store_id      bigint        null                    comment '门店会话的门店',
  source        varchar(16)   null                    comment '发起来源：match 同行匹配 / post 帖子 / buddy 找搭子 / progress 报名进度 / activity 活动',
  source_id     bigint        null                    comment '来源对象ID',
  title         varchar(64)   not null default ''     comment '会话标题',
  announcement  varchar(500)  null                    comment '群公告',
  last_msg      varchar(255)  null                    comment '最后一条消息摘要',
  last_time     datetime      null                    comment '最后消息时间',
  status        varchar(16)   not null default 'active' comment 'active 正常 / closed 已关闭',
  reported      int           not null default 0      comment '被举报次数',
  create_time   datetime                              comment '创建时间',
  update_time   datetime                              comment '更新时间',
  primary key (conv_id),
  unique key uk_conv_pair (pair_key),
  key idx_conv_activity (activity_id)
) engine=innodb comment='私信 / 群聊会话';
call kg_assert_same_collation('kg_chat_conv');

create table if not exists kg_chat_member (
  id            bigint      not null auto_increment comment 'ID',
  conv_id       bigint      not null                comment '会话',
  app_user_id   bigint      not null                comment '成员',
  role          varchar(16) not null default 'member' comment 'owner 群主 / admin 管理员 / member 成员 / store 门店员工',
  muted         char(1)     not null default '0'    comment '消息免打扰',
  pinned        char(1)     not null default '0'    comment '置顶聊天',
  last_read_id  bigint      not null default 0      comment '已读到的消息ID',
  join_time     datetime                            comment '加入时间',
  left_time     datetime    null                    comment '退出时间（为空 = 在会话中）',
  primary key (id),
  unique key uk_chat_member (conv_id, app_user_id),
  key idx_chat_member_user (app_user_id)
) engine=innodb comment='会话成员';
call kg_assert_same_collation('kg_chat_member');

create table if not exists kg_chat_msg (
  msg_id        bigint        not null auto_increment comment '消息ID',
  conv_id       bigint        not null                comment '会话',
  sender_id     bigint        not null default 0      comment '发送人（0 = 系统）',
  sender_role   varchar(16)   not null default 'user' comment 'user 用户 / store 门店 / system 系统',
  content       varchar(1000) not null                comment '内容',
  status        varchar(16)   not null default 'visible' comment 'visible 可见 / blocked 已拦截 / recalled 已撤回',
  create_time   datetime                              comment '发送时间',
  primary key (msg_id),
  key idx_chat_msg (conv_id, msg_id)
) engine=innodb comment='会话消息';
call kg_assert_same_collation('kg_chat_msg');

create table if not exists kg_sensitive_word (
  word_id       bigint      not null auto_increment comment 'ID',
  word          varchar(32) not null                comment '词',
  level         varchar(8)  not null default 'review' comment 'block 直接拦截 / review 转人工审核',
  status        char(1)     not null default '0'    comment '0 启用 1 停用',
  create_by     varchar(64) default ''              comment '创建者',
  create_time   datetime                            comment '创建时间',
  update_by     varchar(64) default ''              comment '更新者',
  update_time   datetime                            comment '更新时间',
  primary key (word_id),
  unique key uk_word (word)
) engine=innodb comment='社区敏感词';
call kg_assert_same_collation('kg_sensitive_word');

create table if not exists kg_consent (
  id            bigint      not null auto_increment comment 'ID',
  app_user_id   bigint      not null                comment '用户',
  doc           varchar(32) not null                comment 'community 社区公约 / activity 活动参与须知',
  version       varchar(16) not null                comment '文本版本',
  agree_time    datetime                            comment '同意时间',
  primary key (id),
  unique key uk_consent (app_user_id, doc, version)
) engine=innodb comment='规则同意记录';
call kg_assert_same_collation('kg_consent');

-- ---------------------------------------------------------------- 敏感词种子（本地必跑的一层；真实内容安全接口未接入前，生产默认全部人工审核）
insert ignore into kg_sensitive_word (word, level, status, create_by, create_time) values
('赌博', 'block', '0', 'V10', now()), ('博彩', 'block', '0', 'V10', now()), ('裸聊', 'block', '0', 'V10', now()),
('代开发票', 'block', '0', 'V10', now()), ('刷单', 'block', '0', 'V10', now()), ('套现', 'block', '0', 'V10', now()),
('加微信', 'review', '0', 'V10', now()), ('微信号', 'review', '0', 'V10', now()), ('vx', 'review', '0', 'V10', now()),
('私聊转账', 'review', '0', 'V10', now()), ('先付定金', 'review', '0', 'V10', now());

-- ---------------------------------------------------------------- 后台菜单 2300–2399
delete from sys_role_menu where menu_id between 2300 and 2399;
delete from sys_menu where menu_id between 2300 and 2399;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2300, '社区', 0, 10, 'kgcommunity', null, '', '', 1, 0, 'M', '0', '0', '', 'peoples', 'admin', sysdate(), '动态、评论、活动、行程、会话、敏感词'),
(2310, '社区动态', 2300, 1, 'post', 'kangaroo/ext/post/index', '', 'KgExtPost', 1, 0, 'C', '0', '0', 'kangaroo:post:list', 'message', 'admin', sysdate(), ''),
(2311, '动态查询', 2310, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:post:query', '#', 'admin', sysdate(), ''),
(2312, '动态修改', 2310, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:post:edit', '#', 'admin', sysdate(), ''),
(2313, '动态审核', 2310, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:post:audit', '#', 'admin', sysdate(), ''),
(2314, '动态导出', 2310, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:post:export', '#', 'admin', sysdate(), ''),
(2320, '动态评论', 2300, 2, 'postcomment', 'kangaroo/ext/postcomment/index', '', 'KgExtPostComment', 1, 0, 'C', '0', '0', 'kangaroo:postcomment:list', 'message', 'admin', sysdate(), ''),
(2321, '评论查询', 2320, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:postcomment:query', '#', 'admin', sysdate(), ''),
(2322, '评论处理', 2320, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:postcomment:audit', '#', 'admin', sysdate(), ''),
(2330, '社区活动', 2300, 3, 'activity', 'kangaroo/ext/activity/index', '', 'KgExtActivity', 1, 0, 'C', '0', '0', 'kangaroo:activity:list', 'date', 'admin', sysdate(), ''),
(2331, '活动查询', 2330, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:activity:query', '#', 'admin', sysdate(), ''),
(2332, '活动修改', 2330, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:activity:edit', '#', 'admin', sysdate(), ''),
(2333, '活动审核', 2330, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:activity:audit', '#', 'admin', sysdate(), ''),
(2334, '活动导出', 2330, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:activity:export', '#', 'admin', sysdate(), ''),
(2340, '活动报名', 2300, 4, 'actsignup', 'kangaroo/ext/actsignup/index', '', 'KgExtActSignup', 1, 0, 'C', '0', '0', 'kangaroo:actsignup:list', 'list', 'admin', sysdate(), ''),
(2341, '报名查询', 2340, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:actsignup:query', '#', 'admin', sysdate(), ''),
(2342, '退款审批', 2340, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:actsignup:audit', '#', 'admin', sysdate(), ''),
(2343, '报名导出', 2340, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:actsignup:export', '#', 'admin', sysdate(), ''),
(2350, '公开行程', 2300, 5, 'tripplan', 'kangaroo/ext/tripplan/index', '', 'KgExtTripPlan', 1, 0, 'C', '0', '0', 'kangaroo:tripplan:list', 'guide', 'admin', sysdate(), ''),
(2351, '行程查询', 2350, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:tripplan:query', '#', 'admin', sysdate(), ''),
(2352, '行程审核', 2350, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:tripplan:audit', '#', 'admin', sysdate(), ''),
(2360, '私信群聊', 2300, 6, 'chatconv', 'kangaroo/ext/chatconv/index', '', 'KgExtChatConv', 1, 0, 'C', '0', '0', 'kangaroo:chatconv:list', 'wechat', 'admin', sysdate(), '只看会话元数据与被举报会话'),
(2361, '会话查询', 2360, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:chatconv:query', '#', 'admin', sysdate(), ''),
(2362, '会话处置', 2360, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:chatconv:audit', '#', 'admin', sysdate(), ''),
(2370, '敏感词库', 2300, 7, 'sensword', 'kangaroo/ext/sensword/index', '', 'KgExtSensWord', 1, 0, 'C', '0', '0', 'kangaroo:sensword:list', 'lock', 'admin', sysdate(), ''),
(2371, '敏感词查询', 2370, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:sensword:query', '#', 'admin', sysdate(), ''),
(2372, '敏感词新增', 2370, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:sensword:add', '#', 'admin', sysdate(), ''),
(2373, '敏感词修改', 2370, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:sensword:edit', '#', 'admin', sysdate(), ''),
(2374, '敏感词删除', 2370, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:sensword:remove', '#', 'admin', sysdate(), '');

-- ---------------------------------------------------------------- 演示种子（只在对应主键不存在时插入；create_by = V10 便于识别）
insert into kg_activity (activity_id, source, store_id, organizer_name, category, title, slogan, cover, city, place, start_time, end_time, deadline,
  capacity, fee_fen, fee_mode, fee_includes, intensity, intro, safety_note, status, review_mode, create_by, create_time)
select 1, 'yard', 110, '古城拾光咖啡馆', '户外活动', '苍山日出徒步', '迎接第一缕阳光，徒步登顶，遇见不一样的大理', '/static/media/activity-sunrise.jpg', '大理',
  '喜洲古镇停车场', timestamp(date_add(curdate(), interval 3 day), '05:30:00'), timestamp(date_add(curdate(), interval 3 day), '10:00:00'),
  timestamp(date_add(curdate(), interval 2 day), '18:00:00'), 18, 6000, 'online', '领队服务、能量补给、活动组织', '徒步约 8km · 累计爬升 600m · 中等强度',
  '清晨出发，一起徒步苍山，见证日出云海。路线轻徒步，适合大多数朋友，记得带上手机和保暖衣物。', '请穿防滑鞋，带保暖外套与饮用水；身体不适请提前告知领队。',
  'open', 'seed', 'V10', now() from dual where not exists (select 1 from kg_activity where activity_id = 1);
insert into kg_activity (activity_id, source, store_id, organizer_name, category, title, slogan, cover, city, place, start_time, end_time, deadline,
  capacity, fee_fen, fee_mode, intro, status, review_mode, create_by, create_time)
select 2, 'yard', 112, '喜洲花间手作', '手作课程', '花间咖啡拉花体验', '跟咖啡师学一杯属于自己的拉花', '/static/media/community-hot-coffee.jpg', '大理',
  '喜洲花间手作', timestamp(date_add(curdate(), interval 2 day), '14:00:00'), timestamp(date_add(curdate(), interval 2 day), '16:00:00'),
  timestamp(date_add(curdate(), interval 2 day), '12:00:00'), 12, 8800, 'offline', '咖啡师一对多教学，含两杯咖啡的豆子与牛奶。', 'open', 'seed', 'V10', now()
  from dual where not exists (select 1 from kg_activity where activity_id = 2);
insert into kg_post (post_id, app_user_id, kind, city, title, content, images, tags, status, review_mode, create_by, create_time)
select 1, 1036, 'daily', '大理', '在大理的第7天，和新朋友一起看日出', '清晨5点出发，沿着小路慢慢往上走，云海翻涌，阳光洒在苍山上，真的太治愈了～',
  '/static/media/community-post-1-1.jpg,/static/media/community-post-1-2.jpg,/static/media/community-post-1-3.jpg', '苍山徒步,大理日常', 'online', 'seed', 'V10', now()
  from dual where not exists (select 1 from kg_post where post_id = 1);
insert into kg_post (post_id, app_user_id, kind, city, title, content, images, tags, category, event_date, event_days, status, review_mode, create_by, create_time)
select 2, 1036, 'buddy', '大理', '周末一起去苍山日出徒步吗？', '想找小伙伴周日清晨出发登顶苍山，看日出云海，新手友好～',
  '/static/media/community-hot-sunrise.jpg', '找搭子', 'hike', date_add(curdate(), interval 3 day), '1天', 'online', 'seed', 'V10', now()
  from dual where not exists (select 1 from kg_post where post_id = 2);

-- ---------------------------------------------------------------- 手机号核验时间（只有经微信手机号快速验证的才算"手机已验证"）
call kg_add_col_if_absent('kg_app_user', 'phone_verified_time', 'phone_verified_time datetime null comment ''手机号经微信核验的时间''');

create table if not exists kg_pii_access (
  id            bigint      not null auto_increment comment 'ID',
  viewer_id     bigint      not null                comment '查看人（小程序账号）',
  target_id     bigint      not null                comment '被查看人',
  biz_type      varchar(16) not null                comment '业务：activity 活动领队查看参与者电话',
  biz_id        bigint      not null                comment '业务ID',
  field         varchar(16) not null default 'phone' comment '字段',
  create_time   datetime                            comment '时间',
  primary key (id),
  key idx_pii_target (target_id),
  key idx_pii_biz (biz_type, biz_id)
) engine=innodb comment='个人信息查看留痕';
call kg_assert_same_collation('kg_pii_access');

call kg_add_col_if_absent('kg_activity', 'checkin_token', 'checkin_token varchar(16) null comment ''签到二维码令牌''');
call kg_add_col_if_absent('kg_activity_signup', 'reminded_time', 'reminded_time datetime null comment ''集合提醒已发送时间''');

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
values (10, 'ext_community', now(), 'ef1c27dd78a3028c4774e9bb375125442b4bf52c3ad1c01e7af582bb554a7762')
on duplicate key update name = 'ext_community', applied_time = now(), checksum = 'ef1c27dd78a3028c4774e9bb375125442b4bf52c3ad1c01e7af582bb554a7762';
