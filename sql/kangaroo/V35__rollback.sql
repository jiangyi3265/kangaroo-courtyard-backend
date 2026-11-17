-- V35 回退：删除 kg_talent_mark.store_tags。
-- 门店已经加过标签时拒绝回退（会丢数据）；需要先停应用，并显式 set @kg_allow_talent_store_tags_rollback = 1。
drop procedure if exists kg_rollback_talent_store_tags;
delimiter $$
create procedure kg_rollback_talent_store_tags()
begin
    if coalesce(@kg_allow_talent_store_tags_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V35 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 35) then
        signal sqlstate '45000' set message_text = 'V35 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_talent_mark' and column_name = 'store_tags') then
        if exists (select 1 from kg_talent_mark where coalesce(store_tags, '') <> '') then
            signal sqlstate '45000' set message_text = 'V35 rollback blocked: store tags still exist';
        end if;
        alter table kg_talent_mark drop column store_tags;
    end if;
    delete from kg_schema_version where version = 35;
end$$
delimiter ;
call kg_rollback_talent_store_tags();
drop procedure kg_rollback_talent_store_tags;
