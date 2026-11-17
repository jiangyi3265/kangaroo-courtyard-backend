-- Stop the application, take a backup, then explicitly authorize this rollback.
-- Existing remote applications with NULL scheduling fields require retaining V28.
-- Never invent arrival dates/stay lengths to force a NOT NULL rollback.
drop procedure if exists kg_rollback_remote_schedule;
delimiter $$
create procedure kg_rollback_remote_schedule()
begin
    if coalesce(@kg_allow_v28_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V28 rollback requires stopped application and explicit authorization';
    end if;
    if not exists (select 1 from kg_schema_version where version = 28) then
        signal sqlstate '45000' set message_text = 'V28 is not applied; refuse rollback against unowned schema objects';
    end if;
    if exists (select 1 from kg_schema_version where version > 28) then
        signal sqlstate '45000' set message_text = 'V28 rollback blocked by later migrations';
    end if;
    if exists (select 1 from kg_application where arrival_date is null or stay_days is null) then
        signal sqlstate '45000' set message_text = 'V28 has applications without arrival/stay data; retain nullable columns';
    end if;
    alter table kg_application
        modify column arrival_date date not null comment '预计到岗日期',
        modify column stay_days int(11) not null comment '可停留天数';
    delete from kg_schema_version where version = 28;
end$$
delimiter ;
call kg_rollback_remote_schedule();
drop procedure kg_rollback_remote_schedule;
