-- 谁是卧底词库与后台菜单。重复执行保留管理员已编辑的词条。
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
call kg_assert_env('88');

create table if not exists kg_undercover_word (
 word_id bigint not null auto_increment,
 category varchar(16) not null,
 first_word varchar(24) not null,
 second_word varchar(24) not null,
 pair_key varchar(64) not null,
 enabled tinyint not null default 1,
 version int not null default 0,
 update_by varchar(64) not null default 'migration:V89',
 update_time datetime not null default current_timestamp,
 primary key(word_id),
 unique key uk_undercover_pair(category,pair_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (1,'travel','民宿','酒店','民宿|酒店');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (2,'travel','露营','野餐','野餐|露营');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (3,'travel','高铁','飞机','飞机|高铁');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (4,'travel','沙滩','沙漠','沙滩|沙漠');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (5,'travel','日出','日落','日出|日落');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (6,'travel','背包','行李箱','背包|行李箱');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (7,'travel','登山','徒步','徒步|登山');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (8,'travel','温泉','泳池','泳池|温泉');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (9,'travel','地图','导航','地图|导航');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (10,'travel','帐篷','房车','帐篷|房车');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (11,'travel','古镇','古城','古城|古镇');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (12,'travel','缆车','摩天轮','摩天轮|缆车');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (13,'travel','瀑布','喷泉','喷泉|瀑布');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (14,'travel','护照','签证','护照|签证');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (15,'travel','导游','领队','导游|领队');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (16,'daily','豆浆','牛奶','牛奶|豆浆');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (17,'daily','饺子','馄饨','饺子|馄饨');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (18,'daily','咖啡','奶茶','咖啡|奶茶');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (19,'daily','火锅','麻辣烫','火锅|麻辣烫');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (20,'daily','西瓜','哈密瓜','哈密瓜|西瓜');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (21,'daily','包子','馒头','包子|馒头');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (22,'daily','蛋糕','面包','蛋糕|面包');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (23,'daily','耳机','音箱','耳机|音箱');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (24,'daily','雨伞','雨衣','雨伞|雨衣');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (25,'daily','围巾','披肩','围巾|披肩');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (26,'daily','冰箱','空调','冰箱|空调');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (27,'daily','钢琴','电子琴','电子琴|钢琴');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (28,'daily','电影','电视剧','电影|电视剧');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (29,'daily','篮球','足球','篮球|足球');
insert ignore into kg_undercover_word(word_id,category,first_word,second_word,pair_key) values (30,'daily','书店','图书馆','书店|图书馆');

insert into sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '谁是卧底管理',coalesce((select menu_id from (select menu_id from sys_menu where path='kangaroo' limit 1) m),0),89,'undercover','kangaroo/undercover/index',1,1,'C','0','0','kangaroo:undercover:list','peoples','admin',now()
where not exists(select 1 from sys_menu where perms='kangaroo:undercover:list');
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '维护卧底词库',menu_id,1,'',1,0,'F','0','0','kangaroo:undercover:edit','#','admin',now() from sys_menu
where perms='kangaroo:undercover:list' and not exists(select 1 from (select perms from sys_menu) x where x.perms='kangaroo:undercover:edit');
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '解散卧底房间',menu_id,2,'',1,0,'F','0','0','kangaroo:undercover:close','#','admin',now() from sys_menu
where perms='kangaroo:undercover:list' and not exists(select 1 from (select perms from sys_menu) x where x.perms='kangaroo:undercover:close');

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
insert into kg_schema_version(version,name,applied_time,checksum)
values(89,'undercover_admin',now(),'0c92b7ddb80684b8a87c5f90f4e9f1db5b4939267496fe89bfff2dc2505ae123')
on duplicate key update name='undercover_admin',applied_time=now(),checksum='0c92b7ddb80684b8a87c5f90f4e9f1db5b4939267496fe89bfff2dc2505ae123';
