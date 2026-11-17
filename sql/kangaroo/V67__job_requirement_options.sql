-- =============================================================================
-- V67__job_requirement_options（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('66');

-- V67：岗位「年龄」「性格」选项（2026-11-03 主人看换宿详情：「加一个性格，横向排列」「年龄选择年龄段，在后台管理配置」）。
-- 不建表、不加列：选项放在两个字典里，运营在后台「袋鼠小院 → 岗位要求选项」（页面 kangaroo/ext/jobopt/index，通用增删改，只认这两个字典）里
-- 加、改、排序、停用，不用去「系统管理 → 字典管理」。小程序发布岗位、义工主理人完善商家岗位、后台岗位表单读 /app/job/requirement-options
-- （后台表单读若依的 /system/dict/data/type），规则见 com.ruoyi.kangaroo.ext.core.KgJobRequirementOptions。
-- 选中的文字照旧写进岗位「要求」的行首（「年龄：18-30岁」「性格：活泼开朗、善于沟通」），kg_job 不动：老岗位手写的年龄照常显示。
-- 不依赖角色阶段；线上老 jar 不认这两个字典、也没有这个菜单指向的接口，退回老 jar 不用回滚这个迁移（菜单点进去是空的）。
-- 后端要在本段执行之后再发布：新代码启动时核对账本里有 V67（JobRequirementOptionsSchema），没有拒绝启动。
-- 只新增，不改动已有数据；全部语句可重复执行（字典、选项、菜单已在就跳过，授权用 insert ignore）。

-- 一、两个字典和默认选项。字典值 = 选项文字（岗位里存的就是文字）；「不限」= 不写年龄这一行
insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
select '岗位年龄段', 'kg_job_age', '0', 'admin', now(), '发布岗位时「年龄」能选的年龄段：小程序发布页、后台岗位表单都读这里；停用后不再出现在选项里，已经发出去的岗位照常显示。在「袋鼠小院 → 岗位要求选项」里改'
from dual where not exists (select 1 from sys_dict_type where dict_type = 'kg_job_age');
insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
select '岗位性格', 'kg_job_personality', '0', 'admin', now(), '发布岗位时「性格」能选的词（最多选 3 个）：小程序发布页、后台岗位表单都读这里；停用后不再出现在选项里，已经发出去的岗位照常显示。在「袋鼠小院 → 岗位要求选项」里改'
from dual where not exists (select 1 from sys_dict_type where dict_type = 'kg_job_personality');

insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 1, '不限', '不限', 'kg_job_age', '', 'default', 'N', '0', 'admin', now(), '选它 = 岗位上不写年龄'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_age' and dict_value = '不限');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 2, '18-25岁', '18-25岁', 'kg_job_age', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_age' and dict_value = '18-25岁');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 3, '18-30岁', '18-30岁', 'kg_job_age', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_age' and dict_value = '18-30岁');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 4, '18-35岁', '18-35岁', 'kg_job_age', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_age' and dict_value = '18-35岁');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 5, '18-40岁', '18-40岁', 'kg_job_age', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_age' and dict_value = '18-40岁');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 6, '18岁以上', '18岁以上', 'kg_job_age', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_age' and dict_value = '18岁以上');

insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 1, '活泼开朗', '活泼开朗', 'kg_job_personality', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_personality' and dict_value = '活泼开朗');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 2, '细心耐心', '细心耐心', 'kg_job_personality', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_personality' and dict_value = '细心耐心');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 3, '善于沟通', '善于沟通', 'kg_job_personality', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_personality' and dict_value = '善于沟通');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 4, '吃苦耐劳', '吃苦耐劳', 'kg_job_personality', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_personality' and dict_value = '吃苦耐劳');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 5, '有责任心', '有责任心', 'kg_job_personality', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_personality' and dict_value = '有责任心');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 6, '安静内向也欢迎', '安静内向也欢迎', 'kg_job_personality', '', 'default', 'N', '0', 'admin', now(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_job_personality' and dict_value = '安静内向也欢迎');

-- 二、菜单：袋鼠小院 → 岗位要求选项，紧挨着「岗位管理」（排序号和它一样）。上级按「岗位管理」（kangaroo:job:list）找，找不到再按根目录 path = 'kangaroo' 找，
-- 都找不到报错中止。优先用固定编号 2380–2384，被占时退给自增（和 V55、V66 同一写法）。
drop procedure if exists kg_v67_jobopt_menu;
delimiter $$
create procedure kg_v67_jobopt_menu()
begin
    declare v_parent bigint default null;
    declare v_order int default 4;
    declare v_menu bigint default null;
    if not exists (select 1 from sys_menu m where m.perms = 'kangaroo:jobopt:list') then
        set v_parent = (select m.parent_id from sys_menu m where m.perms = 'kangaroo:job:list' and m.menu_type = 'C'
                         and m.parent_id is not null and m.parent_id <> 0 order by m.menu_id limit 1);
        set v_order = coalesce((select m.order_num from sys_menu m where m.perms = 'kangaroo:job:list' and m.menu_type = 'C' order by m.menu_id limit 1), 4);
        if v_parent is null then
            set v_parent = (select m.menu_id from sys_menu m where m.parent_id = 0 and m.path = 'kangaroo' and m.menu_type = 'M' order by m.menu_id limit 1);
        end if;
        if v_parent is null then
            signal sqlstate '45000' set message_text = '[V67] 找不到「袋鼠小院」目录（岗位管理 kangaroo:job:list 的上级 / path = kangaroo 的根目录），已中止';
        end if;
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 2380) then null else 2380 end,
               '岗位要求选项', v_parent, v_order, 'jobopt', 'kangaroo/ext/jobopt/index', '', 'KgExtJobOpt', 1, 0, 'C', '0', '0', 'kangaroo:jobopt:list', 'dict', 'admin', now(),
               '发布岗位时「年龄」「性格」能选的选项：加、改、排序、停用。默认给超级管理员和能修改岗位的角色';
    end if;
    set v_menu = (select m.menu_id from sys_menu m where m.perms = 'kangaroo:jobopt:list' order by m.menu_id limit 1);
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 2381) then null else 2381 end,
           '选项查询', v_menu, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:jobopt:query', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:jobopt:query');
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 2382) then null else 2382 end,
           '选项新增', v_menu, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:jobopt:add', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:jobopt:add');
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 2383) then null else 2383 end,
           '选项修改', v_menu, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:jobopt:edit', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:jobopt:edit');
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 2384) then null else 2384 end,
           '选项删除', v_menu, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:jobopt:remove', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:jobopt:remove');
end$$
delimiter ;
call kg_v67_jobopt_menu();
drop procedure kg_v67_jobopt_menu;

-- 授权：能修改岗位（kangaroo:job:edit）的角色一并能改选项；超级管理员（role 1）也补上。只补不删
insert ignore into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, n.menu_id from sys_role_menu rm
join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:job:edit'
join sys_menu n on n.perms like 'kangaroo:jobopt:%';
insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:jobopt:%';

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
values (67, 'job_requirement_options', now(), '89a9b5173f2eedf151cc0cd66d7553f8499f84bedfb535f43de5827065de744d')
on duplicate key update name = 'job_requirement_options', applied_time = now(), checksum = '89a9b5173f2eedf151cc0cd66d7553f8499f84bedfb535f43de5827065de744d';
