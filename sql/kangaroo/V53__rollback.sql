-- V53 回退：去掉 R3 加的小院视频 / 生活碎片顶部（kg_stay.intro_video、intro_video_cover、diary_bg、diary_title、diary_intro）、
-- 房型视频（kg_stay_room_type.video_url、video_cover）、评价视频（kg_review.videos、video_covers）、生活碎片表 kg_stay_diary 和后台「生活碎片」菜单。
-- 需要先停应用（新版本程序发现缺这些列 / 表拒绝启动），并显式 set @kg_allow_stay_media_rollback = 1。
-- 已经有人传过视频、写过生活碎片、评价带了视频时，回退会丢掉这些引用（视频文件本身留在 upload/videos/ 下不删），
-- 默认拒绝；确认可以丢再显式 set @kg_allow_stay_media_loss = 1。
-- 回退后旧版本程序照常运行：详情页没有视频卡和生活碎片入口，评价只有文字和照片。
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

drop procedure if exists kg_rollback_stay_media;
delimiter $$
create procedure kg_rollback_stay_media()
begin
    if coalesce(@kg_allow_stay_media_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V53 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 53) then
        signal sqlstate '45000' set message_text = 'V53 rollback blocked by later migrations';
    end if;
    if coalesce(@kg_allow_stay_media_loss, 0) <> 1 then
        set @kg_v53_used = 0;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'intro_video') then
            set @kg_s = 'select count(*) into @kg_v53_used from kg_stay where intro_video <> '''' or intro_video_cover <> '''' or diary_bg <> '''' or diary_title <> '''' or diary_intro <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v53_used > 0 then
                signal sqlstate '45000' set message_text = 'V53 rollback blocked: stays still have videos / diary settings (set @kg_allow_stay_media_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay_room_type' and column_name = 'video_url') then
            set @kg_s = 'select count(*) into @kg_v53_used from kg_stay_room_type where video_url <> '''' or video_cover <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v53_used > 0 then
                signal sqlstate '45000' set message_text = 'V53 rollback blocked: room types still have videos (set @kg_allow_stay_media_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_review' and column_name = 'videos') then
            set @kg_s = 'select count(*) into @kg_v53_used from kg_review where coalesce(videos, '''') <> ''''';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v53_used > 0 then
                signal sqlstate '45000' set message_text = 'V53 rollback blocked: reviews still have videos (set @kg_allow_stay_media_loss = 1)';
            end if;
        end if;
        if exists (select 1 from information_schema.tables where table_schema = database() and table_name = 'kg_stay_diary') then
            set @kg_s = 'select count(*) into @kg_v53_used from kg_stay_diary';
            prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
            if @kg_v53_used > 0 then
                signal sqlstate '45000' set message_text = 'V53 rollback blocked: kg_stay_diary has entries (set @kg_allow_stay_media_loss = 1)';
            end if;
        end if;
    end if;
    delete rm from sys_role_menu rm join sys_menu m on m.menu_id = rm.menu_id where m.perms like 'kangaroo:staydiary:%';
    delete from sys_menu where perms like 'kangaroo:staydiary:%';
    drop table if exists kg_stay_diary;
    call kg_drop_col_if_present('kg_review', 'video_covers');
    call kg_drop_col_if_present('kg_review', 'videos');
    call kg_drop_col_if_present('kg_stay_room_type', 'video_cover');
    call kg_drop_col_if_present('kg_stay_room_type', 'video_url');
    call kg_drop_col_if_present('kg_stay', 'diary_intro');
    call kg_drop_col_if_present('kg_stay', 'diary_title');
    call kg_drop_col_if_present('kg_stay', 'diary_bg');
    call kg_drop_col_if_present('kg_stay', 'intro_video_cover');
    call kg_drop_col_if_present('kg_stay', 'intro_video');
    delete from kg_schema_version where version = 53;
end$$
delimiter ;
call kg_rollback_stay_media();
drop procedure kg_rollback_stay_media;

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
