-- =============================================================================
-- V76__membership_levels（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('75');

-- 根据用户提供的会员与评价规则更新六级门槛、等级权益说明。
-- 保留已确认的等级名称 / 图标序号，不重写历史积分、不扣分、不重复发放权益。
-- 首次执行备份配置；重跑不覆盖管理员后续编辑。
create table if not exists kg_member_level_v76_backup like kg_member_level;
call kg_assert_same_collation('kg_member_level_v76_backup');
drop procedure if exists kg_apply_member_levels_v76;
delimiter $$
create procedure kg_apply_member_levels_v76()
begin
  declare exit handler for sqlexception begin rollback; resignal; end;
  if not exists(select 1 from kg_schema_version where version=76) then
    if (select count(*) from kg_member_level) <> 6 or
       (select count(*) from kg_member_level where level_id between 1 and 6) <> 6 then
      signal sqlstate '45000' set message_text='V76 requires the existing six member levels (IDs 1-6)';
    end if;
    start transaction;
    insert ignore into kg_member_level_v76_backup select * from kg_member_level;
    update kg_member_level set min_points=0, description='基础浏览与报名', update_time=now(), update_by='migration:V76' where level_id=1;
    update kg_member_level set min_points=15, description='岗位优先推荐
基础会员称号', update_time=now(), update_by='migration:V76' where level_id=2;
    update kg_member_level set min_points=40, description='袋鼠周边任选一
民宿类合作商家底价（较公开渠道低 5%–15%）
等级专属称号
帖子每周置顶 1 次，每次 2 小时', update_time=now(), update_by='migration:V76' where level_id=3;
    update kg_member_level set min_points=90, description='每年四人间免费 1 晚
每年小院活动免费参加 1 次
出游补贴 30 元
全品类合作商家底价
彩色等级称号
帖子每周置顶 2 次，每次 2 小时
留言高亮与专属头像框', update_time=now(), update_by='migration:V76' where level_id=4;
    update kg_member_level set min_points=200, description='每年四人间免费 2 晚、双人间免费 1 晚
出游补贴 80 元
全品类合作商家底价
热门日期优先预订
彩色称号与等级图标
帖子每周置顶 3 次，每次 2 小时
留言高亮、专属头像框与个人徽章', update_time=now(), update_by='migration:V76' where level_id=5;
    update kg_member_level set min_points=400, description='每年四人间免费 3 晚、双人间免费 2 晚、单人间免费 1 晚
出游补贴 150 元
全品类合作商家底价与优先预订
专属客服，每年免费升级房型 1 次
每年旅行礼包
每年可向亲友转赠最多 3 晚免费间夜，限 1 次
彩色称号、等级图标与专属标识
帖子每周置顶 5 次，每次 2 小时
留言高亮、专属头像框与个人徽章
社区优先推荐', update_time=now(), update_by='migration:V76' where level_id=6;
    commit;
  end if;
end$$
delimiter ;
call kg_apply_member_levels_v76();
drop procedure if exists kg_apply_member_levels_v76;

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
values (76, 'membership_levels', now(), '4f6ef35f497dc85a028c2286f6b8a05263df986b0050f3a8c0f39ace181510de')
on duplicate key update name = 'membership_levels', applied_time = now(), checksum = '4f6ef35f497dc85a028c2286f6b8a05263df986b0050f3a8c0f39ace181510de';
