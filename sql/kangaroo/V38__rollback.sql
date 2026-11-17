-- V38 回退：删除积分流水的 idx_point_user 索引，并把账本退回 37。
-- 收紧掉的小程序公告权限不自动加回（加回等于重新放开只读角色发公告）；确需加回时，在后台「角色管理」里按角色手动勾选。
-- 需要先停应用，并显式 set @kg_allow_review_fixes_rollback = 1。
drop procedure if exists kg_rollback_review_fixes;
delimiter $$
create procedure kg_rollback_review_fixes()
begin
    if coalesce(@kg_allow_review_fixes_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V38 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 38) then
        signal sqlstate '45000' set message_text = 'V38 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.statistics where table_schema = database() and table_name = 'kg_point_entry' and index_name = 'idx_point_user') then
        alter table kg_point_entry drop index idx_point_user;
    end if;
    delete from kg_schema_version where version = 38;
end$$
delimiter ;
call kg_rollback_review_fixes();
drop procedure kg_rollback_review_fixes;
