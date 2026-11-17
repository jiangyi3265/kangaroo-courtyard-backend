-- 换工术语回滚：只恢复本次备份；上线后被修改的记录会拒绝回滚。
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
call kg_assert_env('90');
drop procedure if exists kg_work_wording_rollback_guard;
delimiter $$
create procedure kg_work_wording_rollback_guard()
begin
if exists(select 1 from kg_work_exchange_wording_backup b left join sys_dict_type t on t.dict_id=b.row_id where b.table_name='sys_dict_type' and (t.dict_id is null or not (json_object('dict_name',t.dict_name,'remark',t.remark,'update_time',t.update_time)=b.new_values or json_object('dict_name',t.dict_name,'remark',t.remark,'update_time',t.update_time)=b.old_values))) then signal sqlstate '45000' set message_text='V90 rollback refused: sys_dict_type changed after migration'; end if;
if exists(select 1 from kg_work_exchange_wording_backup b left join sys_dict_data t on t.dict_code=b.row_id where b.table_name='sys_dict_data' and (t.dict_code is null or not (json_object('dict_label',t.dict_label,'remark',t.remark,'update_time',t.update_time)=b.new_values or json_object('dict_label',t.dict_label,'remark',t.remark,'update_time',t.update_time)=b.old_values))) then signal sqlstate '45000' set message_text='V90 rollback refused: sys_dict_data changed after migration'; end if;
if exists(select 1 from kg_work_exchange_wording_backup b left join sys_menu t on t.menu_id=b.row_id where b.table_name='sys_menu' and (t.menu_id is null or not (json_object('menu_name',t.menu_name,'update_time',t.update_time)=b.new_values or json_object('menu_name',t.menu_name,'update_time',t.update_time)=b.old_values))) then signal sqlstate '45000' set message_text='V90 rollback refused: sys_menu changed after migration'; end if;
if exists(select 1 from kg_work_exchange_wording_backup b left join sys_role t on t.role_id=b.row_id where b.table_name='sys_role' and (t.role_id is null or not (json_object('role_name',t.role_name,'update_time',t.update_time)=b.new_values or json_object('role_name',t.role_name,'update_time',t.update_time)=b.old_values))) then signal sqlstate '45000' set message_text='V90 rollback refused: sys_role changed after migration'; end if;
if exists(select 1 from kg_work_exchange_wording_backup b left join kg_job t on t.job_id=b.row_id where b.table_name='kg_job' and (t.job_id is null or not (json_object('title',t.title,'tags',t.tags,'content',t.content,'requirement',t.requirement,'update_time',t.update_time)=b.new_values or json_object('title',t.title,'tags',t.tags,'content',t.content,'requirement',t.requirement,'update_time',t.update_time)=b.old_values))) then signal sqlstate '45000' set message_text='V90 rollback refused: kg_job changed after migration'; end if;
if exists(select 1 from kg_work_exchange_wording_backup b left join kg_app_notice t on t.notice_id=b.row_id where b.table_name='kg_app_notice' and (t.notice_id is null or not (json_object('title',t.title,'content',t.content,'version',t.version,'update_time',t.update_time)=b.new_values or json_object('title',t.title,'content',t.content,'version',t.version,'update_time',t.update_time)=b.old_values))) then signal sqlstate '45000' set message_text='V90 rollback refused: kg_app_notice changed after migration'; end if;
end$$
delimiter ;
call kg_work_wording_rollback_guard();
drop procedure kg_work_wording_rollback_guard;
start transaction;
update sys_dict_type t join kg_work_exchange_wording_backup b on b.table_name='sys_dict_type' and b.row_id=t.dict_id set t.dict_name=case when json_type(b.old_values->'$.dict_name')='NULL' then null else json_unquote(b.old_values->'$.dict_name') end,t.remark=case when json_type(b.old_values->'$.remark')='NULL' then null else json_unquote(b.old_values->'$.remark') end,t.update_time=case when json_type(b.old_values->'$.update_time')='NULL' then null else json_unquote(b.old_values->'$.update_time') end where json_object('dict_name',t.dict_name,'remark',t.remark,'update_time',t.update_time)=b.new_values;
update sys_dict_data t join kg_work_exchange_wording_backup b on b.table_name='sys_dict_data' and b.row_id=t.dict_code set t.dict_label=case when json_type(b.old_values->'$.dict_label')='NULL' then null else json_unquote(b.old_values->'$.dict_label') end,t.remark=case when json_type(b.old_values->'$.remark')='NULL' then null else json_unquote(b.old_values->'$.remark') end,t.update_time=case when json_type(b.old_values->'$.update_time')='NULL' then null else json_unquote(b.old_values->'$.update_time') end where json_object('dict_label',t.dict_label,'remark',t.remark,'update_time',t.update_time)=b.new_values;
update sys_menu t join kg_work_exchange_wording_backup b on b.table_name='sys_menu' and b.row_id=t.menu_id set t.menu_name=case when json_type(b.old_values->'$.menu_name')='NULL' then null else json_unquote(b.old_values->'$.menu_name') end,t.update_time=case when json_type(b.old_values->'$.update_time')='NULL' then null else json_unquote(b.old_values->'$.update_time') end where json_object('menu_name',t.menu_name,'update_time',t.update_time)=b.new_values;
update sys_role t join kg_work_exchange_wording_backup b on b.table_name='sys_role' and b.row_id=t.role_id set t.role_name=case when json_type(b.old_values->'$.role_name')='NULL' then null else json_unquote(b.old_values->'$.role_name') end,t.update_time=case when json_type(b.old_values->'$.update_time')='NULL' then null else json_unquote(b.old_values->'$.update_time') end where json_object('role_name',t.role_name,'update_time',t.update_time)=b.new_values;
update kg_job t join kg_work_exchange_wording_backup b on b.table_name='kg_job' and b.row_id=t.job_id set t.title=case when json_type(b.old_values->'$.title')='NULL' then null else json_unquote(b.old_values->'$.title') end,t.tags=case when json_type(b.old_values->'$.tags')='NULL' then null else json_unquote(b.old_values->'$.tags') end,t.content=case when json_type(b.old_values->'$.content')='NULL' then null else json_unquote(b.old_values->'$.content') end,t.requirement=case when json_type(b.old_values->'$.requirement')='NULL' then null else json_unquote(b.old_values->'$.requirement') end,t.update_time=case when json_type(b.old_values->'$.update_time')='NULL' then null else json_unquote(b.old_values->'$.update_time') end where json_object('title',t.title,'tags',t.tags,'content',t.content,'requirement',t.requirement,'update_time',t.update_time)=b.new_values;
update kg_app_notice t join kg_work_exchange_wording_backup b on b.table_name='kg_app_notice' and b.row_id=t.notice_id set t.title=case when json_type(b.old_values->'$.title')='NULL' then null else json_unquote(b.old_values->'$.title') end,t.content=case when json_type(b.old_values->'$.content')='NULL' then null else json_unquote(b.old_values->'$.content') end,t.version=case when json_type(b.old_values->'$.version')='NULL' then null else json_unquote(b.old_values->'$.version') end,t.update_time=case when json_type(b.old_values->'$.update_time')='NULL' then null else json_unquote(b.old_values->'$.update_time') end where json_object('title',t.title,'content',t.content,'version',t.version,'update_time',t.update_time)=b.new_values;
delete from kg_schema_version where version=90 and name='work_exchange_wording';
commit;
drop table kg_work_exchange_wording_backup;
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
