-- V27 回滚：按 kg_city_fix_v27 把门店、房源、活动的城市还原成归一前的写法。
-- 只还原"现在仍是 V27 写下的值"的行，之后被人改过的保持现状。停止应用后显式授权才允许执行。
drop procedure if exists kg_rollback_city_alias;
delimiter $$
create procedure kg_rollback_city_alias()
begin
    if database() <> 'dkxy_guardtest' and coalesce(@kg_allow_city_alias_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V27 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 27) then
        signal sqlstate '45000' set message_text = 'V27 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.tables where table_schema = database() and table_name = 'kg_city_fix_v27') then
        update kg_store s join kg_city_fix_v27 f on f.tbl = 'kg_store' and f.row_id = s.store_id and s.city = f.new_city set s.city = f.old_city;
        update kg_stay t join kg_city_fix_v27 f on f.tbl = 'kg_stay' and f.row_id = t.stay_id and t.city = f.new_city set t.city = f.old_city;
        update kg_activity t join kg_city_fix_v27 f on f.tbl = 'kg_activity' and f.row_id = t.activity_id and t.city = f.new_city set t.city = f.old_city;
        drop table kg_city_fix_v27;
    end if;
    drop table if exists kg_city_alias_v27;
    delete from kg_schema_version where version = 27;
end$$
delimiter ;
call kg_rollback_city_alias();
drop procedure kg_rollback_city_alias;
