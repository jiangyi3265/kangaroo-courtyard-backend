-- =============================================================================
-- V39__beta_feedback（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('38');

-- V39（2026-10-11）：内测反馈这一批。新增门店退款记录表；kg_app_user.dm_privacy、kg_post.tone 两列。不改已有数据。

-- =============================================================================
-- 门店主动退款（V39_staff_refund.sql）
-- =============================================================================
-- 门店主动退款（旅居订单台，2026-10-11）
-- 新增表：kg_stay_staff_refund（门店退款记录）
-- 无新列、无新索引（索引随新表一起建）；不改动任何已有数据，可重复执行。
--
-- 之前：旅居订单只能由客人按取消政策申请退款、门店审核；门店想多退 / 少退、或客人入住后补偿一部分钱，
--   只能找平台在后台对整张支付单退款。
-- 现在：本店主理人可以在员工端「旅居订单台 → 订单详情」里直接给客人退款，金额自己填（0.01 元到还没退的余额），
--   必须写原因。每一笔记一行：谁、什么时候、退了多少、为什么、微信是否已确认到账。
--   订单详情的记录、客人订单页的「门店已退 ¥xx」都从这里读；kg_stay_order.refund_fen 会被客人的退款申请 / 驳回改写，
--   不能用来累计门店退款。
--   kind：partial 部分退款或入住后退款（订单状态不变）/ full 未入住全额退款（订单取消、房量释放）/
--         approve 客人已申请退款，主理人按自己填的金额同意（订单取消）
--   status：processing 已提交、等微信确认到账 / success 已到账（联调支付即时到账）
--   idem_key：同一次提交的编号，连点两下、网络重试只退一次（同一订单内唯一，空值不参与）

create table if not exists kg_stay_staff_refund (
  refund_id         bigint        not null auto_increment comment 'ID',
  order_id          bigint        not null                comment '旅居订单',
  order_no          varchar(32)   not null                comment '订单号',
  store_id          bigint        not null                comment '门店',
  pay_no            varchar(32)   not null                comment '支付单号',
  amount_fen        bigint        not null                comment '这次退款金额（分）',
  reason            varchar(100)  not null                comment '退款原因（主理人填写，客人能看到）',
  kind              varchar(16)   not null                comment 'partial 部分退款 / full 全额退款并取消 / approve 按自定义金额同意退款申请',
  status            varchar(16)   not null default 'processing' comment 'processing 等微信确认 / success 已到账',
  idem_key          varchar(64)   null                    comment '提交编号（防重复提交）',
  operator_user_id  bigint        null                    comment '操作的主理人（小程序用户ID）',
  operator_name     varchar(64)   null                    comment '操作人昵称',
  create_time       datetime                              comment '提交时间',
  success_time      datetime      null                    comment '到账时间',
  primary key (refund_id),
  unique key uk_ssr_idem (order_id, idem_key),
  key idx_ssr_order (order_id, status),
  key idx_ssr_store (store_id, create_time)
) engine=innodb comment='门店主动退款记录（旅居订单）';
call kg_assert_same_collation('kg_stay_staff_refund');

-- =============================================================================
-- 社区：私信权限与伙伴说（V39_community.sql）
-- =============================================================================
-- =============================================================================
-- 社区内测建议（V39_community.sql，2026-10-11）
-- =============================================================================
-- 新增列：kg_app_user.dm_privacy  varchar(16) not null default 'all'  「谁可以私信我」：all 所有人 / following 我关注的人 / mutual 互相关注的人
-- 新增列：kg_post.tone            varchar(16) not null default ''     旅居日常的「伙伴说」：'' 不选 / recommend 推荐 / avoid 避雷
-- 无新表、无新索引（「已关注」信息流、我关注的人走 kg_user_follow 已有的 uk_follow / idx_followee；我收藏的帖子走 kg_favorite 已有的 idx_fav_user）
-- 不改动任何已有数据，可重复执行。
--
-- 之前谁都能私信任何人；现在每个人可以在「我的 → 账号与隐私设置 → 谁可以私信我」里选，默认「所有人」，老用户不受影响。
-- 之前发旅居日常没法表明是推荐还是避雷；现在可以选一个「伙伴说」标签（可不选），老帖子默认不选。
-- 后端要在本段执行之后再发布：新代码读写这两列。

call kg_add_col_if_absent('kg_app_user', 'dm_privacy', 'dm_privacy varchar(16) not null default ''all'' comment ''谁可以私信我：all 所有人 / following 我关注的人 / mutual 互相关注的人''');

call kg_add_col_if_absent('kg_post', 'tone', 'tone varchar(16) not null default '''' comment ''旅居日常的伙伴说：空 不选 / recommend 推荐 / avoid 避雷''');

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
values (39, 'beta_feedback', now(), '1414aacac45752fecf928eb10ed43dda8bceea3eac2f4b27700bf3815650d17a')
on duplicate key update name = 'beta_feedback', applied_time = now(), checksum = '1414aacac45752fecf928eb10ed43dda8bceea3eac2f4b27700bf3815650d17a';
