-- V3 回退：删除岗位分类展示字段（不影响其他列与数据）
--
-- 这里原先写的是 alter table kg_job drop column if exists ... —— 那是 MariaDB 的语法，
-- MySQL 8 没有 drop column if exists，整条语句一执行就报 ERROR 1064，一列都删不掉（WP01 修）。
-- 改用与迁移头部标准片段（_snippets/header.sql）同一写法的 kg_drop_col_if_present：
-- 列在就删、不在就跳过，可重复执行。按 V3 加列的倒序删。
-- C17 会在隔离库上实跑"V3 前滚 → 重复前滚 → 回滚 → 再前滚"并逐项比对整库快照。
drop procedure if exists kg_drop_col_if_present;
delimiter $$
create procedure kg_drop_col_if_present(in p_table varchar(64), in p_column varchar(64))
begin
    if exists (select 1 from information_schema.columns
               where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @kg_s = concat('alter table ', p_table, ' drop column ', p_column);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$
delimiter ;

call kg_drop_col_if_present('kg_job', 'publisher_name');
call kg_drop_col_if_present('kg_job', 'remote_flag');
call kg_drop_col_if_present('kg_job', 'est_hours');
call kg_drop_col_if_present('kg_job', 'deliver_desc');
call kg_drop_col_if_present('kg_job', 'benefits');
call kg_drop_col_if_present('kg_job', 'probation');
call kg_drop_col_if_present('kg_job', 'train_desc');
call kg_drop_col_if_present('kg_job', 'shift_time');

drop procedure if exists kg_drop_col_if_present;
