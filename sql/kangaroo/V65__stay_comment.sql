-- =============================================================================
-- V65__stay_comment（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('60,62,63,64');

-- V65：小院评论（2026-10-31 主人拍板）：住在小院的小伙伴写评论，文字 + 图片或视频，一律先审后显示。
-- 和「住过的家人评价」完全分开：不打星，不进综合分、AI 总结、积分；kg_review、kg_stay_diary 一行不改。
-- 依赖写 '60,62,63,64'：生产账本没有 V61，这里不依赖 kg_store.images。
-- 不依赖角色阶段：legacy 和 new 下行为相同。菜单只属于角色 1（超管），运营账号由主人后续授权。

create table if not exists kg_stay_comment (
  comment_id         bigint        not null auto_increment  comment '评论ID',
  stay_id            bigint        not null                 comment '小院（kg_stay.stay_id）',
  stay_name          varchar(64)   not null default ''      comment '提交时的小院名字（审核列表直接看）',
  store_id           bigint        not null                 comment '提交时小院所属门店（kg_stay.store_id，通知小院主理人用）',
  app_user_id        bigint        not null                 comment '作者',
  source_type        varchar(16)   not null default 'unverified' comment '提交时服务端算的身份：guest 已核实住客 / volunteer 已核实义工 / unverified 未核实',
  source_id          bigint        null                     comment '身份依据：guest 是 kg_stay_order.order_id，volunteer 是 kg_application.app_id',
  content            varchar(500)  not null                 comment '文字，1–500 字',
  images             varchar(1000) not null default ''      comment '图片，平台路径逗号分隔，最多 9 张；和视频二选一',
  video_url          varchar(255)  not null default ''      comment '视频平台路径，最多 1 段；和图片二选一',
  video_cover        varchar(255)  not null default ''      comment '视频封面平台路径',
  portrait_confirmed char(1)       not null default '0'     comment '带图或视频时作者确认不侵犯他人肖像：1 已确认 / 0 没有（纯文字）',
  risk_flag          varchar(16)   not null default 'none'  comment '给审核的人看：none 无 / contact 含联系方式 / word 命中复核词',
  status             varchar(16)   not null default 'pending' comment 'pending 待审核 / online 已通过 / rejected 已驳回 / offline 已下架',
  audit_reason       varchar(255)  null                     comment '驳回 / 下架理由',
  audit_by           varchar(64)   null                     comment '审核人',
  audit_time         datetime      null                     comment '审核时间',
  reported           int           not null default 0       comment '被举报次数',
  anonymous          char(1)       not null default '0'     comment '1 = 作者已注销，转匿名展示',
  del_flag           char(1)       not null default '0'     comment '0 正常 / 2 已删除（本人删除或注销）',
  create_by          varchar(64)   not null default ''      comment '创建者（app:用户ID）',
  create_time        datetime      not null                 comment '提交时间',
  update_by          varchar(64)   not null default ''      comment '更新者',
  update_time        datetime      null                     comment '更新时间',
  primary key (comment_id),
  key idx_stay_comment_list (stay_id, status, del_flag, comment_id),
  key idx_stay_comment_user (app_user_id, stay_id, status),
  key idx_stay_comment_user_time (app_user_id, create_time),
  key idx_stay_comment_audit (status, del_flag, comment_id)
) engine=innodb comment='小院评论（小伙伴写，先审后显示；和 kg_review 无关）';
call kg_assert_same_collation('kg_stay_comment');

-- 菜单：放在「旅居」目录，和 Vlog 审核同一个上级。优先用固定编号，被占时退给自增（和 V53、V64 同一写法）。
drop procedure if exists kg_v65_stay_comment_menu;
delimiter $$
create procedure kg_v65_stay_comment_menu()
begin
    declare v_parent bigint default null;
    declare v_menu bigint default null;
    set v_parent = (select m.parent_id from sys_menu m where m.perms = 'kangaroo:stayvlog:list' order by m.menu_id limit 1);
    if v_parent is null then
        signal sqlstate '45000' set message_text = '[V65] 找不到「Vlog 审核」菜单的上级目录，已中止';
    end if;
    if not exists (select 1 from sys_menu m where m.perms = 'kangaroo:staycomment:list') then
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 2285) then null else 2285 end,
               '小院评论审核', v_parent, 10, 'staycomment', 'kangaroo/ext/staycomment/index', '', 'KgExtStayComment', 1, 0, 'C', '0', '0', 'kangaroo:staycomment:list', 'message', 'admin', now(),
               '小伙伴写的小院评论：通过、驳回、下架。默认只给超级管理员';
    end if;
    set v_menu = (select m.menu_id from sys_menu m where m.perms = 'kangaroo:staycomment:list' order by m.menu_id limit 1);
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 2286) then null else 2286 end,
           '评论查询', v_menu, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staycomment:query', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:staycomment:query');
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 2287) then null else 2287 end,
           '评论审核', v_menu, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staycomment:audit', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:staycomment:audit');
end$$
delimiter ;
call kg_v65_stay_comment_menu();
drop procedure kg_v65_stay_comment_menu;

insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:staycomment:%';

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
values (65, 'stay_comment', now(), '390db109a9392f2bcec09642a1d8682e4c9bd109b976d24092eb8752ab563c1f')
on duplicate key update name = 'stay_comment', applied_time = now(), checksum = '390db109a9392f2bcec09642a1d8682e4c9bd109b976d24092eb8752ab563c1f';
