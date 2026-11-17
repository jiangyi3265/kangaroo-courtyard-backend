-- =============================================================================
-- V53__stay_media（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('52');

-- V53（2026-10-17）：旅居改版 R3（主人效果图 7 小院详情、「近期生活碎片」页、评价视频）—— 小院视频、房型视频、生活碎片、评价视频。
--   主人拍板：视频由员工端上传（房源管理 → 视频与生活碎片），生活碎片的背景图、文案、照片视频也都是员工端上传；住客写住宿评价可以附 1 段视频。
--   视频是公开内容，和公开图片放在同一处（若依 profile 目录 upload/videos/yyyy/MM/dd/{uuid}.mp4，经 /profile/** 访问，支持拖动进度的 Range 请求），
--   库里只存平台路径（/profile/upload/videos/…），别处的网址进不来。
--   · kg_stay.intro_video / intro_video_cover varchar(255)：「3分钟看小院」视频和封面；'' = 没传，详情页不出这张卡。
--   · kg_stay.diary_bg varchar(255)：「近期生活碎片」页顶部背景图；'' = 没传（页面用房源封面）。
--   · kg_stay.diary_title varchar(32)：生活碎片页标题；'' = 用默认的「小院日记」。
--   · kg_stay.diary_intro varchar(255)：生活碎片页顶部的两三行文案（换行分隔，最多 3 行）；'' = 用默认的一句。
--   · kg_stay_room_type.video_url / video_cover varchar(255)：房型短视频（≤1分钟）和封面；'' = 没传，房型卡不出播放按钮。
--   · kg_review.videos / video_covers varchar(600) null：评价视频（逗号拼接的平台路径，现在最多 1 段）和对应封面；null = 没有。
--   · 新表 kg_stay_diary：生活碎片一条一行（照片或视频 + 一句话 + 拍摄日期），员工端增删、排序，后台可隐藏 / 删除。
--   · 后台菜单：旅居 → 生活碎片（kangaroo:staydiary:list / query / edit / remove），能管 Vlog 审核的角色一并能管。
-- 只加列、加表、加菜单，不改动任何已有数据；全部语句可重复执行（列 / 表 / 索引 / 菜单已在就跳过）。
-- 后端要在本段执行之后再发布：新代码读写这些列和新表，缺了拒绝启动（StayMediaSchema）。
-- 老版本程序不受影响：不读不写这些列（新增房源、房型、评价时靠默认值落成 '' / null），也不碰新表。

-- =============================================================================
-- 一、小院视频、生活碎片顶部（kg_stay）
-- =============================================================================
call kg_add_col_if_absent('kg_stay', 'intro_video', 'intro_video varchar(255) not null default '''' comment ''「3分钟看小院」视频（平台路径 /profile/upload/videos/…；空 = 没传）'' after images');
call kg_add_col_if_absent('kg_stay', 'intro_video_cover', 'intro_video_cover varchar(255) not null default '''' comment ''小院视频封面（平台图片路径；空 = 没传，页面用房源封面）'' after intro_video');
call kg_add_col_if_absent('kg_stay', 'diary_bg', 'diary_bg varchar(255) not null default '''' comment ''生活碎片页顶部背景图（空 = 用房源封面）'' after intro_video_cover');
call kg_add_col_if_absent('kg_stay', 'diary_title', 'diary_title varchar(32) not null default '''' comment ''生活碎片页标题（空 = 小院日记）'' after diary_bg');
call kg_add_col_if_absent('kg_stay', 'diary_intro', 'diary_intro varchar(255) not null default '''' comment ''生活碎片页文案（换行分隔，最多 3 行；空 = 默认一句）'' after diary_title');

-- =============================================================================
-- 二、房型短视频（kg_stay_room_type）
-- =============================================================================
call kg_add_col_if_absent('kg_stay_room_type', 'video_url', 'video_url varchar(255) not null default '''' comment ''房型短视频（≤1分钟，平台路径；空 = 没传）'' after cover');
call kg_add_col_if_absent('kg_stay_room_type', 'video_cover', 'video_cover varchar(255) not null default '''' comment ''房型视频封面（空 = 用房型图）'' after video_url');

-- =============================================================================
-- 三、评价视频（kg_review）
-- =============================================================================
call kg_add_col_if_absent('kg_review', 'videos', 'videos varchar(600) null comment ''评价视频（逗号拼接的平台路径，最多 1 段；null = 没有）'' after images');
call kg_add_col_if_absent('kg_review', 'video_covers', 'video_covers varchar(600) null comment ''评价视频封面（和 videos 一一对应，逗号拼接；没封面的位置空着）'' after videos');

-- =============================================================================
-- 四、生活碎片（新表 kg_stay_diary）
-- =============================================================================
create table if not exists kg_stay_diary (
  diary_id     bigint       not null auto_increment comment '主键',
  stay_id      bigint       not null                comment '房源（kg_stay.stay_id）',
  store_id     bigint       not null                comment '门店（kg_stay.store_id，员工端按门店授权管）',
  media_type   varchar(8)   not null default 'image' comment 'image 照片 / video 视频',
  media_url    varchar(255) not null                comment '照片或视频的平台路径（/profile/upload/…）',
  cover_url    varchar(255) not null default ''     comment '视频封面（照片为空）',
  caption      varchar(32)  not null default ''     comment '一句话（火烧云 / 早餐 / 露台…）',
  shot_date    date         null                    comment '拍摄日期（卡片左上角的 10.12）',
  sort         int          not null default 0      comment '排序（小的在前；同排序按拍摄日期新的在前）',
  status       char(1)      not null default '0'    comment '0 显示 / 1 隐藏（后台审核隐藏）',
  create_by    varchar(64)  not null default ''     comment '创建者（app:用户ID）',
  create_time  datetime     not null                comment '创建时间',
  update_by    varchar(64)  not null default ''     comment '更新者',
  update_time  datetime     null                    comment '更新时间',
  del_flag     char(1)      not null default '0'    comment '0 正常 / 2 已删除',
  primary key (diary_id),
  key idx_stay_diary_list (stay_id, del_flag, shot_date)
) engine=innodb comment='小院生活碎片（员工端上传的照片 / 视频 + 一句话）';
call kg_assert_same_collation('kg_stay_diary');
call kg_add_idx_if_absent('kg_stay_diary', 'idx_stay_diary_list', 'key idx_stay_diary_list (stay_id, del_flag, shot_date)');

-- =============================================================================
-- 五、后台菜单：旅居 → 生活碎片（和「Vlog 审核」同一个上级；菜单号被占时让数据库自己编号）
-- =============================================================================
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select case when exists (select 1 from sys_menu x where x.menu_id = 2295) then null else 2295 end,
       '生活碎片', m.parent_id, 9, 'staydiary', 'kangaroo/ext/staydiary/index', '', 'KgExtStayDiary', 1, 0, 'C', '0', '0', 'kangaroo:staydiary:list', 'education', 'admin', sysdate(),
       '小院「近期生活碎片」：员工端上传的照片 / 视频，可隐藏、删除'
from sys_menu m where m.perms = 'kangaroo:stayvlog:list' and m.menu_type = 'C'
  and not exists (select 1 from sys_menu x where x.perms = 'kangaroo:staydiary:list') limit 1;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select case when exists (select 1 from sys_menu x where x.menu_id = 2296) then null else 2296 end,
       '碎片查询', m.menu_id, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staydiary:query', '#', 'admin', sysdate(), ''
from sys_menu m where m.perms = 'kangaroo:staydiary:list' and m.menu_type = 'C'
  and not exists (select 1 from sys_menu x where x.perms = 'kangaroo:staydiary:query') limit 1;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select case when exists (select 1 from sys_menu x where x.menu_id = 2297) then null else 2297 end,
       '碎片修改', m.menu_id, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staydiary:edit', '#', 'admin', sysdate(), ''
from sys_menu m where m.perms = 'kangaroo:staydiary:list' and m.menu_type = 'C'
  and not exists (select 1 from sys_menu x where x.perms = 'kangaroo:staydiary:edit') limit 1;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
select case when exists (select 1 from sys_menu x where x.menu_id = 2298) then null else 2298 end,
       '碎片删除', m.menu_id, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staydiary:remove', '#', 'admin', sysdate(), ''
from sys_menu m where m.perms = 'kangaroo:staydiary:list' and m.menu_type = 'C'
  and not exists (select 1 from sys_menu x where x.perms = 'kangaroo:staydiary:remove') limit 1;

-- 能管「Vlog 审核」的角色一并能管生活碎片（同一类内容审核工作）；超级管理员（role 1）也补上。只补不删
insert ignore into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, n.menu_id from sys_role_menu rm
join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:stayvlog:list'
join sys_menu n on n.perms like 'kangaroo:staydiary:%';
insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:staydiary:%';

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
values (53, 'stay_media', now(), '7805975c5ace5c75e040d3167c5722e6da8812f8443a65f9fad3ea36a22b00fd')
on duplicate key update name = 'stay_media', applied_time = now(), checksum = '7805975c5ace5c75e040d3167c5722e6da8812f8443a65f9fad3ea36a22b00fd';
