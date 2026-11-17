# -*- coding: utf-8 -*-
"""迁移与重置脚本的破坏性守卫、迁移往返与迁移账本：每次验收都实跑一遍。

这两个脚本都能在一条命令里造成不可逆的数据丢失：
  · V1 每张表前面都有 drop table if exists（11 张），而它的头部注释、
    INTEGRATION.md、verify.py 的提示三处都把它描述成"不删除既有数据"的安全脚本；
  · reset-to-demo.sql 按白名单物理删除，而白名单是按演示门店名/openid 前缀查出来的 ——
    在一个没有演示数据的库上白名单为空，NOT IN 一个空集恒为真，它会删光整库。

所以两处都加了守卫。守卫这种东西不测就等于没有，而且只能测一次的"我刚才试过了"
不算证据 —— 这个文件就是为了让它每次验收都被重跑一遍。

V1 与迁移往返仅在 wrapper 创建的独立 MySQL3317 进程/数据目录，且在
显式验收库前缀、以 _guardtest 结尾的库中运行；缺少实例证明时退出2；
reset 两个分支仅在 KG_DB 指定的隔离验收库中运行，绝不写原 dkxy。

WP01 起追加（PLAN WP01；R3 B15、S13）：
  · 隔离库先导入若依基础表 sql/ry_20250522.sql 再执行 V1–V5：V6 起每个迁移都写
    sys_menu / sys_dict_* / sys_config / sys_role_menu，没有若依基础表就无从往返；
  · roundtrip(v)：对一个迁移做"前滚 → 重复前滚 → 回滚 → 再前滚"，每一步对整个隔离库拍快照
    （表、列、索引、存储过程、菜单、角色菜单、字典、参数、账本、各表行数）逐项比对，
    而不是只看退出码 —— "回滚执行成功"但少删了一列，只有逐项比对看得出来；
    资金类迁移带 probe：回滚前先插一行，断言未声明 @KG_ALLOW_DROP_LEDGER 时中止；
  · 往返哪些版本：内置 V3（本包修了它的回滚）与 V6（账本），加上 verify.d/scripts/C17/*.json
    里用 "roundtrip": [n, ...] 登记的版本（各包登记自己的迁移），以及环境变量 KG_ROUNDTRIP=7,8；
    其余 V6 起的迁移只按版本号顺序前滚一次（R3 B15："只往返本次改动过的迁移，其余只前滚"）；
    登记写错（文件解析失败、格式不对、版本号没有对应脚本）判红，不静默跳过（WP01 复核第 1 轮）；
  · 账本：隔离库跑完全部迁移后，kg_schema_version 必须逐版与脚本 checksum 一致、一版不缺；
  · V2 重跑：V6 起各迁移挂在 2000 段的菜单与 kg_ 字典（外加本脚本放进去的哨兵）条数不变；
  · 静态：V6 起每个迁移的头部/尾部与 sql/kangaroo/_snippets/header.sql 逐字一致、
    账本行里的 checksum 字面量等于正文的 sha256、每个迁移都有配套的 rollback。

checksum 口径（verify.py 的前置检查用同一口径，两处改动必须同步）：
  脚本文件字节，CRLF 统一成 LF 后取 sha256 小写十六进制；若文件里有以 "-- @kg-ledger" 开头的行，
  只算这一行之前的内容 —— V6 起每个迁移的最后一句是把自己的 checksum 写进账本，
  一个脚本不可能包含自己的哈希，所以账本行本身不计入。
"""
import glob
import hashlib
import io
import json
import os
import re
import subprocess
import sys

# 按本文件位置定位后端目录，项目目录改名后不会指向不存在的旧目录
BE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(BE)
MIG = os.path.join(BE, 'sql', 'kangaroo')
SNIPPET = os.path.join(MIG, '_snippets', 'header.sql')
RY_SQL = os.path.join(BE, 'sql', 'ry_20250522.sql')
sys.path.insert(0, os.path.join(BE, 'e2e'))
from dbcred import db_password  # noqa: E402
from migration_guard_policy import authorized_rollback_prefix, validate_guard_databases  # noqa: E402

MYSQL = os.environ.get('KG_MYSQL', r'D:/MySQL Server 8.0/bin/mysql.exe')
BASE_DB = os.environ.get('KG_DB', '')
TESTDB = os.environ.get('KG_GUARD_DB', BASE_DB + '_guardtest')
sys.path.insert(0, ROOT)
from acceptance.guard_mysql import EnvironmentBlocked, validate_child_instance, sql_target  # noqa: E402
try:
    # Must happen before fetching shared-server credentials or executing any DDL.
    GUARD_INSTANCE = validate_child_instance(mysql=MYSQL)
    validate_guard_databases(BASE_DB, TESTDB)
except (EnvironmentBlocked, ValueError) as exc:
    print('[环境阻塞] ' + str(exc))
    raise SystemExit(2)
PW = db_password(BE)

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding='utf-8', errors='replace')
    except (AttributeError, ValueError):
        pass

END_MARK = '__KG_END__'
LEDGER_MARK = b'\n-- @kg-ledger'

# V2 自己的 38 个菜单与 8 个字典类型（PLAN WP01 / 00 §3.1 的清单）。
# 重跑 V2 只许动这些；2000–2999 段里其余的菜单、其余的 kg_ 字典都属于 V6 起的迁移。
V2_MENU_IDS = ([2000] + list(range(2010, 2014)) + list(range(2020, 2027)) + list(range(2030, 2035))
               + list(range(2040, 2048)) + list(range(2050, 2056)) + list(range(2060, 2065))
               + list(range(2070, 2072)))
V2_DICT_TYPES = ('kg_job_type', 'kg_job_status', 'kg_salary_type', 'kg_board_type',
                 'kg_app_status', 'kg_staff_role', 'kg_msg_category', 'kg_slot_status')
ROUNDTRIP_BUILTIN = (3, 6)


def run_sql(sql, db=None, batch=False):
    # -n 与结束标记：见 test_kangaroo_flow._mysql 的说明 —— 这台机器上的 mysql.exe
    # 约 1/200 次会 exit 0 却不输出任何东西，"查到 0 张表"这类断言会因此假通过。
    # 密码走环境变量，不放命令行：命令行参数在进程列表里谁都看得见
    port, selected_password = sql_target(db, BASE_DB, PW, GUARD_INSTANCE)
    cmd = [MYSQL, '--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-P' + str(port), '-uroot', '--default-character-set=utf8mb4', '-n']
    if batch:
        cmd += ['-N', '-B']
    if db:
        cmd.append(db)
    script = sql.rstrip() + "\nselect '%s';\n" % END_MARK
    p = subprocess.run(cmd, input=script.encode('utf-8'), capture_output=True,
                       env=dict(os.environ, MYSQL_PWD=selected_password), timeout=120)
    lines = (p.stdout or b'').decode('utf-8', 'replace').replace('\r', '').split('\n')
    while lines and not lines[-1].strip():
        lines.pop()
    if p.returncode == 0:
        # 脚本中途出错时 mysql 会停下、非 0 退出，标记自然不会出现；只有 exit 0 才要求它
        if not lines or lines[-1].strip() != END_MARK:
            raise RuntimeError('mysql 输出丢失（exit 0 但没收到结束标记），这次结果不可信：'
                               + sql[:200])
        lines = lines[:-1]
        if lines and lines[-1].strip() == END_MARK:     # 非 -N 模式下还有一行列名
            lines = lines[:-1]
    out = '\n'.join(lines) + ('\n' if lines else '')
    err = '\n'.join(l for l in (p.stderr or b'').decode('utf-8', 'replace').splitlines()
                    if 'Using a password' not in l)
    return p.returncode, out, err


def rows(sql, db=None):
    """只读查询，返回行列表（-N -B，无表头）。查询本身失败直接抛：拿不到数据不能当成"0 行"。

    db 缺省是隔离库；传 '-' 表示不选库（只查 information_schema）。
    """
    rc, out, err = run_sql(sql, None if db == '-' else (db or TESTDB), batch=True)
    if rc != 0:
        raise RuntimeError('查询失败 exit=%d：%s  %s' % (rc, err.strip()[:300], sql[:200]))
    return [l for l in out.split('\n') if l != '']


def scalar(sql, db=None):
    r = rows(sql, db)
    return r[-1] if r else None


ok = 0
bad = []


def check(name, cond, detail=''):
    global ok
    if cond:
        ok += 1
        print('  [PASS] ' + name)
    else:
        bad.append(name)
        print('  [FAIL] %s  %s' % (name, str(detail)[:600]))


def read(name):
    return io.open(os.path.join(MIG, name), encoding='utf-8').read()


def script_checksum(path):
    """见文件头"checksum 口径"。"""
    with open(path, 'rb') as f:
        data = f.read().replace(b'\r\n', b'\n')
    i = data.find(LEDGER_MARK)
    if i >= 0:
        data = data[:i + 1]
    return hashlib.sha256(data).hexdigest()


def migration_scripts():
    """{版本: (名称, 路径)}。回滚脚本与只读报告（*-report.sql）不是迁移。"""
    out, dup = {}, []
    for p in glob.glob(os.path.join(MIG, 'V*__*.sql')):
        m = re.match(r'^V(\d+)__(.+)\.sql$', os.path.basename(p))
        if not m or m.group(2) == 'rollback' or m.group(2).endswith('-report'):
            continue
        v = int(m.group(1))
        if v in out:
            dup.append(v)
        out[v] = (m.group(2), p)
    return out, dup


def rollback_path(v):
    return os.path.join(MIG, 'V%d__rollback.sql' % v)


def snippet_block(kind):
    """header.sql 里 -- @kg-<kind>-begin 到 -- @kg-<kind>-end（含两行标记）的原文。"""
    if not os.path.exists(SNIPPET):
        return None
    text = io.open(SNIPPET, encoding='utf-8').read().replace('\r\n', '\n')
    m = re.search(r'(?ms)^-- @kg-%s-begin\n.*?^-- @kg-%s-end\n' % (kind, kind), text)
    return m.group(0) if m else None


# ---------------------------------------------------------------- 整库快照

SNAP_SQL = """
select concat('T|', table_name) from information_schema.tables where table_schema = database() order by 1;
select concat('C|', table_name, '.', column_name, '|', column_type, '|', is_nullable, '|',
              ifnull(column_default, '~'), '|', ifnull(generation_expression, ''), '|', extra)
  from information_schema.columns where table_schema = database() order by 1;
select concat('I|', table_name, '.', index_name, '|', non_unique, '|',
              group_concat(column_name order by seq_in_index))
  from information_schema.statistics where table_schema = database()
 group by table_name, index_name, non_unique order by 1;
select concat('R|', routine_type, '.', routine_name) from information_schema.routines
 where routine_schema = database() order by 1;
select concat('M|', menu_id, '|', menu_name, '|', ifnull(perms, ''), '|', parent_id, '|',
              menu_type, '|', visible, '|', status) from sys_menu order by menu_id;
select concat('RM|', role_id, '|', menu_id) from sys_role_menu order by role_id, menu_id;
select concat('DT|', dict_type, '|', dict_name, '|', status) from sys_dict_type order by dict_type;
select concat('DD|', dict_type, '|', dict_value, '|', dict_label, '|', dict_sort, '|', status)
  from sys_dict_data order by dict_type, dict_value;
select concat('CF|', config_key, '|', ifnull(config_value, '')) from sys_config order by config_key;
"""


def snapshot():
    """隔离库的逐项快照（排好序的字符串列表）。自增主键、时间戳这类每次都会变的值不纳入。"""
    base = rows(SNAP_SQL)
    tables = [x[2:] for x in base if x.startswith('T|')]
    extra = []
    if tables:
        extra = rows('\n'.join("select concat('N|%s|', count(*)) from `%s`;" % (t, t) for t in tables))
    if 'kg_schema_version' in tables:
        extra += rows("select concat('L|', version, '|', name, '|', checksum) "
                      "from kg_schema_version order by version;")
    return sorted(base + extra)


def snap_diff(a, b):
    sa, sb = set(a), set(b)
    gone = sorted(sa - sb)
    new = sorted(sb - sa)
    parts = []
    if gone:
        parts.append('少了 %d 项：%s' % (len(gone), '; '.join(gone[:8])))
    if new:
        parts.append('多了 %d 项：%s' % (len(new), '; '.join(new[:8])))
    return ' / '.join(parts) or '（集合相同，重复项数量不同）'


def ledger_line(v, name, path):
    return 'L|%d|%s|%s' % (v, name, script_checksum(path))


def guarded_rollback_rejection(tag, rb_sql, reason, data_sql=None):
    """Actual rejection must preserve structure, rows, ledger and probe values."""
    before = snapshot()
    content = rows(data_sql) if data_sql else None
    rc, _, err = run_sql(rb_sql, TESTDB)
    after = snapshot()
    business_before = [x for x in before if not x.startswith('R|')]
    business_after = [x for x in after if not x.startswith('R|')]
    content_after = rows(data_sql) if data_sql else None
    check(tag, rc != 0 and reason in err and business_before == business_after and content == content_after,
          'exit=%d %s %s' % (rc, err.strip()[:200], snap_diff(business_before, business_after)))
    # mysql stops after SIGNAL, leaving only this attempted script's new procedures.
    # Remove those test-owned routines; never mask a structural/data difference.
    for added in sorted(set(after) - set(before)):
        if added.startswith('R|PROCEDURE.'):
            routine = added[len('R|PROCEDURE.'):]
            if not re.fullmatch(r'kg_[A-Za-z0-9_]+', routine):
                raise RuntimeError('Unexpected test routine: ' + routine)
            rc, _, err = run_sql('drop procedure `%s`;' % routine, TESTDB)
            check(tag + '：清理本次守卫过程 ' + routine, rc == 0, err)
    check(tag + '：清理后全库快照不变', snapshot() == before, snap_diff(before, snapshot()))


def rollback_business_probes(v, rb_sql):
    """Exercise new rollback guards against actual isolated rows, then remove only probes."""
    probes = []
    if v in (28, 30):
        values = "25,null,null" if v == 28 else "null,'2026-10-04',1"
        number = 'guard-rollback-v%d' % v
        probes.append((
            'V%d：现有线上报名空值阻止回滚且原值不变' % v,
            "insert into kg_application(app_no,job_id,store_id,app_user_id,real_name,phone,age,arrival_date,stay_days) "
            "values('%s',999999,999999,999999,'回滚隔离探针','13800138000',%s);" % (number, values),
            "delete from kg_application where app_no='%s';" % number,
            "select app_no,age,arrival_date,stay_days from kg_application where app_no='%s';" % number,
            'V28 has applications without arrival/stay data' if v == 28 else 'V30 has applications without age'))
    if v == 29:
        probes += [
            ('V29：包括软删除的首页配置不能丢失',
             "insert into kg_home_pick(section,target_id,target_title,del_flag) values('job',999999,'回滚隔离探针','2');",
             "delete from kg_home_pick where target_title='回滚隔离探针';",
             "select section,target_id,target_title,del_flag from kg_home_pick;", 'V29 has homepage or member benefits'),
            ('V29：包括软删除的会员权益不能丢失',
             "insert into kg_member_benefit(merchant_name,title,city,category,discount_text,rules,del_flag) "
             "values('回滚隔离探针','回滚测试','平潭','coffee','9折','保留规则','2');",
             "delete from kg_member_benefit where merchant_name='回滚隔离探针';",
             "select merchant_name,title,discount_text,rules,del_flag from kg_member_benefit;", 'V29 has homepage or member benefits'),
            ('V29：编辑过的成长门槛不能丢失',
             "update kg_member_level set min_points=1 where level_id=1;",
             "update kg_member_level set min_points=0 where level_id=1;",
             "select level_id,level_name,min_points,description,del_flag from kg_member_level order by level_id;",
             'V29 member levels were edited'),
            ('V29：菜单空权限不能绕过身份检查',
             "update sys_menu set perms=null where menu_id=3901;",
             "update sys_menu set perms='kangaroo:homepick:query' where menu_id=3901;",
             "select menu_id,parent_id,perms from sys_menu where menu_id=3901;", 'V29 menu identities changed'),
            ('V29：互换已有子菜单权限仍应拒绝回滚',
             "update sys_menu set perms='kangaroo:homepick:add' where menu_id=3901;",
             "update sys_menu set perms='kangaroo:homepick:query' where menu_id=3901;",
             "select menu_id,parent_id,perms from sys_menu where menu_id=3901;", 'V29 menu identities changed'),
        ]
    if v == 31:
        probes.append(('V31：变更身份的菜单不能被误删',
                       "update sys_menu set perms='guard:custom:permission' where menu_id=2960;",
                       "update sys_menu set perms='kangaroo:application:list' where menu_id=2960;",
                       "select menu_id,parent_id,perms from sys_menu where menu_id in(2960,2970,2622,2632) order by menu_id;",
                       'V31 menu identities changed'))
        probes.append(('V31：菜单空权限不能绕过身份检查',
                       "update sys_menu set perms=null where menu_id=2960;",
                       "update sys_menu set perms='kangaroo:application:list' where menu_id=2960;",
                       "select menu_id,parent_id,perms from sys_menu where menu_id=2960;",
                       'V31 menu identities changed'))
    if v in (28, 29, 30, 31):
        original_ledger = rows("select name,date_format(applied_time,'%Y-%m-%d %H:%i:%s'),checksum "
                               f"from kg_schema_version where version={v};")[0].split('\t')
        # Only known, isolated migration metadata is interpolated; not user data.
        if (len(original_ledger) != 3 or not re.fullmatch(r'[a-z0-9_]+', original_ledger[0])
                or not re.fullmatch(r'[0-9 :\-]+', original_ledger[1])
                or not re.fullmatch(r'[0-9a-f]{64}', original_ledger[2])):
            raise RuntimeError('Unexpected isolated migration ledger metadata')
        probes.insert(0, ('V%d：无本版本账本不能操作同名对象' % v,
                         'delete from kg_schema_version where version=%d;' % v,
                         "insert into kg_schema_version(version,name,applied_time,checksum) values(%d,'%s','%s','%s');" % ((v,) + tuple(original_ledger)),
                         'select version,name,checksum from kg_schema_version where version=%d;' % v,
                         'V%d is not applied' % v))
        probes.insert(0, ('V%d：有后续迁移时回滚中止' % v,
                         "insert into kg_schema_version(version,name,applied_time,checksum) values(999999,'guard-rollback-probe',now(),repeat('0',64));",
                         "delete from kg_schema_version where version=999999 and name='guard-rollback-probe';",
                         "select version,name,checksum from kg_schema_version where version=999999;",
                         'V%d rollback blocked by later migrations' % v))
    for tag, insert, cleanup, data_sql, reason in probes:
        baseline = snapshot()
        rc, _, err = run_sql(insert, TESTDB)
        check(tag + '：准备隔离探针', rc == 0, err)
        guarded_rollback_rejection(tag, rb_sql, reason, data_sql)
        rc, _, err = run_sql(cleanup, TESTDB)
        check(tag + '：只清除本次探针', rc == 0 and snapshot() == baseline,
              err + snap_diff(baseline, snapshot()))


def roundtrip(v, fwd_sql, rb_sql, label=None, ledger=None, probe=None):
    """前滚 → 重复前滚 → 回滚 → 再前滚，每一步整库快照逐项比对。

    ledger: (name, path) —— V6 起的迁移要求前滚后账本里有自己这一行且 checksum 与脚本一致。
    probe: {'insert': sql, 'var': 'KG_ALLOW_DROP_LEDGER'} —— 资金类回滚：先插一行，
           不声明变量时必须中止且什么都没删，声明后才执行。
    """
    tag = label or 'V%d' % v
    s0 = snapshot()
    rc, out, err = run_sql(fwd_sql, TESTDB)
    check('%s 前滚执行成功' % tag, rc == 0, err)
    s1 = snapshot()
    check('%s 前滚确实改变了库（结构/菜单/字典/参数/账本/行数至少一项）' % tag, s1 != s0, '前滚前后快照相同')
    left = [x for x in set(s1) - set(s0) if x.startswith('R|')]
    check('%s 执行完不遗留自己建的存储过程' % tag, not left, left)
    if ledger:
        want = ledger_line(v, ledger[0], ledger[1])
        check('%s 前滚后账本里有自己这一行，checksum 与脚本一致' % tag, want in s1,
              [x for x in s1 if x.startswith('L|%d|' % v)] or '账本里没有 V%d' % v)
    rc, out, err = run_sql(fwd_sql, TESTDB)
    check('%s 重复前滚执行成功' % tag, rc == 0, err)
    s2 = snapshot()
    check('%s 重复前滚无副作用（与第一次前滚后逐项一致）' % tag, s2 == s1, snap_diff(s1, s2))

    authorization = authorized_rollback_prefix(v, BASE_DB, TESTDB)
    if authorization:
        guarded_rollback_rejection(tag + '：无显式授权时拒绝回滚且数据不变', rb_sql, 'explicit authorization')
        # The validated guard DB is created exclusively for this test and no app
        # points to it. Positive rollback explicitly opts into the existing contract.
        rb_sql = authorization + rb_sql
    rollback_business_probes(v, rb_sql)

    if probe:
        var = probe['var']
        rc, out, err = run_sql(probe['insert'], TESTDB)
        check('%s 回滚守卫探针：往资金表插入一行' % tag, rc == 0, err)
        s_probe = snapshot()
        rc, out, err = run_sql(rb_sql, TESTDB)
        s_after = [x for x in snapshot() if not x.startswith('R|')]
        check('%s 资金表非空且未声明 @%s 时回滚中止，且一样都没删' % (tag, var),
              rc != 0 and var in err and s_after == [x for x in s_probe if not x.startswith('R|')],
              'exit=%d %s %s' % (rc, err.strip()[:200], snap_diff(s_probe, s_after)))
        rb_sql = 'set @%s = 1;\n' % var + rb_sql

    rc, out, err = run_sql(rb_sql, TESTDB)
    check('%s 回滚执行成功' % tag, rc == 0, err)
    s3 = snapshot()
    check('%s 回滚后库状态与前滚前逐项一致' % tag, s3 == s0, snap_diff(s0, s3))
    rc, out, err = run_sql(fwd_sql, TESTDB)
    check('%s 回滚后可以重新前滚' % tag, rc == 0, err)
    s4 = snapshot()
    check('%s 再前滚后与第一次前滚后逐项一致' % tag, s4 == s1, snap_diff(s1, s4))


REG_DIR = os.path.join(ROOT, 'verify.d', 'scripts', 'C17')


def registered_roundtrips():
    """内置 + verify.d/scripts/C17/*.json 的 "roundtrip" + 环境变量 KG_ROUNDTRIP。

    返回 (版本集合, 问题清单, 登记文件数)。登记写错必须报出来、不能跳过（WP01 复核第 1 轮）：
    一个包的登记文件多了个逗号、或者 roundtrip 写成了字符串，它的迁移就一次往返都不做，
    而 C17 照样全绿 —— 这是失败开放。所以解析失败、顶层不是对象、roundtrip 不是整数列表、
    KG_ROUNDTRIP 里有不是整数的项，一律记进问题清单，由调用方判红。
    没有 "roundtrip" 键的对象不算登记（同一目录里可能放着 WP02 的检查注册文件），不报错。
    """
    vs, problems = set(ROUNDTRIP_BUILTIN), []
    files = sorted(glob.glob(os.path.join(REG_DIR, '*.json')))
    for f in files:
        name = os.path.basename(f)
        try:
            with io.open(f, encoding='utf-8-sig') as fh:   # 容忍 BOM：Windows 记事本会加
                data = json.load(fh)
        except ValueError as e:        # JSONDecodeError 与编码错误都是 ValueError 的子类
            problems.append('%s 解析失败：%s' % (name, e))
            continue
        if not isinstance(data, dict):
            problems.append('%s 顶层应为对象，实际是 %s' % (name, type(data).__name__))
            continue
        if 'roundtrip' not in data:
            continue
        lst = data['roundtrip']
        if not isinstance(lst, list):
            problems.append('%s 的 roundtrip 应为整数列表，实际是 %r' % (name, lst))
            continue
        for n in lst:
            # bool 是 int 的子类：true/false 不是版本号
            if isinstance(n, int) and not isinstance(n, bool):
                vs.add(n)
            else:
                problems.append('%s 的 roundtrip 里有非整数项 %r' % (name, n))
    for n in (os.environ.get('KG_ROUNDTRIP') or '').split(','):
        if not n.strip():
            continue
        if n.strip().isdigit():
            vs.add(int(n.strip()))
        else:
            problems.append('环境变量 KG_ROUNDTRIP 里有非整数项 %r' % n.strip())
    return vs, problems, len(files)


# ================================================================ 0. 隔离库

print('=== 0. 隔离库：库名自检，导入若依基础表 ===')
check('隔离库为验收前缀且以 _guardtest 结尾，与 KG_DB 不同（绝不在 dkxy 上往返）',
      validate_guard_databases(BASE_DB, TESTDB))

# 与本轮隔离应用库同字符集、同排序规则；不读取另一个项目/原库。
coll = scalar("select default_collation_name from information_schema.schemata "
              "where schema_name='%s';" % BASE_DB, db=BASE_DB) or 'utf8mb4_general_ci'
run_sql('drop database if exists `%s`; create database `%s` default charset utf8mb4 collate %s;'
        % (TESTDB, TESTDB, coll))
rc, out, err = run_sql(io.open(RY_SQL, encoding='utf-8').read(), TESTDB)
n_sys = scalar("select count(*) from information_schema.tables where table_schema=database() "
               "and table_name in ('sys_menu','sys_role_menu','sys_dict_type','sys_dict_data','sys_config');")
check('隔离库导入若依基础表 ry_20250522.sql 成功（菜单/角色菜单/字典/参数表都在）',
      rc == 0 and n_sys == '5', 'exit=%d tables=%s %s' % (rc, n_sys, err[:200]))

print('=== 1. V1 守卫：只有若依基础表、没有业务数据时应当正常建表 ===')
v1 = read('V1__kangaroo_core.sql')
rc, out, err = run_sql(v1, TESTDB)
check('空库上 V1 执行成功', rc == 0, err)
rc, out, _ = run_sql("select count(*) from information_schema.tables "
                     "where table_schema='%s' and table_name like 'kg\\_%%';" % TESTDB)
n = out.strip().splitlines()[-1] if out.strip() else '0'
check('建出了 11 张 kg_ 表', n == '11', 'got ' + n)

print('=== 2. V1 守卫：库里有数据时必须中止，且一张表都不能被删 ===')
run_sql("insert into kg_app_user (openid, nickname) values ('guard-test-openid', '守卫测试');", TESTDB)
rc, out, err = run_sql(v1, TESTDB)
check('有数据时 V1 被拒绝执行', rc != 0, 'exit=%d' % rc)
check('拒绝理由说清楚了原因', 'V1 是建库脚本' in err, err)
rc, out, _ = run_sql("select count(*) from kg_app_user where openid='guard-test-openid';", TESTDB)
left = out.strip().splitlines()[-1] if out.strip() else '?'
check('中止发生在任何 drop 之前（那行数据还在）', left == '1', 'got ' + left)
# 清掉这行测试数据：V4 要在干净的表上建唯一索引
run_sql("delete from kg_app_user where openid='guard-test-openid';", TESTDB)

print('=== 2.1 V2 前滚；V3 往返（回滚在 MySQL 8 上必须真能执行）===')
rc, out, err = run_sql(read('V2__menu_and_dict.sql'), TESTDB)
check('隔离库上 V2 执行成功', rc == 0, err)
roundtrip(3, read('V3__job_display_fields.sql'), read('V3__rollback.sql'))

print('=== 2.5 V4 可回滚、回滚后可重新前滚（G14）===')
# 这一条之前只在 2026-09-14 一次性实测过（记在 artifacts/migration/），
# 没进一键验收也没重跑 —— 一次性的"我试过了"不算可复核的证据。现在每次都跑。
V4_IDX = (('kg_application', 'uk_app_active_user_job'),
          ('kg_store', 'uk_store_name_city'),
          ('kg_interview_slot', 'uk_slot_store_range'))


def idx_count():
    total = 0
    for t, i in V4_IDX:
        rc_, out_, _ = run_sql("select count(*) from information_schema.statistics "
                               "where table_schema='%s' and table_name='%s' and index_name='%s';"
                               % (TESTDB, t, i))
        total += int((out_.strip().splitlines() or ['0'])[-1])
    return total


rc, out, err = run_sql(read('V4__uniqueness_and_counter_fix.sql'), TESTDB)
check('隔离库上 V4 执行成功', rc == 0, err)
check('V4 之后三条唯一索引都在', idx_count() == 3, 'got %d' % idx_count())

rc, out, err = run_sql(read('V4__rollback.sql'), TESTDB)
check('V4 回滚执行成功', rc == 0, err)
check('回滚之后三条唯一索引都没了', idx_count() == 0, 'got %d' % idx_count())
rc, out, _ = run_sql("select count(*) from information_schema.columns where table_schema='%s' "
                     "and column_name in ('active_uk','name_city_uk','slot_uk');" % TESTDB)
cols = (out.strip().splitlines() or ['?'])[-1]
check('回滚之后生成列也一并撤掉了', cols == '0', 'got ' + cols)

# 趁 V4 处于回滚状态，验 V6 的"存量执行痕迹"守卫：账本不能替没执行过的迁移作证
_v6 = [p for p in glob.glob(os.path.join(MIG, 'V6__*.sql')) if not p.endswith('__rollback.sql')]
if _v6:
    rc, out, err = run_sql(io.open(_v6[0], encoding='utf-8').read(), TESTDB)
    ledger_tbl = scalar("select count(*) from information_schema.tables where table_schema=database() "
                        "and table_name='kg_schema_version';")
    check('V6：存量迁移 V4 的执行痕迹不在时中止，且不建账本、不回填',
          rc != 0 and 'V4' in err and ledger_tbl == '0', 'exit=%d table=%s %s' % (rc, ledger_tbl, err[:200]))
    # 中止发生在脚本中途，头部建的公共过程与 V6 自己的检查过程还在：清掉，免得污染后面的快照比对
    run_sql((snippet_block('footer') or '') + 'drop procedure if exists kg_v6_assert_legacy;', TESTDB)
else:
    check('V6：存量迁移 V4 的执行痕迹不在时中止，且不建账本、不回填', False, '没有 V6 脚本')

rc, out, err = run_sql(read('V4__uniqueness_and_counter_fix.sql'), TESTDB)
check('回滚之后可以重新前滚（再跑一次 V4 成功）', rc == 0, err)
check('重新前滚之后三条唯一索引又都回来了', idx_count() == 3, 'got %d' % idx_count())

rc, out, err = run_sql(read('V5__hide_druid_menu.sql'), TESTDB)
check('隔离库上 V5 执行成功', rc == 0, err)

print('=== 2.6 V6 起：按版本号顺序前滚，登记过的版本做往返 ===')
scripts, dup = migration_scripts()
check('迁移版本号不重复', not dup, dup)
check('迁移 V6（迁移账本）存在', 6 in scripts, sorted(scripts))
missing_rb = [v for v in sorted(scripts) if not os.path.exists(rollback_path(v))]
check('每个迁移都有配套的 V{n}__rollback.sql', not missing_rb, missing_rb)
rts, reg_problems, n_reg = registered_roundtrips()
check('往返登记都可解析且格式正确（verify.d/scripts/C17/*.json %d 个：顶层为对象、roundtrip 为整数列表；'
      'KG_ROUNDTRIP 为逗号分隔的整数）' % n_reg, not reg_problems, reg_problems)
# 登记了却不会被往返的版本：没有对应迁移脚本（版本号写错），或者是 V6 之前的版本
# （V1–V5 不走登记：V3 在 2.1 节内置往返、V4 在 2.5 节往返，其余不往返）—— 都是静默忽略，必须判红
roundtrippable = {3} | {v for v in scripts if v >= 6}
unknown = sorted(v for v in rts if v not in roundtrippable)
check('登记往返的版本都有对应迁移脚本（V6 起；V3 为内置），不会被静默忽略', not unknown,
      '登记了但不会被往返：%s；可往返的版本：%s' % (unknown, sorted(roundtrippable)))
print('   往返版本：%s（其余 V6 起的迁移只前滚一次）' % sorted(v for v in rts if v >= 6))
for v in sorted(x for x in scripts if x >= 6):
    name, path = scripts[v]
    fwd = io.open(path, encoding='utf-8').read()
    if v in rts and os.path.exists(rollback_path(v)):
        roundtrip(v, fwd, io.open(rollback_path(v), encoding='utf-8').read(), ledger=(name, path))
    else:
        rc, out, err = run_sql(fwd, TESTDB)
        check('V%d 前滚执行成功' % v, rc == 0, err)

print('=== 2.6.1 V32 更正审计非空时拒绝回滚，并完整保留证据 ===')
if 32 in scripts:
    rc, _, err = run_sql("insert into kg_online_payout_correction (settle_id,app_id,store_id,amount_fen,operator_id,operator_name,new_method,new_voucher,correction_note,create_time) "
                        "values (1,1,1,12345,1,'隔离守卫测试','微信转账','/profile/local-test.png','仅隔离验证',now());", TESTDB)
    check('V32：插入隔离更正审计探针', rc == 0, err)
    before_guard = [x for x in snapshot() if not x.startswith('R|')]
    rc, _, err = run_sql(read('V32__rollback.sql'), TESTDB)
    after_guard = [x for x in snapshot() if not x.startswith('R|')]
    check('V32：非空审计拒绝回滚且业务结构/数据/账本均保留',
          rc != 0 and 'Payout correction audit exists' in err and before_guard == after_guard,
          'exit=%d %s %s' % (rc, err[:200], snap_diff(before_guard, after_guard)))
    run_sql((snippet_block('footer') or '') + 'drop procedure if exists kg_guard_payout_correction_rollback;', TESTDB)
    rc, _, err = run_sql("delete from kg_online_payout_correction where operator_name='隔离守卫测试' and correction_note='仅隔离验证';", TESTDB)
    check('V32：清除本次审计探针', rc == 0, err)

print('=== 2.7 迁移账本：逐版与脚本一致，一版不缺 ===')
has_ledger = scalar("select count(*) from information_schema.tables where table_schema=database() "
                    "and table_name='kg_schema_version';") == '1'
got = rows('select version, name, checksum from kg_schema_version order by version;') if has_ledger else []
got = {int(r.split('\t')[0]): (r.split('\t')[1], r.split('\t')[2]) for r in got}
check('账本回填了 V1–V5 五行', all(v in got for v in range(1, 6)), sorted(got))
want = {v: (name, script_checksum(path)) for v, (name, path) in scripts.items()}
wrong = ['V%d 库里=%s 脚本=%s' % (v, got.get(v), want[v]) for v in sorted(want) if got.get(v) != want[v]]
check('账本与脚本逐版一致（版本、名称、checksum；V1–V%d）' % max(want or [0]), not wrong and has_ledger,
      wrong or '没有账本表')
extra = sorted(set(got) - set(want))
check('账本里没有脚本目录里不存在的版本', not extra, extra)

print('=== 2.8 迁移头部标准片段的存储过程真的管用 ===')
head, foot = snippet_block('header'), snippet_block('footer')
check('_snippets/header.sql 有头部段与尾部段', bool(head and foot), SNIPPET)
if head and foot:
    ref = scalar("select table_collation from information_schema.tables where table_schema=database() "
                 "and table_name='kg_application';")
    other = 'utf8mb4_0900_ai_ci' if ref != 'utf8mb4_0900_ai_ci' else 'utf8mb4_general_ci'
    probe_sql = (head + "call kg_assert_env('1,2,3,4,5,6');\n"
                 "create table kg_guardtest_coll_same (id int);\n"
                 "call kg_assert_same_collation('kg_guardtest_coll_same');\n"
                 # 索引建在 k 上、删的列是 c：删列会连带删掉建在该列上的索引，
                 # 两者用同一列就分不清索引是被哪个过程删的（变异 M12 实测抓到过这个盲区）
                 "create table kg_guardtest_idx (id int, k int, c int, key idx_k (k));\n"
                 "call kg_drop_idx_if_present('kg_guardtest_idx', 'idx_k');\n"
                 "call kg_drop_idx_if_present('kg_guardtest_idx', 'idx_k');\n"
                 "call kg_drop_col_if_present('kg_guardtest_idx', 'c');\n"
                 "call kg_drop_col_if_present('kg_guardtest_idx', 'c');\n"
                 "call kg_add_col_if_absent('kg_guardtest_idx', 'd', 'd int default null');\n"
                 "call kg_add_col_if_absent('kg_guardtest_idx', 'd', 'd int default null');\n"
                 "call kg_add_idx_if_absent('kg_guardtest_idx', 'idx_d', 'key idx_d (d)');\n"
                 "call kg_add_idx_if_absent('kg_guardtest_idx', 'idx_d', 'key idx_d (d)');\n" + foot)
    rc, out, err = run_sql(probe_sql, TESTDB)
    left_cols = rows("select column_name from information_schema.columns where table_schema=database() "
                     "and table_name='kg_guardtest_idx' order by ordinal_position;")
    left_idx = rows("select distinct index_name from information_schema.statistics where table_schema=database() "
                    "and table_name='kg_guardtest_idx' order by index_name;")
    check('头部片段：前置齐全、排序规则一致时放行；加列/加索引/删列/删索引四个过程可重复执行且真的生效',
          rc == 0 and left_cols == ['id', 'k', 'd'] and left_idx == ['idx_d'],
          'exit=%d cols=%s idx=%s %s' % (rc, left_cols, left_idx, err[:200]))
    rc, out, err = run_sql(head + "call kg_assert_env('1,6,9998');\n" + foot, TESTDB)
    check('头部片段：前置迁移缺失时 kg_assert_env 中止并点名缺的版本',
          rc != 0 and 'V9998' in err, 'exit=%d %s' % (rc, err[:200]))
    rc, out, err = run_sql(head + "create table kg_guardtest_coll_diff (id int) collate %s;\n"
                           "call kg_assert_same_collation('kg_guardtest_coll_diff');\n" % other + foot,
                           TESTDB)
    check('头部片段：新表排序规则与 kg_application 不一致时 kg_assert_same_collation 中止',
          rc != 0 and 'kg_guardtest_coll_diff' in err, 'exit=%d %s' % (rc, err[:200]))
    run_sql('drop table if exists kg_guardtest_coll_same, kg_guardtest_coll_diff, kg_guardtest_idx;\n'
            + foot, TESTDB)

print('=== 2.9 V6 回滚守卫：账本里还有 V7 起的记录时拒绝回滚 ===')
if has_ledger and os.path.exists(rollback_path(6)):
    run_sql("insert into kg_schema_version (version, name, applied_time, checksum) "
            "values (9999, 'guardtest_future', now(), repeat('0', 64));", TESTDB)
    rc, out, err = run_sql(io.open(rollback_path(6), encoding='utf-8').read(), TESTDB)
    still = scalar("select count(*) from information_schema.tables where table_schema=database() "
                   "and table_name='kg_schema_version';")
    check('V6 回滚：账本里还有 V7 起的迁移时中止，账本表还在', rc != 0 and still == '1',
          'exit=%d table=%s %s' % (rc, still, err[:200]))
    run_sql("delete from kg_schema_version where version = 9999;\n"
            "drop procedure if exists kg_v6_rollback_guard;", TESTDB)
else:
    check('V6 回滚：账本里还有 V7 起的迁移时中止，账本表还在', False, '没有账本表或没有 V6 回滚脚本')

print('=== 2.10 静态：V6 起每个迁移的头部/尾部片段与账本行 ===')
drift_head, drift_foot, bad_tail = [], [], []
tail_re = re.compile(
    r"\A-- @kg-ledger[^\n]*\n(?:--[^\n]*\n)*"
    r"insert into kg_schema_version \(version, name, applied_time, checksum\)\n"
    r"values \((\d+), '([^']+)', now\(\), '([0-9a-f]{64})'\)\n"
    r"on duplicate key update name = '([^']+)', applied_time = now\(\), checksum = '([0-9a-f]{64})';\n*\Z")
for v in sorted(x for x in scripts if x >= 6):
    name, path = scripts[v]
    text = io.open(path, encoding='utf-8').read().replace('\r\n', '\n')
    if not head or head not in text:
        drift_head.append('V%d' % v)
    if not foot or foot not in text:
        drift_foot.append('V%d' % v)
    i = text.find('\n-- @kg-ledger')
    m = tail_re.match(text[i + 1:]) if i >= 0 else None
    cs = script_checksum(path)
    if not m or int(m.group(1)) != v or m.group(2) != name or m.group(4) != name \
            or m.group(3) != cs or m.group(5) != cs:
        bad_tail.append('V%d（正文 checksum=%s…）' % (v, cs[:12]))
check('V6 起每个迁移原样复制了 _snippets/header.sql 的头部段', not drift_head and 6 in scripts,
      drift_head or '没有 V6')
check('V6 起每个迁移原样复制了 _snippets/header.sql 的尾部段（清掉公共存储过程）',
      not drift_foot and 6 in scripts, drift_foot or '没有 V6')
check('V6 起每个迁移以账本行收尾，版本/名称与文件名一致、checksum 字面量等于正文 sha256',
      not bad_tail and 6 in scripts, bad_tail or '没有 V6')

print('=== 2.11 重跑 V2：V6 起的菜单、角色授权与 kg_ 字典一条都不能少 ===')
# 哨兵：模拟 V6 起的迁移挂在 2000 段的菜单（含挂在 V2 页面下的按钮）、它们的角色授权、自有 kg_ 字典。
# 用本仓库里规划好的真实编号（PLAN §4.3），不另造编号。
run_sql("""
delete from sys_menu where menu_id in (2048, 2056, 2100, 2110);
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2048, '岗位审核', 2040, 8, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:job:review', '#', 'guardtest', sysdate(), 'C17 哨兵'),
(2056, '标记入职', 2050, 6, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:application:onboard', '#', 'guardtest', sysdate(), 'C17 哨兵'),
(2100, '平台与合规', 2000, 10, 'platform', null, '', '', 1, 0, 'M', '0', '0', '', 'guide', 'guardtest', sysdate(), 'C17 哨兵'),
(2110, '运行与维护', 2100, 1, 'maint', 'kangaroo/maint/index', '', 'KgMaint', 1, 0, 'C', '0', '0', 'kangaroo:maint:list', 'monitor', 'guardtest', sysdate(), 'C17 哨兵');
insert ignore into sys_role_menu (role_id, menu_id) values (1, 2048), (1, 2110);
delete from sys_dict_data where dict_type = 'kg_guardtest_sentinel';
delete from sys_dict_type where dict_type = 'kg_guardtest_sentinel';
insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
values ('C17 哨兵字典', 'kg_guardtest_sentinel', '0', 'guardtest', sysdate(), '模拟 V6 起迁移自有的 kg_ 字典');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
                           status, create_by, create_time, remark) values
(1, '模拟通道', 'mock', 'kg_guardtest_sentinel', '', 'danger', 'N', '0', 'guardtest', sysdate(), ''),
(2, '真实通道', 'real', 'kg_guardtest_sentinel', '', 'success', 'N', '0', 'guardtest', sysdate(), '');
""", TESTDB)
own_ids = ','.join(str(i) for i in V2_MENU_IDS)
own_types = ','.join("'%s'" % t for t in V2_DICT_TYPES)
BEYOND = [
    ('菜单', "select count(*) from sys_menu where menu_id between 2000 and 2999 and menu_id not in (%s);" % own_ids),
    ('角色授权', "select count(*) from sys_role_menu where menu_id between 2000 and 2999 and menu_id not in (%s);" % own_ids),
    ('字典类型', "select count(*) from sys_dict_type where dict_type like 'kg\\_%%' and dict_type not in (%s);" % own_types),
    ('字典取值', "select count(*) from sys_dict_data where dict_type like 'kg\\_%%' and dict_type not in (%s);" % own_types),
]
before = {k: int(scalar(q)) for k, q in BEYOND}
rc, out, err = run_sql(read('V2__menu_and_dict.sql'), TESTDB)
check('重跑 V2 执行成功', rc == 0, err)
after = {k: int(scalar(q)) for k, q in BEYOND}
check('重跑 V2 后 V6 起的菜单与角色授权条数不变（且确有这类菜单）',
      before['菜单'] > 0 and before['菜单'] == after['菜单'] and before['角色授权'] == after['角色授权'],
      '%s -> %s' % (before, after))
check('重跑 V2 后 V6 起的 kg_ 字典类型与取值条数不变（且确有这类字典）',
      before['字典类型'] > 0 and before['字典类型'] == after['字典类型'] and before['字典取值'] == after['字典取值'],
      '%s -> %s' % (before, after))
own_menu = scalar("select count(*) from sys_menu where menu_id in (%s);" % own_ids)
own_dt = scalar("select count(*) from sys_dict_type where dict_type in (%s);" % own_types)
grp = scalar("select count(*) from sys_dict_data where dict_type='kg_msg_category' "
             "and dict_value='group' and dict_label='群聊';")
check('重跑 V2 后它自己的 38 个菜单、8 个字典类型齐全，kg_msg_category 含 group 群聊',
      own_menu == str(len(V2_MENU_IDS)) and own_dt == '8' and grp == '1',
      'menus=%s types=%s group=%s' % (own_menu, own_dt, grp))

print('=== 2.12 往返工具自检：资金类回滚守卫探针 ===')
# 用一对合成的迁移证明 roundtrip() 的 probe 分支真能抓住"资金表非空仍被回滚删掉"。
PROBE_FWD = ("create table if not exists kg_guardtest_ledger "
             "(id bigint not null auto_increment primary key, amount_fen bigint not null default 0) "
             "engine=innodb comment='C17 自检用的资金样表';")
PROBE_RB = """drop procedure if exists kg_guardtest_ledger_guard;
delimiter $$
create procedure kg_guardtest_ledger_guard()
begin
    declare n bigint default 0;
    if exists (select 1 from information_schema.tables
                where table_schema = database() and table_name = 'kg_guardtest_ledger') then
        select count(*) into n from kg_guardtest_ledger;
        if n > 0 and @KG_ALLOW_DROP_LEDGER is null then
            signal sqlstate '45000' set message_text =
                '[回滚中止] kg_guardtest_ledger 非空：资金类数据须先 set @KG_ALLOW_DROP_LEDGER=1';
        end if;
    end if;
end$$
delimiter ;
call kg_guardtest_ledger_guard();
drop procedure if exists kg_guardtest_ledger_guard;
drop table if exists kg_guardtest_ledger;"""
roundtrip(0, PROBE_FWD, PROBE_RB, label='自检·资金样表',
          probe={'insert': 'insert into kg_guardtest_ledger (amount_fen) values (100);',
                 'var': 'KG_ALLOW_DROP_LEDGER'})

run_sql('drop database if exists `%s`;' % TESTDB)
print('  （隔离库已删除）')

print('=== 3. reset 守卫：不给确认变量时必须中止，且不能删任何东西 ===')
rc, out, _ = run_sql("select count(*) from kg_store where del_flag='0';", BASE_DB)
before = out.strip().splitlines()[-1]
reset = io.open(os.path.join(BE, 'e2e', 'reset-to-demo.sql'), encoding='utf-8').read()
rc, out, err = run_sql(reset, BASE_DB)
check('不声明"非生产库"时被拒绝', rc != 0, 'exit=%d' % rc)
check('拒绝理由提到必须显式声明', 'I_KNOW_THIS_IS_NOT_PRODUCTION' in err, err)
rc, out, _ = run_sql("select count(*) from kg_store where del_flag='0';", BASE_DB)
after = out.strip().splitlines()[-1]
check('被拒绝时一条数据都没动', before == after, '%s -> %s' % (before, after))

print('=== 4. reset 正常路径：给了确认变量应当照常工作 ===')
rc, out, err = run_sql('set @I_KNOW_THIS_IS_NOT_PRODUCTION=1;\n' + reset, BASE_DB)
check('声明之后执行成功', rc == 0, err)
check('输出的是演示基线计数', '已重置到演示基线' in out, out[-200:])
print('   ' + (out.strip().splitlines()[-1] if out.strip() else ''))

print()
print('通过 %d / 共 %d' % (ok, ok + len(bad)))
if bad:
    print('失败：' + '; '.join(bad))
    raise SystemExit(1)
