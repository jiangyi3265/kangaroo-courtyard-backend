-- =============================================================================
-- V100__online_task_agreement（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
-- =============================================================================
-- @kg-header-begin
-- ---- 迁移头部标准片段（原样复制自 sql/kangaroo/_snippets/header.sql，勿手改）----
-- 用 mysql 客户端跑脚本时会话时区是 SYSTEM；钉到 +08:00，now() 写进审计列才和应用写的对得上
set time_zone = '+08:00';

drop procedure if exists kg_assert_env;
drop procedure if exists kg_assert_same_collation;
drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_drop_col_if_present;
drop procedure if exists kg_drop_idx_if_present;

delimiter $$

-- 库默认字符集必须是 utf8mb4；p_requires 列出的前置迁移版本（逗号分隔）必须都已记在账本里
create procedure kg_assert_env(in p_requires varchar(255))
begin
    declare v_left varchar(255) default trim(ifnull(p_requires, ''));
    declare v_ver varchar(16);
    declare v_pos int;
    declare v_msg varchar(255);
    if @@character_set_database <> 'utf8mb4' then
        signal sqlstate '45000' set message_text = '[kg_assert_env] 库默认字符集不是 utf8mb4，已中止';
    end if;
    if v_left <> '' and not exists (select 1 from information_schema.tables
                                    where table_schema = database() and table_name = 'kg_schema_version') then
        signal sqlstate '45000' set message_text = '[kg_assert_env] 没有迁移账本 kg_schema_version（V6 未执行），已中止';
    end if;
    while v_left <> '' do
        set v_pos = locate(',', v_left);
        if v_pos = 0 then
            set v_ver = v_left;
            set v_left = '';
        else
            set v_ver = trim(substring(v_left, 1, v_pos - 1));
            set v_left = trim(substring(v_left, v_pos + 1));
        end if;
        if v_ver <> '' and not exists (select 1 from kg_schema_version where version = cast(v_ver as unsigned)) then
            set v_msg = concat('[kg_assert_env] 前置迁移 V', v_ver, ' 未执行（账本里没有），已中止');
            signal sqlstate '45000' set message_text = v_msg;
        end if;
    end while;
end$$

-- 新表的排序规则必须与 kg_application 相同，否则 join 时报 Illegal mix of collations
create procedure kg_assert_same_collation(in p_table varchar(64))
begin
    declare v_ref varchar(64);
    declare v_got varchar(64);
    declare v_msg varchar(255);
    set v_ref = (select table_collation from information_schema.tables
                  where table_schema = database() and table_name = 'kg_application');
    set v_got = (select table_collation from information_schema.tables
                  where table_schema = database() and table_name = p_table);
    if v_got is null or v_ref is null or v_got <> v_ref then
        -- SIGNAL 的 message_text 上限 128 个字符，这里控制在 100 以内
        set v_msg = left(concat('[kg_assert_same_collation] ', p_table, ' 排序规则 ', ifnull(v_got, '(无此表)'),
                                ' ≠ kg_application 的 ', ifnull(v_ref, '(无此表)')), 120);
        signal sqlstate '45000' set message_text = v_msg;
    end if;
end$$

create procedure kg_add_col_if_absent(in p_table varchar(64), in p_column varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.columns
                   where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @kg_s = concat('alter table ', p_table, ' add column ', p_ddl);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_add_idx_if_absent(in p_table varchar(64), in p_index varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.statistics
                   where table_schema = database() and table_name = p_table and index_name = p_index) then
        set @kg_s = concat('alter table ', p_table, ' add ', p_ddl);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_drop_col_if_present(in p_table varchar(64), in p_column varchar(64))
begin
    if exists (select 1 from information_schema.columns
               where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @kg_s = concat('alter table ', p_table, ' drop column ', p_column);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_drop_idx_if_present(in p_table varchar(64), in p_index varchar(64))
begin
    if exists (select 1 from information_schema.statistics
               where table_schema = database() and table_name = p_table and index_name = p_index) then
        set @kg_s = concat('alter table ', p_table, ' drop index ', p_index);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

delimiter ;
-- ---- 迁移头部标准片段结束 ----
-- @kg-header-end
call kg_assert_env('99');

-- Only newly published merchant tasks opt in; all historical rows remain fixed.
call kg_add_col_if_absent('kg_online_task','reward_mode',"reward_mode varchar(16) not null default 'fixed'");
call kg_add_col_if_absent('kg_online_task','items_per_person','items_per_person int null');
call kg_add_col_if_absent('kg_online_task','unit_reward_fen','unit_reward_fen bigint null');
call kg_add_col_if_absent('kg_online_submission','submitted_count','submitted_count int null');
call kg_add_col_if_absent('kg_online_settlement','qualified_count','qualified_count int null');
call kg_add_col_if_absent('kg_online_settlement','unit_reward_fen','unit_reward_fen bigint null');
insert into sys_notice(notice_title,notice_type,notice_content,status,create_by,create_time,remark) select convert(0xe7babfe4b88ae5b08fe7a88be5ba8fe5b9b3e58fb0e59586e5aeb6e58f91e5b883e58685e5aeb9e58d8fe8aeae using utf8mb4),'2',0x3c68323ee7babfe4b88ae5b08fe7a88be5ba8fe5b9b3e58fb0e59586e5aeb6e58f91e5b883e58685e5aeb9e58d8fe8aeae3c2f68323e0a3c703ee58d8fe8aeaee78988e69cacefbc9a56312e313c2f703e0a3c703ee7949fe69588e697a5e69c9fefbc9a32303236e5b9b43131e69c883133e697a53c2f703e0a3c703ee5b9b3e58fb0e8bf90e890a5e58f8ae7adbee7baa6e4b8bbe4bd93efbc9ae6b7b1e59cb3e5b882e8a28be9bca0e6989fe8beb0e68a95e8b584e69c89e99990e585ace58fb83c2f703e0a3c703ee98082e794a8e5afb9e8b1a1efbc9ae68980e69c89e59ca8e69cace5b9b3e58fb0e6b3a8e5868ce5b9b6e58f91e5b883e7babfe4b88ae4bbbbe58aa1e79a84e59586e5aeb63c2f703e0a3c703ee989b4e4ba8ee59586e5aeb6e99c80e4bdbfe794a8e69cace5b9b3e58fb0e5b08fe7a88be5ba8fe58f91e5b883e7babfe4b88ae4bbbbe58aa1efbc8ce4b8bae6988ee7a1aee58f91e5b883e8a784e88c83e38081e7bb93e7ae97e6a087e58786e58f8ae8bf9de8a784e8b4a3e4bbbbefbc8ce4be9de68daee3808ae7bd91e7bb9ce4bfa1e681afe58685e5aeb9e7949fe68081e6b2bbe79086e8a784e5ae9ae3808be7ad89e79bb8e585b3e6b395e5be8be6b395e8a784efbc8ce58f8ce696b9e8bebee68890e5a682e4b88be58d8fe8aeaeefbc9a3c2f703e0a3c68333ee7acace4b880e69da120e4bbbbe58aa1e7bb93e7ae97e6a087e587863c2f68333e0a3c703e312e3120e8aea1e8b4b9e696b9e5bc8fefbc9ae59586e5aeb6e58f91e5b883e79a84e4bbbbe58aa1efbc8ce5b9b3e58fb0e68896e4bbbbe58aa1e689bfe68ea5e696b9e4bb85e4bba5e2809ce59088e6a0bce5ae8ce68890e2809de79a84e695b0e9878fe4bd9ce4b8bae7bb93e7ae97e4be9de68daee380823c2f703e0a3c703e3c7374726f6e673e312e3220e58d95e4bbb7e7baa6e5ae9aefbc9ae7bb8fe9aa8ce694b6e59088e6a0bce79a84e4bbbbe58aa1efbc8ce68c89e6af8fe69da1203320e58583e79a84e6a087e58786e8bf9be8a18ce7bb93e7ae97e380823c2f7374726f6e673e3c2f703e0a3c68333ee7acace4ba8ce69da120e58f91e5b883e58685e5aeb9e7a681e6ada2e680a7e8a784e5ae9a3c2f68333e0a3c703ee59586e5aeb6e689bfe8afbae59ca8e58f91e5b883e4bbbbe58aa1e4bfa1e681afe58f8ae8a681e6b182e697b6efbc8ce7bb9de5afb9e4b88de5be97e58c85e590abe4bba5e4b88be58685e5aeb9efbc9a3c2f703e0a3c703e322e3120e6958fe6849fe8af8de6b187efbc9ae4b88de5be97e590abe69c89e2809ce588b7e58d95e2809de38081e2809ce585bce8818ce2809de38081e2809ce782b9e8b59ee59198e2809de38081e2809ce68ea8e5b9bfe8bf94e588a9e2809de38081e2809ce59eabe4bb98e2809de38081e2809ce68abce98791e2809de7ad89e585b7e69c89e6acbae8af88e38081e8afafe5afbce680a7e8b4a8e68896e8bf9de58f8de5b9b3e58fb0e9a38ee68ea7e8a784e58899e79a84e8af8de6b187efbc88e58c85e68bace4bd86e4b88de99990e4ba8ee4b88ae8bfb0e8af8de6b187e79a84e68bbce99fb3e38081e8b090e99fb3e38081e58f98e4bd93e58f8ae5a496e8afade7bfbbe8af91efbc89e380823c2f703e0a3c703e322e3220e8bf9de6b395e8bf9de8a784e58685e5aeb9efbc9ae4b8a5e7a681e58f91e5b883e6b689e58f8ae58db1e5aeb3e59bbde5aeb6e5ae89e585a8e38081e6b384e99cb2e59bbde5aeb6e7a798e5af86e38081e9a2a0e8a686e59bbde5aeb6e694bfe69d83e38081e7a0b4e59d8fe59bbde5aeb6e7bb9fe4b880e79a84e58685e5aeb9efbc9be4b8a5e7a681e58f91e5b883e6b689e58f8ae889b2e68385e38081e8b58ce58d9ae38081e69ab4e58a9be38081e587b6e69d80e38081e68190e68096e68896e69599e59486e78aafe7bdaae79a84e58685e5aeb9efbc9be4b8a5e7a681e58f91e5b883e4beaee8beb1e38081e8afbde8b0a4e4bb96e4babae68896e4beb5e5aeb3e4bb96e4babae99a90e7a781e38081e79fa5e8af86e4baa7e69d83e79a84e58685e5aeb9e380823c2f703e0a3c703e322e3320e4b88de889afe4bfa1e681afefbc9ae4b88de5be97e4bdbfe794a8e5a4b8e5bca0e6a087e9a298e68896e58685e5aeb9e4b88ee6a087e9a298e4b8a5e9878de4b88de7aca6e79a84e8a1a8e8bfb0efbc9be4b88de5be97e78292e4bd9ce7bbafe997bbe38081e4b891e997bbe38081e58aa3e8bfb9efbc9be4b88de5be97e5aea3e689ace4bd8ee4bf97e38081e5bab8e4bf97e38081e5aa9ae4bf97e58685e5aeb9e380823c2f703e0a3c703e322e3420e7a681e6ada2e68b89e8b8a9e585b6e4bb96e59586e5aeb6efbc9ae59586e5aeb6e59ca8e58f91e5b883e4bbbbe58aa1e4bfa1e681afe38081e4bbbbe58aa1e8a681e6b182e68896e4bbbbe58aa1e8afb4e6988ee697b6efbc8ce4b88de5be97e5afb9e585b6e4bb96e59586e5aeb6efbc88e58c85e68bace4bd86e4b88de99990e4ba8ee5908ce8a18ce4b89ae7bb8fe890a5e88085e38081e5b9b3e58fb0e58685e585b6e4bb96e585a5e9a9bbe59586e5aeb6efbc89e8bf9be8a18ce8b4ace4bd8ee38081e8af8be6af81e38081e681b6e6848fe6af94e8be83e38081e5bdb1e5b084e694bbe587bbe68896e585b6e4bb96e5bda2e5bc8fe79a84e68b89e8b8a9e8a18ce4b8baefbc9be4b88de5be97e4bba5e4b88de6ada3e5bd93e6898be6aeb5e68d9fe5aeb3e585b6e4bb96e59586e5aeb6e79a84e59586e4b89ae4bfa1e8aa89e38081e59586e59381e5a3b0e8aa89e68896e69c8de58aa1e8af84e4bbb7e380823c2f703e0a3c68333ee7acace4b889e69da120e59586e5aeb6e8b4a3e4bbbbe4b88ee5aea1e69fa53c2f68333e0a3c703e332e3120e4ba8be58588e5aea1e69fa5e4b989e58aa1efbc9ae59586e5aeb6e59ca8e782b9e587bbe2809ce58f91e5b883e2809de5898defbc8ce5ba94e887aae8a18ce5afb9e4bbbbe58aa1e68f8fe8bfb0e38081e6a087e9a298e38081e6a087e7adbee58f8ae9858de59bbee8bf9be8a18ce5aea1e6a0b8efbc8ce7a1aee4bf9de697a0e6958fe6849fe5ad97e38081e697a0e8bf9de8a784e58685e5aeb9efbc8ce4b894e4b88de5ad98e59ca8e68b89e8b8a9e585b6e4bb96e59586e5aeb6e79a84e8a1a8e8bfb0e380823c2f703e0a3c703e332e3220e58685e5aeb9e4bfaee694b9e69d83efbc9ae5b9b3e58fb0e58f91e78eb0e59586e5aeb6e58f91e5b883e58685e5aeb9e5ad98e59ca8e6958fe6849fe5ad97e38081e8bf9de8a784e5ab8ce79691e68896e68b89e8b8a9e585b6e4bb96e59586e5aeb6e68385e5bda2e79a84efbc8ce69c89e69d83e7ab8be58db3e4b88be69eb6e8afa5e4bbbbe58aa1e68896e9a9b3e59b9ee58f91e5b883e794b3e8afb7efbc8ce5b9b6e9809ae79fa5e59586e5aeb6e4bfaee694b9e38082e59586e5aeb6e4bfaee694b9e5b9b6e7bb8fe5b9b3e58fb0e5a48de6a0b8e9809ae8bf87e5908eefbc8ce696b9e58fafe9878de696b0e58f91e5b883e380823c2f703e0a3c703e3c7374726f6e673e332e3320e8b4a3e4bbbbe689bfe68b85efbc9ae59ba0e59586e5aeb6e58f91e5b883e58685e5aeb9e8bf9de58f8de69cace58d8fe8aeaee7acace4ba8ce69da1e7baa6e5ae9aefbc88e590abe68b89e8b8a9e585b6e4bb96e59586e5aeb6efbc89efbc8ce5afbce887b4e5b9b3e58fb0e981ade58f97e8a18ce694bfe5a484e7bd9ae38081e7acace4b889e696b9e7b4a2e8b594e68896e4baa7e7949fe585b6e4bb96e68d9fe5a4b1e79a84efbc88e58c85e68bace4bd86e4b88de99990e4ba8ee7bd9ae6acbee38081e8b594e581bfe98791e38081e5be8be5b888e8b4b9efbc89efbc8ce59586e5aeb6e5ba94e689bfe68b85e585a8e983a8e8b594e581bfe8b4a3e4bbbbefbc9be7bb99e585b6e4bb96e59586e5aeb6e980a0e68890e68d9fe5aeb3e79a84efbc8ce794b1e59586e5aeb6e887aae8a18ce689bfe68b85e79bb8e5ba94e6b395e5be8be8b4a3e4bbbbe380823c2f7374726f6e673e3c2f703e0a3c68333ee7acace59b9be69da120e8bf9de8a784e5a484e79086e4b88ee7bb93e7ae97e586bbe7bb933c2f68333e0a3c703e342e3120e8bf9de8a784e689a3e7bd9aefbc9ae88ba5e59586e5aeb6e58f91e5b883e79a84e4bfa1e681afe58c85e590abe6958fe6849fe5ad97e38081e8bf9de8a784e58685e5aeb9e68896e5ad98e59ca8e68b89e8b8a9e585b6e4bb96e59586e5aeb6e79a84e8a18ce4b8baefbc8ce5b9b3e58fb0e69c89e69d83e8a786e68385e88a82e4b8a5e9878de7a88be5baa6e98787e58f96e4bba5e4b88be68eaae696bdefbc9a3c2f703e0a3c703eefbc8831efbc89e58d95e6aca1e8bf9de8a784efbc9ae8ada6e5918ae5b9b6e5bcbae588b6e4b88be69eb6e8bf9de8a784e4bbbbe58aa1efbc9b3c2f703e0a3c703eefbc8832efbc89e7b4afe8aea1e8bf9de8a784e8bebe203220e6aca1efbc9ae69a82e5819ce59586e5aeb6e58f91e5b883e69d83e99990203320e5a4a9efbc9b3c2f703e0a3c703e3c7374726f6e673eefbc8833efbc89e7b4afe8aea1e8bf9de8a784e8bebe203320e6aca1e58f8ae4bba5e4b88ae68896e58f91e5b883e4b8a5e9878de8bf9de6b395e4bfa1e681afe38081e681b6e6848fe68b89e8b8a9e980a0e68890e681b6e58aa3e5bdb1e5938de79a84efbc9ae6b0b8e4b985e5b081e7a681e8b4a6e58fb7efbc8ce5b9b6e689a3e999a4e8b4a6e688b7e58685e69caae7bb93e7ae97e79a84e585a8e983a8e6acbee9a1b9efbc88e590abe5b7b2e59088e6a0bce5ae8ce68890e4bd86e5b09ae69caae694afe4bb98e79a84203320e585832fe69da1e8b4b9e794a8efbc89e380823c2f7374726f6e673e3c2f703e0a3c68333ee7acace4ba94e69da120e99984e588993c2f68333e0a3c703e352e3120e69cace58d8fe8aeaee887aae59586e5aeb6e59ca8e7babfe782b9e587bbe2809ce5908ce6848fe2809de68896e5ae9ee99985e58f91e5b883e4bbbbe58aa1e4b98be697a5e8b5b7e7949fe69588e380823c2f703e0a3c703e352e3220e5b9b3e58fb0e69c89e69d83e6a0b9e68daee6b395e5be8be6b395e8a784e58f8ae8bf90e890a5e99c80e8a681e4bfaee8aea2e69cace58d8fe8aeaeefbc8ce4bfaee8aea2e5908ee79a84e78988e69cace5b086e59ca8e5b08fe7a88be5ba8fe58685e585ace7a4baefbc8ce88ba5e59586e5aeb6e7bba7e7bbade4bdbfe794a8e58f91e5b883e58a9fe883bdefbc8ce8a786e4b8bae68ea5e58f97e4bfaee8aea2e5908ee79a84e58d8fe8aeaee380823c2f703e0a3c68333ee59586e5aeb6e7a1aee8aea4e6a08f3c2f68333e0a3c703ee69cace4baba2fe69cace58d95e4bd8de5b7b2e99885e8afbbe5b9b6e79086e8a7a3e4b88ae8bfb0e69da1e6acbeefbc8ce789b9e588abe698afe585b3e4ba8e33e585832fe69da1e7bb93e7ae97e6a087e58786e38081e6958fe6849fe5ad97e7a681e4bba4e58f8ae7a681e6ada2e68b89e8b8a9e585b6e4bb96e59586e5aeb6e79a84e8a784e5ae9aefbc8ce689bfe8afbae59088e8a784e58f91e5b883e4bbbbe58aa1e380823c2f703e0a3c703ee59586e5aeb6e5908de7a7b0efbc88e79b96e7aba02fe7adbee5ad97efbc89efbc9a5f5f5f5f5f5f5f5f5f5f5f5f5f5f3c2f703e0a3c703ee697a5e69c9fefbc9a32303236e5b9b45f5f5f5fe69c885f5f5f5fe697a53c2f703e0a3c703ee7babfe4b88ae7a1aee8aea4e8afb4e6988eefbc9ae58bbee98089e5908ce6848fe5908eefbc8ce5b9b3e58fb0e4bf9de5ad98e7a1aee8aea4e8b4a6e58fb7e38081e997a8e5ba97e5908de7a7b0e38081e7a1aee8aea4e697b6e997b4e38081e58d8fe8aeaee78988e69cace58f8ae585a8e69687e5bfabe785a7efbc8ce4bd9ce4b8bae69cace6aca1e7babfe4b88ae7a1aee8aea4e8aeb0e5bd95e380823c2f703e,'0','admin',now(),'online_task V1.1' where not exists(select 1 from sys_notice where cast(notice_title as binary)=0xe7babfe4b88ae5b08fe7a88be5ba8fe5b9b3e58fb0e59586e5aeb6e58f91e5b883e58685e5aeb9e58d8fe8aeae);

-- @kg-footer-begin
-- ---- 迁移尾部标准片段（原样复制自 sql/kangaroo/_snippets/header.sql，勿手改）----
drop procedure if exists kg_assert_env;
drop procedure if exists kg_assert_same_collation;
drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_drop_col_if_present;
drop procedure if exists kg_drop_idx_if_present;
-- ---- 迁移尾部标准片段结束 ----
-- @kg-footer-end
-- @kg-ledger（本行起不计入 checksum：checksum = 本行之前全部内容的 sha256）
insert into kg_schema_version (version, name, applied_time, checksum)
values (100, 'online_task_agreement', now(), '3585925e182d9a6d662f7a5f301f89c684c87e558672fb35afa17879c55a2a76')
on duplicate key update name = 'online_task_agreement', applied_time = now(), checksum = '3585925e182d9a6d662f7a5f301f89c684c87e558672fb35afa17879c55a2a76';
