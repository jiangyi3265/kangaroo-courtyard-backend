-- =============================================================================
-- V11__ext_user（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('10');

-- V11 用户端扩展：城市字典、客服（智能问答 + 人工工单）、新机会订阅、简历与经历、技能证书、责任书签署与到店信息、
-- 同行队伍、提醒设置、攻略体验官申请、评价分项。约定同 V7–V10。

create table if not exists kg_city (
  city_id       bigint       not null auto_increment comment '城市ID',
  name          varchar(32)  not null                comment '简称（与岗位 / 房源 / 活动的城市字段一致）',
  full_name     varchar(32)  not null default ''     comment '全称',
  province      varchar(16)  not null default ''     comment '省份',
  region        varchar(32)  not null default ''     comment '城市群',
  pinyin        varchar(64)  not null default ''     comment '拼音',
  initial       char(1)      not null default ''     comment '首字母',
  lat           decimal(9,6) null                    comment '中心纬度',
  lng           decimal(9,6) null                    comment '中心经度',
  hot           char(1)      not null default '0'    comment '热门',
  sort          int          not null default 0      comment '排序',
  status        char(1)      not null default '0'    comment '0 启用 1 停用',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  primary key (city_id),
  unique key uk_city_name (name)
) engine=innodb comment='城市字典';
call kg_assert_same_collation('kg_city');

create table if not exists kg_support_ticket (
  ticket_id     bigint        not null auto_increment comment '工单ID',
  app_user_id   bigint        not null                comment '用户',
  context       varchar(16)   not null default 'home' comment '来源：home / city / profile / order',
  question      varchar(500)  not null                comment '用户问题',
  summary       varchar(500)  null                    comment '问题摘要',
  related       varchar(500)  null                    comment '用户授权客服查看的关联信息',
  status        varchar(16)   not null default 'open' comment 'open 待处理 / replied 已回复 / closed 已关闭',
  reply         varchar(1000) null                    comment '客服回复',
  replied_by    varchar(64)   null                    comment '回复人',
  replied_time  datetime      null                    comment '回复时间',
  satisfaction  tinyint       null                    comment '满意度 1–5',
  create_time   datetime                              comment '创建时间',
  update_time   datetime                              comment '更新时间',
  primary key (ticket_id),
  key idx_ticket_user (app_user_id),
  key idx_ticket_status (status)
) engine=innodb comment='人工客服工单';
call kg_assert_same_collation('kg_support_ticket');

create table if not exists kg_support_log (
  log_id        bigint        not null auto_increment comment 'ID',
  app_user_id   bigint        null                    comment '用户（游客为空）',
  context       varchar(16)   null                    comment '来源',
  question      varchar(255)  not null                comment '问题',
  intent        varchar(32)   not null                comment '识别出的意图',
  answer        varchar(1000) null                    comment '回答摘要',
  helpful       char(1)       null                    comment '1 有帮助 0 没帮助',
  create_time   datetime                              comment '时间',
  primary key (log_id),
  key idx_slog_intent (intent)
) engine=innodb comment='智能问答记录';
call kg_assert_same_collation('kg_support_log');

create table if not exists kg_job_subscribe (
  sub_id             bigint       not null auto_increment comment '订阅ID',
  app_user_id        bigint       not null                comment '用户',
  cities             varchar(255) null                    comment '城市（逗号分隔）',
  job_types          varchar(255) null                    comment '岗位类型关键词（逗号分隔）',
  keyword            varchar(64)  null                    comment '关键词',
  store_id           bigint       null                    comment '只关注这家店（"这家店缺人了提醒我"）',
  from_date          date         null                    comment '可到岗日期',
  min_match          int          not null default 60     comment '最低匹配度',
  freq               varchar(8)   not null default 'instant' comment 'instant 即时 / daily 每日汇总',
  status             char(1)      not null default '0'    comment '0 生效 1 暂停',
  last_notified_time datetime     null                    comment '上次推送时间',
  create_time        datetime                             comment '创建时间',
  update_time        datetime                             comment '更新时间',
  primary key (sub_id),
  key idx_jsub_user (app_user_id)
) engine=innodb comment='新机会订阅';
call kg_assert_same_collation('kg_job_subscribe');

create table if not exists kg_resume (
  resume_id     bigint        not null auto_increment comment 'ID',
  app_user_id   bigint        not null                comment '用户',
  name          varchar(32)   not null default ''     comment '昵称 / 称呼',
  gender        varchar(8)    null                    comment '性别',
  age           int           null                    comment '年龄',
  phone         varchar(20)   null                    comment '联系电话（仅报名时给到门店，脱敏展示）',
  city          varchar(32)   null                    comment '所在城市',
  intro         varchar(200)  null                    comment '自我介绍',
  skill_text    varchar(1000) null                    comment '技能描述（原文）',
  extra_text    varchar(1000) null                    comment '补充说明',
  links         varchar(2000) null                    comment '作品链接（换行分隔）',
  create_time   datetime                              comment '创建时间',
  update_time   datetime                              comment '更新时间',
  primary key (resume_id),
  unique key uk_resume_user (app_user_id)
) engine=innodb comment='简历';
call kg_assert_same_collation('kg_resume');

create table if not exists kg_resume_exp (
  exp_id        bigint        not null auto_increment comment 'ID',
  app_user_id   bigint        not null                comment '用户',
  kind          varchar(16)   not null                comment 'volunteer 义工 / parttime 兼职 / fulltime 全职 / training 培训',
  title         varchar(64)   not null                comment '岗位 / 课程',
  place         varchar(64)   null                    comment '门店 / 机构 · 城市',
  start_date    date          null                    comment '开始',
  end_date      date          null                    comment '结束',
  content       varchar(500)  null                    comment '内容',
  level         varchar(16)   null                    comment '考核等级（培训）',
  passed        char(1)       null                    comment '是否通过（培训）',
  app_id        bigint        null                    comment '来自哪条报名（自动生成的经历）',
  source        varchar(16)   not null default 'manual' comment 'manual 自己填写 / platform 平台记录',
  create_time   datetime                              comment '创建时间',
  update_time   datetime                              comment '更新时间',
  del_flag      char(1)       default '0'             comment '删除标志',
  primary key (exp_id),
  key idx_rexp_user (app_user_id, kind),
  unique key uk_rexp_app (app_id)
) engine=innodb comment='简历经历';
call kg_assert_same_collation('kg_resume_exp');

create table if not exists kg_cert (
  cert_id       bigint        not null auto_increment comment 'ID',
  app_user_id   bigint        not null                comment '用户',
  name          varchar(64)   not null                comment '证书名称',
  category      varchar(32)   not null default ''     comment '技能方向（咖啡师 / 手作 …）',
  level         varchar(16)   not null default ''     comment '等级',
  grade         varchar(16)   null                    comment '评定（良好 / 优秀 …）',
  score         int           null                    comment '综合评分',
  cert_no       varchar(32)   not null                comment '证书编号',
  issuer        varchar(64)   not null                comment '颁发机构',
  issued_date   date          not null                comment '颁发日期',
  status        char(1)       not null default '0'    comment '0 有效 1 作废',
  create_by     varchar(64)   default ''              comment '创建者',
  create_time   datetime                              comment '创建时间',
  update_by     varchar(64)   default ''              comment '更新者',
  update_time   datetime                              comment '更新时间',
  primary key (cert_id),
  unique key uk_cert_no (cert_no),
  key idx_cert_user (app_user_id)
) engine=innodb comment='技能认证证书（平台考核后在后台登记）';
call kg_assert_same_collation('kg_cert');

create table if not exists kg_contract_sign (
  sign_id       bigint        not null auto_increment comment 'ID',
  app_id        bigint        not null                comment '报名',
  app_user_id   bigint        not null                comment '签署人账号',
  version       varchar(16)   not null                comment '责任书版本',
  content_hash  varchar(64)   not null                comment '签署时责任书全文的 SHA-256',
  signer_name   varchar(32)   not null                comment '签署姓名',
  id_last4      varchar(4)    null                    comment '证件后四位（选填，仅用于核对）',
  sign_ip       varchar(64)   null                    comment '签署 IP',
  signed_time   datetime                              comment '签署时间',
  primary key (sign_id),
  unique key uk_sign_app (app_id)
) engine=innodb comment='责任书签署存证（电子签提供方未接入：这是平台内的确认留痕，不等同于第三方电子签名）';
call kg_assert_same_collation('kg_contract_sign');

create table if not exists kg_app_arrival (
  app_id        bigint        not null                comment '报名',
  app_user_id   bigint        not null                comment '用户',
  arrive_date   date          null                    comment '到达日期',
  arrive_time   varchar(8)    null                    comment '到达时间',
  transport     varchar(16)   null                    comment '交通方式：火车 / 飞机 / 大巴 / 自驾 / 其他',
  transport_no  varchar(32)   null                    comment '车次 / 航班',
  need_pickup   char(1)       not null default '0'    comment '需要接站（由门店确认）',
  tickets       varchar(1000) null                    comment '票据截图（逗号分隔）',
  note          varchar(200)  null                    comment '备注',
  update_time   datetime                              comment '更新时间',
  primary key (app_id),
  key idx_arrival_user (app_user_id)
) engine=innodb comment='到店信息';
call kg_assert_same_collation('kg_app_arrival');

create table if not exists kg_team_member (
  id            bigint       not null auto_increment comment 'ID',
  job_id        bigint       not null                comment '岗位',
  app_user_id   bigint       not null                comment '用户',
  app_id        bigint       null                    comment '对应报名',
  visible       char(1)      not null default '1'    comment '在同行队伍里展示',
  create_time   datetime                             comment '加入时间',
  primary key (id),
  unique key uk_team (job_id, app_user_id)
) engine=innodb comment='同行队伍（本人主动加入才展示，只展示昵称、城市与停留天数）';
call kg_assert_same_collation('kg_team_member');

create table if not exists kg_notify_pref (
  id            bigint       not null auto_increment comment 'ID',
  app_user_id   bigint       not null                comment '用户',
  pref_key      varchar(32)  not null                comment 'job_new 新机会 / apply_progress 报名进度 / activity 活动提醒 / order 订单与退款 / review 评价邀请 / community 社区互动',
  enabled       char(1)      not null default '1'    comment '1 开启 0 暂停',
  freq          varchar(16)  not null default 'instant' comment 'instant 即时 / daily 每日汇总',
  update_time   datetime                             comment '更新时间',
  primary key (id),
  unique key uk_pref (app_user_id, pref_key)
) engine=innodb comment='提醒设置';
call kg_assert_same_collation('kg_notify_pref');

create table if not exists kg_guide_apply (
  apply_id      bigint       not null auto_increment comment 'ID',
  app_user_id   bigint       not null                comment '申请人',
  cities        varchar(255) not null                comment '申请城市（逗号分隔）',
  plan          varchar(200) not null                comment '创作计划',
  works         varchar(500) null                    comment '代表作品（链接，换行分隔）',
  status        varchar(16)  not null default 'pending' comment 'pending 待审核 / approved 已通过 / rejected 未通过',
  audit_note    varchar(255) null                    comment '审核说明',
  audited_by    varchar(64)  null                    comment '审核人',
  audited_time  datetime     null                    comment '审核时间',
  create_time   datetime                             comment '申请时间',
  primary key (apply_id),
  key idx_guide_user (app_user_id)
) engine=innodb comment='旅行体验官（攻略作者）申请';
call kg_assert_same_collation('kg_guide_apply');

call kg_add_col_if_absent('kg_review', 'dims', 'dims varchar(255) null comment ''分项评分（JSON：住宿 / 强度 / 沟通 / 成长 / 位置）''');
call kg_add_col_if_absent('kg_store', 'latitude', 'latitude decimal(9,6) null comment ''门店纬度''');
call kg_add_col_if_absent('kg_store', 'longitude', 'longitude decimal(9,6) null comment ''门店经度''');

-- ---------------------------------------------------------------- 城市字典种子（中心点坐标取城市政府驻地附近，用于就近匹配，不做精确定位）
insert ignore into kg_city (name, full_name, province, region, pinyin, initial, lat, lng, hot, sort, status, create_by, create_time) values
('大理', '大理市', '云南省', '滇西生活圈', 'dali', 'D', 25.606486, 100.267638, '1', 1, '0', 'V11', now()),
('丽江', '丽江市', '云南省', '滇西生活圈', 'lijiang', 'L', 26.872108, 100.229940, '1', 2, '0', 'V11', now()),
('腾冲', '腾冲市', '云南省', '滇西生活圈', 'tengchong', 'T', 25.020600, 98.497000, '0', 20, '0', 'V11', now()),
('香格里拉', '香格里拉市', '云南省', '滇西生活圈', 'xianggelila', 'X', 27.829700, 99.706500, '0', 21, '0', 'V11', now()),
('保山', '保山市', '云南省', '滇西生活圈', 'baoshan', 'B', 25.112000, 99.161800, '0', 22, '0', 'V11', now()),
('昆明', '昆明市', '云南省', '滇中生活圈', 'kunming', 'K', 25.038900, 102.718300, '1', 5, '0', 'V11', now()),
('西双版纳', '景洪市', '云南省', '滇南生活圈', 'xishuangbanna', 'X', 22.007400, 100.797900, '0', 23, '0', 'V11', now()),
('成都', '成都市', '四川省', '川渝生活圈', 'chengdu', 'C', 30.572800, 104.066800, '1', 3, '0', 'V11', now()),
('重庆', '重庆市', '重庆市', '川渝生活圈', 'chongqing', 'C', 29.563000, 106.551600, '1', 8, '0', 'V11', now()),
('阿坝州', '马尔康市', '四川省', '川西生活圈', 'aba', 'A', 31.899400, 102.221400, '0', 30, '0', 'V11', now()),
('杭州', '杭州市', '浙江省', '江浙沪生活圈', 'hangzhou', 'H', 30.274100, 120.155100, '1', 4, '0', 'V11', now()),
('安吉', '安吉县', '浙江省', '江浙沪生活圈', 'anji', 'A', 30.638000, 119.680000, '0', 24, '0', 'V11', now()),
('苏州', '苏州市', '江苏省', '江浙沪生活圈', 'suzhou', 'S', 31.299000, 120.585300, '1', 10, '0', 'V11', now()),
('上海', '上海市', '上海市', '江浙沪生活圈', 'shanghai', 'S', 31.230400, 121.473700, '0', 25, '0', 'V11', now()),
('南京', '南京市', '江苏省', '江浙沪生活圈', 'nanjing', 'N', 32.060300, 118.796900, '0', 26, '0', 'V11', now()),
('景德镇', '景德镇市', '江西省', '赣北生活圈', 'jingdezhen', 'J', 29.268800, 117.178400, '0', 12, '0', 'V11', now()),
('黄山', '黄山市', '安徽省', '皖南生活圈', 'huangshan', 'H', 29.714700, 118.337500, '0', 27, '0', 'V11', now()),
('厦门', '厦门市', '福建省', '闽南生活圈', 'xiamen', 'X', 24.479800, 118.089400, '1', 9, '0', 'V11', now()),
('泉州', '泉州市', '福建省', '闽南生活圈', 'quanzhou', 'Q', 24.874100, 118.675700, '0', 28, '0', 'V11', now()),
('广州', '广州市', '广东省', '珠三角生活圈', 'guangzhou', 'G', 23.129100, 113.264400, '1', 6, '0', 'V11', now()),
('深圳', '深圳市', '广东省', '珠三角生活圈', 'shenzhen', 'S', 22.543100, 114.057900, '1', 7, '0', 'V11', now()),
('北京', '北京市', '北京市', '京津生活圈', 'beijing', 'B', 39.904200, 116.407400, '0', 29, '0', 'V11', now()),
('西安', '西安市', '陕西省', '关中生活圈', 'xian', 'X', 34.341600, 108.939800, '1', 11, '0', 'V11', now()),
('青岛', '青岛市', '山东省', '胶东生活圈', 'qingdao', 'Q', 36.067100, 120.382600, '0', 31, '0', 'V11', now()),
('长沙', '长沙市', '湖南省', '长株潭生活圈', 'changsha', 'C', 28.228200, 112.938800, '0', 32, '0', 'V11', now()),
('武汉', '武汉市', '湖北省', '江汉生活圈', 'wuhan', 'W', 30.592800, 114.305500, '0', 33, '0', 'V11', now()),
('桂林', '桂林市', '广西', '桂北生活圈', 'guilin', 'G', 25.273600, 110.290000, '0', 34, '0', 'V11', now()),
('阳朔', '阳朔县', '广西', '桂北生活圈', 'yangshuo', 'Y', 24.778400, 110.496600, '0', 35, '0', 'V11', now()),
('北海', '北海市', '广西', '北部湾生活圈', 'beihai', 'B', 21.473300, 109.119300, '0', 36, '0', 'V11', now()),
('三亚', '三亚市', '海南省', '海南生活圈', 'sanya', 'S', 18.252800, 109.511900, '0', 37, '0', 'V11', now()),
('拉萨', '拉萨市', '西藏', '藏地生活圈', 'lasa', 'L', 29.652000, 91.172100, '0', 38, '0', 'V11', now()),
('安阳', '安阳市', '河南省', '中原生活圈', 'anyang', 'A', 36.097600, 114.392400, '0', 39, '0', 'V11', now());

-- 演示门店坐标（大理三家，取所在片区大致位置，仅用于到店路线的地图打开）
update kg_store set latitude = 25.694700, longitude = 100.163700 where store_id = 110 and latitude is null;
update kg_store set latitude = 25.920600, longitude = 100.301400 where store_id = 111 and latitude is null;
update kg_store set latitude = 25.846500, longitude = 100.132900 where store_id = 112 and latitude is null;

-- ---------------------------------------------------------------- 后台菜单 2400–2499
delete from sys_role_menu where menu_id between 2400 and 2499;
delete from sys_menu where menu_id between 2400 and 2499;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2400, '用户服务', 0, 11, 'kgusersvc', null, '', '', 1, 0, 'M', '0', '0', '', 'user', 'admin', sysdate(), '城市、客服工单、订阅、简历、证书、签署、到店、体验官'),
(2410, '城市字典', 2400, 1, 'city', 'kangaroo/ext/city/index', '', 'KgExtCity', 1, 0, 'C', '0', '0', 'kangaroo:city:list', 'international', 'admin', sysdate(), ''),
(2411, '城市查询', 2410, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:city:query', '#', 'admin', sysdate(), ''),
(2412, '城市新增', 2410, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:city:add', '#', 'admin', sysdate(), ''),
(2413, '城市修改', 2410, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:city:edit', '#', 'admin', sysdate(), ''),
(2414, '城市删除', 2410, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:city:remove', '#', 'admin', sysdate(), ''),
(2420, '客服工单', 2400, 2, 'ticket', 'kangaroo/ext/ticket/index', '', 'KgExtTicket', 1, 0, 'C', '0', '0', 'kangaroo:ticket:list', 'message', 'admin', sysdate(), ''),
(2421, '工单查询', 2420, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:ticket:query', '#', 'admin', sysdate(), ''),
(2422, '工单回复', 2420, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:ticket:audit', '#', 'admin', sysdate(), ''),
(2430, '新机会订阅', 2400, 3, 'jobsub', 'kangaroo/ext/jobsub/index', '', 'KgExtJobSub', 1, 0, 'C', '0', '0', 'kangaroo:jobsub:list', 'star', 'admin', sysdate(), ''),
(2431, '订阅查询', 2430, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:jobsub:query', '#', 'admin', sysdate(), ''),
(2440, '技能证书', 2400, 4, 'cert', 'kangaroo/ext/cert/index', '', 'KgExtCert', 1, 0, 'C', '0', '0', 'kangaroo:cert:list', 'education', 'admin', sysdate(), ''),
(2441, '证书查询', 2440, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:cert:query', '#', 'admin', sysdate(), ''),
(2442, '证书登记', 2440, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:cert:add', '#', 'admin', sysdate(), ''),
(2443, '证书修改', 2440, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:cert:edit', '#', 'admin', sysdate(), ''),
(2450, '责任书签署', 2400, 5, 'contractsign', 'kangaroo/ext/contractsign/index', '', 'KgExtContractSign', 1, 0, 'C', '0', '0', 'kangaroo:contractsign:list', 'documentation', 'admin', sysdate(), ''),
(2451, '签署查询', 2450, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:contractsign:query', '#', 'admin', sysdate(), ''),
(2460, '到店信息', 2400, 6, 'arrival', 'kangaroo/ext/arrival/index', '', 'KgExtArrival', 1, 0, 'C', '0', '0', 'kangaroo:arrival:list', 'guide', 'admin', sysdate(), ''),
(2461, '到店查询', 2460, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:arrival:query', '#', 'admin', sysdate(), ''),
(2470, '体验官申请', 2400, 7, 'guideapply', 'kangaroo/ext/guideapply/index', '', 'KgExtGuideApply', 1, 0, 'C', '0', '0', 'kangaroo:guideapply:list', 'people', 'admin', sysdate(), ''),
(2471, '申请查询', 2470, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:guideapply:query', '#', 'admin', sysdate(), ''),
(2472, '申请审核', 2470, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:guideapply:audit', '#', 'admin', sysdate(), '');

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
values (11, 'ext_user', now(), 'a7f6fe945f01bafa13fbd0b469a76bb4517fa83bebdc584471f6edc22fde1585')
on duplicate key update name = 'ext_user', applied_time = now(), checksum = 'a7f6fe945f01bafa13fbd0b469a76bb4517fa83bebdc584471f6edc22fde1585';
