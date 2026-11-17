-- Stop the application, back up the schema, then set @kg_allow_v29_rollback=1.
-- Only an unused V29 is reversible: keep all tables whenever real home/benefit
-- content or edited growth levels exist, including soft-deleted rows.
-- The old application can be restored while these additive tables are retained.
drop procedure if exists kg_rollback_home_membership;
delimiter $$
create procedure kg_rollback_home_membership()
begin
    if coalesce(@kg_allow_v29_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V29 rollback requires stopped application and explicit authorization';
    end if;
    if not exists (select 1 from kg_schema_version where version = 29) then
        signal sqlstate '45000' set message_text = 'V29 is not applied; refuse rollback against unowned schema objects';
    end if;
    if exists (select 1 from kg_schema_version where version > 29) then
        signal sqlstate '45000' set message_text = 'V29 rollback blocked by later migrations';
    end if;
    if exists (select 1 from kg_home_pick) or exists (select 1 from kg_member_benefit) then
        signal sqlstate '45000' set message_text = 'V29 has homepage or member benefits; retain configured business data';
    end if;
    if (select count(*) from kg_member_level) <> 6 or exists (
        select 1 from kg_member_level where level_id not between 1 and 6
        or level_name <> case level_id when 1 then '幼崽鼠' when 2 then '小袋鼠' when 3 then '袋鼠'
            when 4 then '大袋鼠' when 5 then '巨袋鼠' when 6 then '袋鼠王' end
        or min_points <> case level_id when 1 then 0 when 2 then 100 when 3 then 300
            when 4 then 800 when 5 then 2000 when 6 then 5000 end
        or description is null or description <> '' or coalesce(create_by, '') <> 'V29'
        or coalesce(update_by, '') <> '' or update_time is not null or coalesce(del_flag, '') <> '0'
    ) then
        signal sqlstate '45000' set message_text = 'V29 member levels were edited; retain configured growth levels';
    end if;
    if exists (select 1 from sys_menu where menu_id in (3900,3901,3902,3903,3904,3920,3921,3922,3940,3941,3942,3943,3944) and (
        coalesce(perms, '') <> case menu_id
            when 3900 then 'kangaroo:homepick:list' when 3901 then 'kangaroo:homepick:query'
            when 3902 then 'kangaroo:homepick:add' when 3903 then 'kangaroo:homepick:edit' when 3904 then 'kangaroo:homepick:remove'
            when 3920 then 'kangaroo:memberlevel:list' when 3921 then 'kangaroo:memberlevel:query' when 3922 then 'kangaroo:memberlevel:edit'
            when 3940 then 'kangaroo:memberbenefit:list' when 3941 then 'kangaroo:memberbenefit:query'
            when 3942 then 'kangaroo:memberbenefit:add' when 3943 then 'kangaroo:memberbenefit:edit' when 3944 then 'kangaroo:memberbenefit:remove' end
        or coalesce(parent_id, -1) <> case when menu_id in (3900,3920,3940) then 2000
            when menu_id between 3901 and 3904 then 3900 when menu_id between 3921 and 3922 then 3920 else 3940 end
        or coalesce(menu_type, '') <> case when menu_id in (3900,3920,3940) then 'C' else 'F' end
        or (menu_id = 3900 and (coalesce(path, '') <> 'homepick' or coalesce(component, '') <> 'kangaroo/ext/homepick/index'))
        or (menu_id = 3920 and (coalesce(path, '') <> 'memberlevel' or coalesce(component, '') <> 'kangaroo/ext/memberlevel/index'))
        or (menu_id = 3940 and (coalesce(path, '') <> 'memberbenefit' or coalesce(component, '') <> 'kangaroo/ext/memberbenefit/index'))
    )
    ) or exists (select 1 from sys_menu where parent_id in (3900,3901,3902,3903,3904,3920,3921,3922,3940,3941,3942,3943,3944)
        and menu_id not in (3900,3901,3902,3903,3904,3920,3921,3922,3940,3941,3942,3943,3944)) then
        signal sqlstate '45000' set message_text = 'V29 menu identities changed or have extra children; restore from reviewed backup';
    end if;
    delete from sys_role_menu where menu_id in (3900,3901,3902,3903,3904,3920,3921,3922,3940,3941,3942,3943,3944);
    delete from sys_menu where menu_id in (3900,3901,3902,3903,3904,3920,3921,3922,3940,3941,3942,3943,3944);
    drop table kg_home_pick, kg_member_benefit, kg_member_level;
    delete from kg_schema_version where version = 29;
end$$
delimiter ;
call kg_rollback_home_membership();
drop procedure kg_rollback_home_membership;
