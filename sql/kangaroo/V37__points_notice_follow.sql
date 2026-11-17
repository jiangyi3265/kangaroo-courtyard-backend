-- =============================================================================
-- V37__points_notice_follow（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('36');

-- V37（2026-10-11）：按主人选定的「app建议总结」第一批改动（都不涉及收钱）
--   1) 义工分类型 + 小程序公告（线上兼职须知弹窗）
--   2) 关注门店 + 我的收入 + 线上任务「发素材」
--   3) 积分：义工完成得积分 + 积分换加急投递 + 邀请新人送积分
--   4) 私信交换联系方式
-- 只新增表、列、索引、字典、菜单和后台参数；唯一改到已有数据的是第 3 段末尾「上线前已结束的义工记为已完成、不补发积分」（只在第一次执行时）。
-- 各段开头列出了本段新增的全部对象。

-- =============================================================================
-- 义工分类型 + 小程序公告（线上兼职须知弹窗）（V37_kinds_notice.sql）
-- =============================================================================
-- 义工分类型 + 小程序公告（线上兼职须知弹窗）（2026-10-10）
-- 新增列：kg_job.work_kind  varchar(32) not null default ''（义工类型，取值来自字典 kg_volunteer_kind；只对义工岗位有意义，其他岗位为空串）
-- 新增索引：kg_job.idx_job_kind (job_type, work_kind)
-- 新增表：kg_app_notice（小程序公告：标题 / 正文 / 给谁看 / 是否弹窗 / 固定标识 / 版本号 / 上下线 / 排序 / 展示时间）
--   索引：PRIMARY (notice_id)、uk_notice_key (notice_key) 唯一、idx_notice_list (status, del_flag, sort)
-- 新增表：kg_app_notice_seen（谁看过哪条公告的哪个版本）
--   索引：PRIMARY (user_id, notice_id)
-- 新增字典：sys_dict_type kg_volunteer_kind「义工类型」+ 6 条 sys_dict_data：daily 日常义工 / edit 剪辑义工 / live 直播义工 / photo 摄影义工 / craft 手作教学 / other 其他
-- 新增菜单：用户服务 → 小程序公告（menu_id 2480，按钮 2481–2484；权限 kangaroo:appnotice:list / query / add / edit / remove）
--   授权：已有「常见问题」管理权限（kangaroo:faq:list）的角色一并获得小程序公告全部权限；超级管理员角色 1 也补上
-- 种子数据：kg_app_notice 一条，notice_key = 'online-guide'「线上兼职须知」（面向关注线上任务的人，进小程序弹窗一次）
-- 只新增，不改动已有数据；全部语句可重复执行。

-- ---------------------------------------------------------------- 1) 义工类型
call kg_add_col_if_absent('kg_job', 'work_kind', 'work_kind varchar(32) not null default '''' comment ''义工类型（字典 kg_volunteer_kind，只对义工岗位有意义，其他岗位为空串）'' after job_type');
call kg_add_idx_if_absent('kg_job', 'idx_job_kind', 'key idx_job_kind (job_type, work_kind)');

-- 类型由运营在后台「系统管理 → 字典管理 → 义工类型」里改名、排序、停用、新增；备注写一句说明（员工发布岗位时能看到，
-- 也用来把还没选类型的老岗位按词模糊归类，词之间用顿号隔开）
insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
select '义工类型', 'kg_volunteer_kind', '0', 'admin', sysdate(), '义工换宿岗位的类型：小程序「义工换宿」列表的分类条、筛选页、发布岗位都读这里；停用后不再出现在选项里，已经选了它的老岗位照常显示'
from dual where not exists (select 1 from sys_dict_type where dict_type = 'kg_volunteer_kind');

insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 1, '日常义工', 'daily', 'kg_volunteer_kind', '', 'primary', 'N', '0', 'admin', sysdate(), '前台、接待、打扫、陪伴客人'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_volunteer_kind' and dict_value = 'daily');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 2, '剪辑义工', 'edit', 'kg_volunteer_kind', '', 'success', 'N', '0', 'admin', sysdate(), '剪辑、拍视频、做短视频'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_volunteer_kind' and dict_value = 'edit');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 3, '直播义工', 'live', 'kg_volunteer_kind', '', 'warning', 'N', '0', 'admin', sysdate(), '直播、直播间助理、带货'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_volunteer_kind' and dict_value = 'live');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 4, '摄影义工', 'photo', 'kg_volunteer_kind', '', 'info', 'N', '0', 'admin', sysdate(), '摄影、拍照、修图'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_volunteer_kind' and dict_value = 'photo');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 5, '手作教学', 'craft', 'kg_volunteer_kind', '', 'danger', 'N', '0', 'admin', sysdate(), '手作、手工、带客人做手作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_volunteer_kind' and dict_value = 'craft');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 6, '其他', 'other', 'kg_volunteer_kind', '', 'default', 'N', '0', 'admin', sysdate(), '以上都不是的义工'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_volunteer_kind' and dict_value = 'other');

-- ---------------------------------------------------------------- 2) 小程序公告
create table if not exists kg_app_notice (
  notice_id     bigint       not null auto_increment comment '公告ID',
  title         varchar(64)  not null                comment '标题',
  content       text         not null                comment '正文（纯文字，保留换行）',
  audience      varchar(16)  not null default 'all'  comment '给谁看：all 所有人 / online 关注线上任务的人（订阅过线上任务或报名过线上任务）',
  popup         char(1)      not null default '0'    comment '1 进小程序时弹窗一次（改了标题或正文会再弹一次）/ 0 不弹窗，只在消息页公告里',
  notice_key    varchar(32)  null                    comment '固定标识（如 online-guide），小程序页面按它直接打开这条公告；可不填',
  version       int          not null default 1      comment '版本号：每次改标题或正文 +1，用户按版本记「看过」',
  status        char(1)      not null default '0'    comment '0 上线 / 1 下线',
  sort          int          not null default 0      comment '排序（小的在前）',
  start_time    datetime     null                    comment '开始展示时间（空 = 立即）',
  end_time      datetime     null                    comment '结束展示时间（空 = 一直展示）',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  del_flag      char(1)      not null default '0'    comment '删除标志（0 存在 2 删除）',
  primary key (notice_id),
  unique key uk_notice_key (notice_key),
  key idx_notice_list (status, del_flag, sort)
) engine=innodb comment='小程序公告（运营在后台编辑）';
call kg_assert_same_collation('kg_app_notice');

create table if not exists kg_app_notice_seen (
  user_id       bigint       not null                comment '小程序用户',
  notice_id     bigint       not null                comment '公告',
  version       int          not null default 1      comment '看过的版本（点「我知道了」或打开详情时的版本）',
  seen_time     datetime     not null                comment '最近一次看的时间',
  primary key (user_id, notice_id)
) engine=innodb comment='用户看过的公告（按版本记，公告改了内容会再提醒）';
call kg_assert_same_collation('kg_app_notice_seen');

-- 后台菜单：用户服务（path = kgusersvc）→ 小程序公告，与「常见问题」放在一起
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select 2480, '小程序公告', m.menu_id, 13, 'appnotice', 'kangaroo/ext/appnotice/index', '', 'KgExtAppNotice', 1, 0, 'C', '0', '0', 'kangaroo:appnotice:list', 'message', 'admin', sysdate(), '小程序公告：进小程序弹窗、消息页公告、线上兼职须知'
from sys_menu m where m.path = 'kgusersvc' and m.menu_type = 'M'
  and not exists (select 1 from sys_menu x where x.menu_id = 2480 or x.perms = 'kangaroo:appnotice:list') limit 1;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select 2481, '公告查询', m.menu_id, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:appnotice:query', '#', 'admin', sysdate(), ''
from sys_menu m where m.perms = 'kangaroo:appnotice:list' and m.menu_type = 'C'
  and not exists (select 1 from sys_menu x where x.menu_id = 2481 or x.perms = 'kangaroo:appnotice:query') limit 1;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select 2482, '公告新增', m.menu_id, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:appnotice:add', '#', 'admin', sysdate(), ''
from sys_menu m where m.perms = 'kangaroo:appnotice:list' and m.menu_type = 'C'
  and not exists (select 1 from sys_menu x where x.menu_id = 2482 or x.perms = 'kangaroo:appnotice:add') limit 1;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select 2483, '公告修改', m.menu_id, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:appnotice:edit', '#', 'admin', sysdate(), ''
from sys_menu m where m.perms = 'kangaroo:appnotice:list' and m.menu_type = 'C'
  and not exists (select 1 from sys_menu x where x.menu_id = 2483 or x.perms = 'kangaroo:appnotice:edit') limit 1;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select 2484, '公告删除', m.menu_id, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:appnotice:remove', '#', 'admin', sysdate(), ''
from sys_menu m where m.perms = 'kangaroo:appnotice:list' and m.menu_type = 'C'
  and not exists (select 1 from sys_menu x where x.menu_id = 2484 or x.perms = 'kangaroo:appnotice:remove') limit 1;

-- 能管「常见问题」的角色一并能管公告（同一类运营工作）；保留所有已有授权，只补不删
insert ignore into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, n.menu_id from sys_role_menu rm
join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:faq:list'
join sys_menu n on n.perms like 'kangaroo:appnotice:%';
insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:appnotice:%';

-- 种子公告：线上兼职须知。小程序线上任务详情页、任务交付页的「线上兼职须知」按固定标识 online-guide 打开它；
-- 面向关注线上任务的人，进小程序时弹窗一次。运营可以在后台改文字（改了会自动升版本、再弹一次），固定标识不能改。
insert into kg_app_notice (title, content, audience, popup, notice_key, version, status, sort, create_by, create_time)
select '线上兼职须知', '欢迎来袋鼠小院接线上兼职！整个流程是这样的：
1. 报名：在任务详情页点「报名申请」。
2. 接单：门店审核通过，就算接单成功，消息里会通知你。
3. 拿素材：门店会把任务素材发在任务里，打开任务就能看到；有不清楚的地方，可以私信主理人。
4. 交成果：按任务要求做完，在任务里提交成果，比如发帖链接、截图。
5. 验收：门店会检查成果，不合格会退回，按意见改好再提交就行。
6. 结算：报酬由门店按约定的方式线下支付，平台不经手资金。收到钱后，记得在任务里点「确认收款」；没收到或有争议，可以在任务里申诉，平台会介入核实。', 'online', '1', 'online-guide', 1, '0', 0, 'admin', sysdate()
from dual where not exists (select 1 from kg_app_notice where notice_key = 'online-guide');

-- =============================================================================
-- 关注门店 + 我的收入 + 线上任务「发素材」（V37_follow_income_material.sql）
-- =============================================================================
-- 关注门店 + 我的收入 + 线上任务「发素材」。只新增表和索引，不改动已有数据。
--
-- 本段新增（合并到 V37 时请一并加进启动时的表结构检查）：
--   新表  kg_online_material                       门店发给某位接单人的任务素材（文字 + 最多 9 张图）
--   新索引 kg_online_material.idx_material_app     (app_id, del_flag, sent_time)  交付页 / 门店交付记录按接单取素材
--   新索引 kg_online_material.idx_material_store   (store_id, sent_time)          门店侧按店查素材发送记录
--   新索引 kg_online_settlement.idx_online_settle_user (app_user_id, status, create_time)  「我的收入」按本人取结算单、按状态合计
--   新索引 kg_job_subscribe.idx_jsub_store         (store_id, status)             门店上新时找关注者 /「我的关注」与岗位详情判断是否已关注
--   没有新增列。
--
-- 关注门店沿用 kg_job_subscribe（只带 store_id、城市 / 岗位类型 / 关键词为空的一行就是一条关注），不新建表。
-- 我的收入只读 kg_online_settlement（线下结算单），平台不经手资金，也不新增任何资金表。

-- 任务素材：门店接单后给接单人发的拍摄 / 写作素材，可以分多次补发。只是信息展示，不参与成果提交和结算的状态流转。
create table if not exists kg_online_material (
  material_id   bigint        not null auto_increment comment '素材ID',
  app_id        bigint        not null                comment '报名（接单）kg_application',
  job_id        bigint        not null                comment '线上任务 kg_job（job_type = online）',
  store_id      bigint        not null                comment '门店',
  content       text          null                    comment '素材说明文字（1000 字内）',
  images        varchar(2000) not null default ''     comment '素材图片，逗号分隔的平台图片地址，最多 9 张',
  sent_by_id    bigint        null                    comment '发送员工（小程序账号ID）',
  sent_by       varchar(64)   not null default ''     comment '发送员工昵称',
  sent_time     datetime      not null                comment '发送时间',
  del_flag      char(1)       not null default '0'    comment '删除标志 0 正常 2 删除',
  primary key (material_id),
  key idx_material_app (app_id, del_flag, sent_time),
  key idx_material_store (store_id, sent_time)
) engine=innodb comment='线上任务素材（门店发给接单人）';
call kg_assert_same_collation('kg_online_material');

call kg_add_idx_if_absent('kg_online_settlement', 'idx_online_settle_user', 'key idx_online_settle_user (app_user_id, status, create_time)');
call kg_add_idx_if_absent('kg_job_subscribe', 'idx_jsub_store', 'key idx_jsub_store (store_id, status)');

-- =============================================================================
-- 积分：义工完成得积分 + 积分换加急投递 + 邀请新人送积分（V37_points_invite.sql）
-- =============================================================================
-- V37（积分流）：义工完成得积分 + 积分换加急投递 + 邀请新人送积分。
-- 积分仍记在 V12 的 kg_point_entry：唯一键 uk_point_biz (biz_type, biz_id, app_user_id) 已经能保证「同一件事只记一次」，不用改表。
-- 这一段只新增列、索引和后台参数；唯一改到已有数据的是最后一段「上线前已经结束的义工记为已完成、不补发积分」，见那里的说明。
--
-- 新表：无
-- 新列：
--   kg_application.urgent_flag          char(1) not null default '0'  加急投递：0 否 / 1 已加急（花积分换的，之后不会撤销）
--   kg_application.urgent_time          datetime null                 加急时间
--   kg_application.volunteer_done_time  datetime null                 义工完成时间（门店点「确认义工完成」，或计划结束 3 天后系统自动完成）
--   kg_application.volunteer_days       int null                      计积分的义工天数（门店填的实际天数；没填按报名天数；最多 180）
--   kg_application.volunteer_done_by    varchar(64) null              谁确认的：staff:员工账号ID / system 系统自动 / migration 上线前已结束（不计积分）
--   kg_app_user.invite_code             varchar(8) null               我的邀请码（第一次打开「我的邀请」时生成）
--   kg_app_user.invited_by              bigint null                   邀请我的人（kg_app_user.user_id），只能绑定一次、不能改
--   kg_app_user.invited_time            datetime null                 绑定邀请人的时间
-- 新索引：
--   kg_application.idx_app_volunteer_done  (status, volunteer_done_time)  定时任务找「计划已结束、还没完成」的义工
--   kg_app_user.uk_app_user_invite_code    unique (invite_code)           邀请码不能重复
--   kg_app_user.idx_app_user_invited_by    (invited_by)                   「我的邀请」列出我邀请的人
-- 新参数（sys_config，运营在 系统管理 → 参数设置 里改，改完马上生效；填错或删掉时程序按括号里的默认值算）：
--   kangaroo.points.volunteerPerDay  义工换宿每天得多少积分（默认 10，可填 0–1000，填 0 = 暂停义工积分）
--   kangaroo.points.inviteReward     邀请的朋友完成第一次义工 / 线上任务，邀请人得多少积分（默认 100，可填 0–10000，填 0 = 暂停邀请奖励）
--   kangaroo.points.urgentCost       加急投递一次花多少积分（默认 50，可填 1–10000）

-- ---------------------------------------------------------------- 加急投递
call kg_add_col_if_absent('kg_application', 'urgent_flag', 'urgent_flag char(1) not null default ''0'' comment ''加急投递：0 否 1 已加急（花积分换的，不会撤销）''');
call kg_add_col_if_absent('kg_application', 'urgent_time', 'urgent_time datetime null comment ''加急时间''');

-- ---------------------------------------------------------------- 义工完成
-- 先记下「这一列这次是不是新加的」，最后一段只在新加时执行一次
set @kg_v37_volunteer_new := (select count(*) = 0 from information_schema.columns
                              where table_schema = database() and table_name = 'kg_application' and column_name = 'volunteer_done_time');
call kg_add_col_if_absent('kg_application', 'volunteer_done_time', 'volunteer_done_time datetime null comment ''义工完成时间（门店确认，或计划结束 3 天后系统自动完成）''');
call kg_add_col_if_absent('kg_application', 'volunteer_days', 'volunteer_days int null comment ''计积分的义工天数（门店填的实际天数，没填按报名天数，最多 180）''');
call kg_add_col_if_absent('kg_application', 'volunteer_done_by', 'volunteer_done_by varchar(64) null comment ''确认人：staff:员工账号ID / system 自动 / migration 上线前已结束（不计积分）''');
call kg_add_idx_if_absent('kg_application', 'idx_app_volunteer_done', 'key idx_app_volunteer_done (status, volunteer_done_time)');

-- ---------------------------------------------------------------- 邀请
call kg_add_col_if_absent('kg_app_user', 'invite_code', 'invite_code varchar(8) null comment ''我的邀请码（第一次打开「我的邀请」时生成）''');
call kg_add_col_if_absent('kg_app_user', 'invited_by', 'invited_by bigint null comment ''邀请人（kg_app_user.user_id），只能绑定一次''');
call kg_add_col_if_absent('kg_app_user', 'invited_time', 'invited_time datetime null comment ''绑定邀请人的时间''');
call kg_add_idx_if_absent('kg_app_user', 'uk_app_user_invite_code', 'unique key uk_app_user_invite_code (invite_code)');
call kg_add_idx_if_absent('kg_app_user', 'idx_app_user_invited_by', 'key idx_app_user_invited_by (invited_by)');

-- ---------------------------------------------------------------- 后台参数（已有同名参数时不覆盖运营改过的值）
insert into sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
select '积分-义工每天得分', 'kangaroo.points.volunteerPerDay', '10', 'Y', 'admin', now(),
       '义工换宿完成后，每天给义工多少积分。填 0–1000 的整数；填 0 表示暂停发义工积分；填错按 10 算。'
from dual where not exists (select 1 from sys_config where config_key = 'kangaroo.points.volunteerPerDay');

insert into sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
select '积分-邀请新人奖励', 'kangaroo.points.inviteReward', '100', 'Y', 'admin', now(),
       '被邀请的新朋友完成第一次义工或第一次线上任务后，邀请人得多少积分（每位朋友只算一次）。填 0–10000 的整数；填 0 表示暂停；填错按 100 算。'
from dual where not exists (select 1 from sys_config where config_key = 'kangaroo.points.inviteReward');

insert into sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
select '积分-加急投递花费', 'kangaroo.points.urgentCost', '50', 'Y', 'admin', now(),
       '用户把一条等待门店审核的报名设为「加急」要花多少积分（每条报名只能加急一次，不退）。填 1–10000 的整数；填错按 50 算。'
from dual where not exists (select 1 from sys_config where config_key = 'kangaroo.points.urgentCost');

-- ---------------------------------------------------------------- 只算上线后完成的义工（不补发）
-- 上线前计划结束日就已经过去的义工报名（已入职），记为「已完成、不计积分」（volunteer_done_by = 'migration'），
-- 否则定时任务会把历史上所有结束过的义工一次性补发积分。计划结束日是今天或以后的，照常在完成时发积分。
-- 只在本次真正新加 volunteer_done_time 这一列时执行；重复执行本脚本不会再改任何数据。
update kg_application a join kg_job j on j.job_id = a.job_id
   set a.volunteer_done_time = now(), a.volunteer_days = 0, a.volunteer_done_by = 'migration'
 where @kg_v37_volunteer_new = 1 and j.job_type = 'volunteer' and a.status = 'onboarded'
   and a.volunteer_done_time is null and date_add(a.arrival_date, interval a.stay_days day) <= curdate();

-- =============================================================================
-- 私信交换联系方式（V37_chat_contact.sql）
-- =============================================================================
-- 私信交换联系方式（2026-10-11）
-- 新增列：kg_chat_member.contact_ok_time（我同意交换联系方式的时间；私信双方都不为空时手机号 / 微信号才显示）
-- 无新表、无新索引

call kg_add_col_if_absent('kg_chat_member', 'contact_ok_time', 'contact_ok_time datetime null comment ''同意交换联系方式的时间（私信双方都同意后才显示手机号 / 微信号）''');

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
values (37, 'points_notice_follow', now(), 'c54def993cb6dc9c607d712b2fc27e1decba84ec352fe598a271b7a412ceaf7b')
on duplicate key update name = 'points_notice_follow', applied_time = now(), checksum = 'c54def993cb6dc9c607d712b2fc27e1decba84ec352fe598a271b7a412ceaf7b';
