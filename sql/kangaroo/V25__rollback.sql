-- V25 回滚：删掉 V25 补进来的城市 / 区县行（create_by = 'V25'），去掉 V25 给 kg_city 加的 4 列。
-- 运营原有的目的地行（V11 种子、后台新增）只是被 V25 补了行政区划代码、层级、所属城市和搜索别名，随列一起去掉，行本身保留。
-- 门店、房源、活动、岗位、帖子还在用 V25 补进来的城市名时拒绝回滚，免得这些数据的城市在字典里找不到。
-- 停止应用后显式授权才允许执行。
drop procedure if exists kg_rollback_national_cities;
delimiter $$
create procedure kg_rollback_national_cities()
begin
    if database() <> 'dkxy_guardtest' and coalesce(@kg_allow_national_cities_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V25 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 25) then
        signal sqlstate '45000' set message_text = 'V25 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_city' and column_name = 'adcode') then
        if exists (select 1 from kg_city c where c.create_by = 'V25'
                      and (exists (select 1 from kg_store s where s.city = c.name and s.del_flag = '0')
                        or exists (select 1 from kg_stay t where t.city = c.name and t.del_flag = '0')
                        or exists (select 1 from kg_activity a where a.city = c.name and a.del_flag = '0')
                        or exists (select 1 from kg_job j where j.city = c.name and j.del_flag = '0')
                        or exists (select 1 from kg_post p where p.city = c.name and p.del_flag = '0'))) then
            signal sqlstate '45000' set message_text = 'V25 rollback blocked: cities added by V25 are used by business data';
        end if;
        delete from kg_city where create_by = 'V25';
        alter table kg_city drop column adcode, drop column parent_name, drop column level, drop column search_aliases;
    end if;
    delete from kg_schema_version where version = 25;
end$$
delimiter ;
call kg_rollback_national_cities();
drop procedure kg_rollback_national_cities;
