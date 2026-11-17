-- =============================================================================
-- V70__stay_week_price_options（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('51');

-- V70：房源管理的三处字典 / 一列（2026-11-03 主人看员工端「房源管理」和「新增 / 编辑房源」）：
--   一、来源类型（门店运营类型 kg_store.op_type，字典 kg_store_op_type）：「来源类型，分成直营，加盟，代运营，合作」→ 新增 partner 合作，
--       顺序改成 直营、加盟、代运营、合作（只改排序号，已存的 direct / managed / franchise 照常有效）。合作和代运营、加盟一样算非直营：
--       收费活动不由平台在线代收，旅居列表归「袋鼠合作」那一类。后台「门店管理」的运营类型下拉读这个字典，改完就有「合作」。
--   二、配套设施（字典 kg_stay_facility）：「增加一个地暖」→ 新增 floorheat 地暖，排在健身房后面。员工端设施勾选、房源详情设施图标都读它。
--   三、房型 7 天价（kg_stay_room_type.week_price_fen）：「必须7天，填写7天的价格页面优化一下不要弹窗」→ 周一到周日 7 个每晚价（分），逗号分隔；
--       空 = 每天按 base_price_fen（老房型不动，照旧）。填了 7 天价时应用把 base_price_fen 存成其中最低的（列表「¥X 起」、价格筛选不用改）；
--       某晚的价 = 房态日历单独设的当晚价 → 这天是星期几的 7 天价 → 基础价（com.ruoyi.kangaroo.ext.stay.KgRoomWeekPrice）。
-- 后端要在本段执行之后再发布：新代码启动时核对这一列和账本（StayWeekPriceSchema），没有拒绝启动。
-- 老 jar 不认这一列（insert / update 不写它，select * 多一列无妨）、不认 partner（标签显示成直营）、地暖只是多一个选项：退回老 jar 不用回滚，
-- 但已经用了「合作」的门店在老 jar 里会显示成直营、活动会按直营在线代收，退回前先把它们改回原来的类型。
-- 只新增或改排序号，不删除、不改已有业务数据；全部语句可重复执行。

-- 一、来源类型：合作 + 排序 直营 1、加盟 2、代运营 3、合作 4
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 4, '合作', 'partner', 'kg_store_op_type', '', 'primary', 'N', '0', 'admin', now(), '2026-11-03 主人加：合作门店，按非直营处理（收费活动线下收）'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_store_op_type' and dict_value = 'partner');
update sys_dict_data set dict_sort = 1 where dict_type = 'kg_store_op_type' and dict_value = 'direct';
update sys_dict_data set dict_sort = 2 where dict_type = 'kg_store_op_type' and dict_value = 'franchise';
update sys_dict_data set dict_sort = 3 where dict_type = 'kg_store_op_type' and dict_value = 'managed';
update sys_dict_data set dict_sort = 4 where dict_type = 'kg_store_op_type' and dict_value = 'partner';

-- 二、配套设施：地暖
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 10, '地暖', 'floorheat', 'kg_stay_facility', '', '', 'N', '0', 'admin', now(), '2026-11-03 主人加：房间有地暖'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_stay_facility' and dict_value = 'floorheat');

-- 三、房型 7 天价
call kg_add_col_if_absent('kg_stay_room_type', 'week_price_fen', 'week_price_fen varchar(80) null default null comment ''周一到周日每晚价（分，逗号分隔 7 个；空 = 每天按 base_price_fen）'' after base_price_fen');

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
values (70, 'stay_week_price_options', now(), 'fb767044f76b6a902ee8d6bcfc428d3b5c597e86f61ec9d4706f448ddde18867')
on duplicate key update name = 'stay_week_price_options', applied_time = now(), checksum = 'fb767044f76b6a902ee8d6bcfc428d3b5c597e86f61ec9d4706f448ddde18867';
