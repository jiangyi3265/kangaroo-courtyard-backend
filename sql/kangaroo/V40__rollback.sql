-- V40 回退：删除 kg_member_benefit.intro（会员权益的店铺介绍）。
-- 已经有权益填了店铺介绍时拒绝回退（会丢数据）；需要先停应用，并显式 set @kg_allow_benefit_intro_rollback = 1。
drop procedure if exists kg_rollback_benefit_intro;
delimiter $$
create procedure kg_rollback_benefit_intro()
begin
    if coalesce(@kg_allow_benefit_intro_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V40 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 40) then
        signal sqlstate '45000' set message_text = 'V40 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_member_benefit' and column_name = 'intro') then
        if exists (select 1 from kg_member_benefit where coalesce(intro, '') <> '') then
            signal sqlstate '45000' set message_text = 'V40 rollback blocked: store introductions still exist';
        end if;
        alter table kg_member_benefit drop column intro;
    end if;
    delete from kg_schema_version where version = 40;
end$$
delimiter ;
call kg_rollback_benefit_intro();
drop procedure kg_rollback_benefit_intro;
