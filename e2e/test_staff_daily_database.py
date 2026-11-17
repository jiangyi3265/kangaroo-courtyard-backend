"""员工端「每日概况」真实 SQL 回归（2026-10-17）：在本机 MySQL 里建一个一次性库，只复制表结构、不复制任何业务数据，
跑 KgStaffDailyIT（入住 / 在住 / 离店边界、当天库存、入住率、报名计数、只算有授权的门店），跑完删库。

用法：python -X utf8 RuoYi-Vue/e2e/test_staff_daily_database.py
  KG_DAILY_SOURCE_DB 指定从哪个本机库复制表结构（默认验收库 dkxy_acceptance_20261004）；KG_MYSQL 指定 mysql.exe。
"""
from pathlib import Path
import os, re, subprocess, sys, uuid, json
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from dbcred import db_password
BE = ROOT / 'RuoYi-Vue'
database = 'kg_daily_test_' + uuid.uuid4().hex[:12]
assert re.fullmatch(r'kg_daily_test_[a-f0-9]{12}', database)
env = dict(os.environ, MYSQL_PWD=db_password(str(BE)), KG_DAILY_TEST_DB=database)
mysql = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
TABLES = ['kg_store', 'kg_stay', 'kg_stay_room_type', 'kg_stay_calendar', 'kg_stay_order', 'kg_job', 'kg_application']


def sql(query, use=None):
    cmd = [mysql, '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', '-N', '-B', '--raw']
    if use:
        cmd.append(use)
    r = subprocess.run(cmd, input=query, encoding='utf-8', capture_output=True, env=env)
    assert r.returncode == 0, r.stderr
    return r.stdout.strip()


created = False
result = {'database': database, 'checks': []}
try:
    source = os.environ.get('KG_DAILY_SOURCE_DB', 'dkxy_acceptance_20261004')
    assert re.fullmatch(r'[a-zA-Z0-9_]+', source)
    assert sql("select count(*) from information_schema.tables where table_schema='" + source + "' and table_name='kg_stay_order'") == '1', 'source schema missing: ' + source
    collation = sql("select default_collation_name from information_schema.schemata where schema_name='" + source + "'")
    assert re.fullmatch(r'[a-zA-Z0-9_]+', collation)
    sql('create database ' + database + ' character set utf8mb4 collate ' + collation)
    created = True
    # 只读表结构，不复制一行业务数据；外键 / 触发器这些表没有，复制 create table 即可
    for table in TABLES:
        ddl = sql('show create table `' + table + '`', source).split('\t', 1)[1]
        sql(ddl, database)
    result['checks'].append('copied DDL of ' + ', '.join(TABLES) + ' from ' + source)
    log = BE / 'ruoyi-kangaroo' / 'target' / 'staff-daily-it.log'
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w', encoding='utf-8') as out:
        r = subprocess.run(['mvn.cmd', '-o', '-q', '-pl', 'ruoyi-kangaroo', '-am', '-Dtest=KgStaffDailyIT,KgStaffDailyServiceTest',
                            '-Dsurefire.failIfNoSpecifiedTests=false', '-Duser.timezone=Asia/Shanghai', 'test'],
                           cwd=BE, env=env, stdout=out, stderr=subprocess.STDOUT)
    report = BE / 'ruoyi-kangaroo' / 'target' / 'surefire-reports' / 'TEST-com.ruoyi.kangaroo.ext.staff.KgStaffDailyIT.xml'
    head = report.read_text(encoding='utf-8')[:600] if report.exists() else ''
    m = re.search(r'tests="(\d+)" errors="(\d+)" skipped="(\d+)" failures="(\d+)"', head)
    assert r.returncode == 0 and m and m.group(1) != '0' and m.groups()[1:] == ('0', '0', '0'), 'KgStaffDailyIT failed or skipped; see ' + str(log)
    result['checks'].append('KgStaffDailyIT real SQL: ' + m.group(0))
finally:
    if created:
        sql('drop database ' + database)
        result['checks'].append('dropped ' + database)
print(json.dumps(result, ensure_ascii=False, indent=2))
