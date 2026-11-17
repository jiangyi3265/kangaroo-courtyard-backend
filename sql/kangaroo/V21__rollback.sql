-- 新应用停止且活动附选记录已归档后才可回滚；不静默删除用户的选择。
drop procedure if exists kg_rollback_stay_activity;
delimiter $$
create procedure kg_rollback_stay_activity()
begin
    if database()<>'dkxy_guardtest' and coalesce(@kg_allow_stay_activity_rollback,0)<>1 then
        signal sqlstate '45000' set message_text='V21 rollback requires stopped application and explicit authorization';
    end if;
    if exists(select 1 from kg_schema_version where version>21) then
        signal sqlstate '45000' set message_text='V21 rollback blocked by later migrations';
    end if;
    if exists(select 1 from information_schema.tables where table_schema=database() and table_name='kg_stay_activity_selection') then
        if exists(select 1 from kg_stay_activity_selection) then
            signal sqlstate '45000' set message_text='V21 contains user selections; archive them before rollback';
        end if;
        drop table kg_stay_activity_selection;
    end if;
    delete from kg_schema_version where version=21;
end$$
delimiter ;
call kg_rollback_stay_activity();
drop procedure kg_rollback_stay_activity;
