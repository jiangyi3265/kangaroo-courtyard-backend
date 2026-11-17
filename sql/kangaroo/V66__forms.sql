-- =============================================================================
-- V66__forms（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('60,62,63,64,65');

-- V66：包 20261031-forms（2026-10-31 主人反馈）。依赖写 '60,62,63,64,65'：生产账本没有 V61，这里不依赖 kg_store.images。
-- 不依赖角色阶段：legacy 和 new 下行为相同。kg_job 只加可空列，线上 4bd5 jar 的查询、插入都不写这几列；报销是新表，老 jar 不认识。退回老 jar 不用回滚这个迁移。

-- 一、招募表单新字段（kg_job），取值和收口规则见 com.ruoyi.kangaroo.domain.JobExtras：
-- 老版本小程序提交时不带这些键，服务端保持原值；读的时候再按岗位类型和食宿收口，所以这里不做任何回填。
call kg_add_col_if_absent('kg_job', 'meal_allowance', 'meal_allowance varchar(12) null default null comment ''餐补金额（元），空=没填；只有不包吃（room / none）的岗位显示''');
call kg_add_col_if_absent('kg_job', 'meal_allowance_unit', 'meal_allowance_unit varchar(8) null default null comment ''餐补单位：day 元/天 / month 元/月''');
call kg_add_col_if_absent('kg_job', 'pet_status', 'pet_status varchar(8) null default null comment ''店里有没有宠物，只给义工：空=没填 / none 没有 / yes 有''');
call kg_add_col_if_absent('kg_job', 'pet_images', 'pet_images varchar(1000) null default null comment ''宠物照片 JSON 数组（平台上传路径），最多 6 张，有宠物才有''');
call kg_add_col_if_absent('kg_job', 'lodging_images', 'lodging_images varchar(2000) null default null comment ''住宿照片 JSON 数组（平台上传路径），最多 9 张，包住才有''');
call kg_add_col_if_absent('kg_job', 'lodging_video', 'lodging_video varchar(255) null default null comment ''住宿视频（平台视频路径），1 段，3 分钟、100MB，包住才有''');
call kg_add_col_if_absent('kg_job', 'lodging_video_cover', 'lodging_video_cover varchar(255) null default null comment ''住宿视频封面（平台图片路径）''');

-- 二、「云连锁」改名「抖音卖房」（内部编码 cloud_chain 不变）：V63 里写死的字典备注、菜单备注不能改脚本，这里改数据；
-- 已存的需求标题（默认是「云连锁 · 店名」，小程序 requestMeta 靠它和类型名对得上才认）和已发的站内信一起改。
-- 不改：商家自己写的需求说明（kg_merchant_request.content）、老操作日志（kg_staff_op_log，历史记录）、V63 的表注释（后台不显示）。
update sys_dict_data set remark = replace(remark, '云连锁', '抖音卖房')
 where dict_type = 'kg_staff_role' and dict_value = 'merchant_service' and remark like '%云连锁%';
update sys_menu set remark = replace(remark, '云连锁', '抖音卖房')
 where perms = 'kangaroo:merchantrequest:list' and remark like '%云连锁%';
-- 「云连锁」3 个字换 4 个字，列宽 64 / 128 / 500，要 left；update_time 不动，这不是商家的修改
update kg_merchant_request set title = left(replace(title, '云连锁', '抖音卖房'), 64)
 where request_type = 'cloud_chain' and title like '%云连锁%';
update kg_message set title = left(replace(title, '云连锁', '抖音卖房'), 128), content = left(replace(content, '云连锁', '抖音卖房'), 500)
 where biz_type = 'merchant_request' and (title like '%云连锁%' or content like '%云连锁%');

-- 三、采购报销（R12，2026-10-31 主人拍板）：直营小院的小院主理人在小程序提交，主人在后台表格里通过、驳回、标记已打款。系统只记账不打款，没提交进表的不报销。
-- 规则在 com.ruoyi.kangaroo.ext.stay.KgReimburseService。金额按主人这张表的用法存 decimal（元），和别的表的「分」不一样，读的时候要注意。
-- 新表 not null 没关系：线上 4bd5 jar 不读不写它，退回老 jar 不用回滚。
create table if not exists kg_reimbursement (
  reimburse_id    bigint        not null auto_increment  comment '报销ID',
  store_id        bigint        not null                 comment '门店（kg_store.store_id），第一次提交时定，不能改',
  store_name      varchar(64)   not null default ''      comment '提交时的门店名字（门店改名后表格还对得上当时的凭证）',
  app_user_id     bigint        not null                 comment '提交人（kg_app_user.user_id）',
  submitter_name  varchar(64)   not null default ''      comment '提交时的昵称；注销后是「已注销用户」',
  item_name       varchar(60)   not null                 comment '品名，1–60 字',
  quantity        int           not null                 comment '数量，正整数',
  amount          decimal(10,2) not null                 comment '金额（元，这一笔的总价）：大于 0，不超过 100000，最多两位小数',
  purchase_date   date          not null                 comment '购买日期，不晚于提交当天',
  receipts        varchar(2000) not null                 comment '凭证照片 JSON 数组（平台上传路径），1–9 张',
  remark          varchar(200)  not null default ''      comment '备注，选填',
  status          varchar(16)   not null default 'pending' comment 'pending 待审核 / approved 已通过 / rejected 已驳回 / paid 已打款（只记账，系统不打款）',
  revision        int           not null default 1       comment '第几次提交：驳回后再交加 1。后台带着它审，防止审到改过的内容',
  reject_reason   varchar(255)  null                     comment '驳回理由（再交时清空）',
  audit_by        varchar(64)   null                     comment '审核人（后台账号）',
  audit_time      datetime      null                     comment '审核时间',
  paid_by         varchar(64)   null                     comment '标记已打款的后台账号',
  paid_time       datetime      null                     comment '标记已打款的时间',
  submit_time     datetime      not null                 comment '最近一次提交时间（驳回后再交会更新），每人每小时条数按它数',
  anonymous       char(1)       not null default '0'     comment '1 = 提交人已注销',
  del_flag        char(1)       not null default '0'     comment '0 正常 / 2 已删除（提交人撤回待审的）',
  create_by       varchar(64)   not null default ''      comment '创建者（app:用户ID）',
  create_time     datetime      not null                 comment '第一次提交时间',
  update_by       varchar(64)   not null default ''      comment '更新者',
  update_time     datetime      null                     comment '更新时间',
  primary key (reimburse_id),
  key idx_reimburse_store (store_id, del_flag, reimburse_id),
  key idx_reimburse_audit (status, del_flag, reimburse_id),
  key idx_reimburse_user_time (app_user_id, submit_time)
) engine=innodb comment='采购报销（小院主理人提交，平台审核，只记账不打款）';
call kg_assert_same_collation('kg_reimbursement');

-- 菜单：放在「旅居」目录，和 Vlog 审核、小院评论审核同一个上级。优先用固定编号，被占时退给自增（和 V53、V64、V65 同一写法）。只属于角色 1。
drop procedure if exists kg_v66_reimburse_menu;
delimiter $$
create procedure kg_v66_reimburse_menu()
begin
    declare v_parent bigint default null;
    declare v_menu bigint default null;
    set v_parent = (select m.parent_id from sys_menu m where m.perms = 'kangaroo:stayvlog:list' order by m.menu_id limit 1);
    if v_parent is null then
        signal sqlstate '45000' set message_text = '[V66] 找不到「Vlog 审核」菜单的上级目录，已中止';
    end if;
    if not exists (select 1 from sys_menu m where m.perms = 'kangaroo:reimburse:list') then
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 2288) then null else 2288 end,
               '采购报销', v_parent, 11, 'reimburse', 'kangaroo/reimburse/index', '', 'KgReimburse', 1, 0, 'C', '0', '0', 'kangaroo:reimburse:list', 'money', 'admin', now(),
               '直营小院的采购报销：看表格、导出、通过、驳回、标记已打款。系统只记账不打款。默认只给超级管理员';
    end if;
    set v_menu = (select m.menu_id from sys_menu m where m.perms = 'kangaroo:reimburse:list' order by m.menu_id limit 1);
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 2289) then null else 2289 end,
           '报销审核', v_menu, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:reimburse:audit', '#', 'admin', now(), '通过、驳回、标记已打款'
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:reimburse:audit');
end$$
delimiter ;
call kg_v66_reimburse_menu();
drop procedure kg_v66_reimburse_menu;

insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:reimburse:%';

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
values (66, 'forms', now(), '2369c84d0427dfbb605bee51af4fe1ece1a31d4f523d81fd28a300601e9f589f')
on duplicate key update name = 'forms', applied_time = now(), checksum = '2369c84d0427dfbb605bee51af4fe1ece1a31d4f523d81fd28a300601e9f589f';
