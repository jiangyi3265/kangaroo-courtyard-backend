-- Stop the application and take a backup before set @kg_allow_v31_rollback=1.
-- This version only adds review/export menu entries, not settlement data.
-- Parent 2600 and its role grants predate V31 and must remain. V31 added parent
-- grants without a provenance snapshot, so these cannot safely be inferred away.
-- If any target IDs existed before V31's upsert, use that backup to restore them.
drop procedure if exists kg_rollback_online_review_menus;
delimiter $$
create procedure kg_rollback_online_review_menus()
begin
    if coalesce(@kg_allow_v31_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V31 rollback requires stopped application and explicit authorization';
    end if;
    if not exists (select 1 from kg_schema_version where version = 31) then
        signal sqlstate '45000' set message_text = 'V31 is not applied; refuse rollback against unowned schema objects';
    end if;
    if exists (select 1 from kg_schema_version where version > 31) then
        signal sqlstate '45000' set message_text = 'V31 rollback blocked by later migrations';
    end if;
    if exists (select 1 from sys_menu where
        (menu_id = 2960 and (coalesce(parent_id, -1) <> 2600 or coalesce(path, '') <> 'onlinereview' or coalesce(component, '') <> 'kangaroo/ext/onlinereview/index' or coalesce(perms, '') <> 'kangaroo:application:list' or coalesce(menu_type, '') <> 'C'))
        or (menu_id = 2970 and (coalesce(parent_id, -1) <> 2600 or coalesce(path, '') <> 'volunteerreview' or coalesce(component, '') <> 'kangaroo/ext/volunteerreview/index' or coalesce(perms, '') <> 'kangaroo:application:list' or coalesce(menu_type, '') <> 'C'))
        or (menu_id = 2622 and (coalesce(parent_id, -1) <> 2620 or coalesce(perms, '') <> 'kangaroo:onlinesubmit:export' or coalesce(menu_type, '') <> 'F'))
        or (menu_id = 2632 and (coalesce(parent_id, -1) <> 2630 or coalesce(perms, '') <> 'kangaroo:onlinesettle:export' or coalesce(menu_type, '') <> 'F'))
    ) or exists (select 1 from sys_menu where parent_id in (2960,2970,2622,2632)) then
        signal sqlstate '45000' set message_text = 'V31 menu identities changed or have children; restore from reviewed backup';
    end if;
    delete from sys_role_menu where menu_id in (2960,2970,2622,2632);
    delete from sys_menu where menu_id in (2960,2970,2622,2632);
    delete from kg_schema_version where version = 31;
end$$
delimiter ;
call kg_rollback_online_review_menus();
drop procedure kg_rollback_online_review_menus;
