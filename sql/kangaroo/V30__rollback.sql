-- Stop the application, take a backup, then explicitly authorize this rollback.
-- Existing remote applications may omit age. Preserve that fact and retain V30;
-- do not fabricate an age or delete applications to satisfy the old schema.
drop procedure if exists kg_rollback_remote_age;
delimiter $$
create procedure kg_rollback_remote_age()
begin
    if coalesce(@kg_allow_v30_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V30 rollback requires stopped application and explicit authorization';
    end if;
    if not exists (select 1 from kg_schema_version where version = 30) then
        signal sqlstate '45000' set message_text = 'V30 is not applied; refuse rollback against unowned schema objects';
    end if;
    if exists (select 1 from kg_schema_version where version > 30) then
        signal sqlstate '45000' set message_text = 'V30 rollback blocked by later migrations';
    end if;
    if exists (select 1 from kg_application where age is null) then
        signal sqlstate '45000' set message_text = 'V30 has applications without age; retain the nullable age column';
    end if;
    alter table kg_application modify column age int(11) not null comment '年龄';
    delete from kg_schema_version where version = 30;
end$$
delimiter ;
call kg_rollback_remote_age();
drop procedure kg_rollback_remote_age;
