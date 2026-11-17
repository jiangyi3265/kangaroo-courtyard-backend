"""V27 city alias normalization and V25/V27 rollbacks in isolated, disposable local MySQL databases; no live business writes."""
from pathlib import Path
import os, re, subprocess, sys, uuid, json
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from dbcred import db_password
BE = ROOT / 'RuoYi-Vue'
MIG = BE / 'sql/kangaroo'
OUT = Path(os.environ.get('KG_CITY_ALIAS_OUT', ROOT / 'artifacts/flow-fixes-20261002'))
env = dict(os.environ, MYSQL_PWD=db_password(str(BE)))
mysql = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
SOURCE = os.environ.get('KG_OPS_SOURCE_DB', 'dkxy')


def sql(query, use=None, error=None):
    cmd = [mysql, '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', '-N', '-B', '--raw']
    if use:
        cmd.append(use)
    r = subprocess.run(cmd, input=query, encoding='utf-8', capture_output=True, env=env)
    if error:
        assert r.returncode and error in r.stderr, r.stderr
    else:
        assert r.returncode == 0, r.stderr
    return r.stdout.strip()


def fresh(database):
    """Only table definitions are copied from the local source schema; business rows are never copied."""
    made.append(database)
    assert re.fullmatch(r'kg_alias_test_[a-f0-9]{12}', database)
    collation = sql("select default_collation_name from information_schema.schemata where schema_name='" + SOURCE + "'")
    assert re.fullmatch(r'[a-zA-Z0-9_]+', collation)
    sql('create database ' + database + ' character set utf8mb4 collate ' + collation)
    tables = sql("select table_name from information_schema.tables where table_schema='" + SOURCE + "' and (table_name like 'kg_%' or table_name in ('sys_menu','sys_role_menu'))").splitlines()
    for table in tables:
        assert re.fullmatch(r'[a-zA-Z0-9_]+', table)
        ddl = sql('show create table `' + table + '`', SOURCE)
        assert '\t' in ddl, 'unexpected show create table output for %s: %r' % (table, ddl[:300])
        sql(ddl.split('\t', 1)[1], database)
    sql("delete from kg_schema_version; insert into kg_schema_version(version,name,applied_time,checksum) values(24,'fixture',now(),'fixture');"
        "insert into sys_menu(menu_id,menu_name,parent_id,path,menu_type,perms) values(900001,'用户服务',0,'kgusersvc','M',''),(900002,'社区活动',0,'activity','C','kangaroo:activity:list');", database)


def migrate(database, *versions):
    for v in versions:
        path = next(MIG.glob('V%d__*.sql' % v))
        assert 'rollback' not in path.name
        sql(path.read_text(encoding='utf-8'), database)


checks, made = [], []
try:
    # ---------------------------------------------------------------- V27 前滚 / 重跑 / 回滚
    db = 'kg_alias_test_' + uuid.uuid4().hex[:12]; fresh(db)
    migrate(db, 25, 26)
    sql("""insert into kg_store(store_id,store_name,city,status,del_flag) values
          (102,'平潭袋鼠小院','平潭岛','0','0'),(103,'袋鼠小院','平潭','0','0'),(105,'鼓浪屿小院','厦门市','0','0'),
          (106,'思明小院','思明区','0','0'),(107,'海边小院','海岛营地','0','0'),
          (108,'同名小院','平潭','0','0'),(109,'同名小院','平潭岛','0','0');
          insert into kg_stay(stay_id,store_id,stay_name,city) values (4,102,'女生多人间','平潭岛'),(6,103,'海景房','平潭');
          insert into kg_activity(activity_id,store_id,source,title,start_time,city) values (1,102,'yard','海边徒步',now(),'平潭岛');""", db)
    migrate(db, 27)
    got = lambda q: sql(q, db)
    assert got("select city from kg_store where store_id=102") == '平潭', 'alias 平潭岛 -> 平潭'
    assert got("select city from kg_store where store_id=105") == '厦门', 'full name 厦门市 -> 厦门'
    assert got("select city from kg_store where store_id=106") == '厦门', 'V25 district 思明区 -> 厦门'
    assert got("select city from kg_store where store_id=107") == '海岛营地', 'unknown names untouched'
    assert got("select city from kg_store where store_id=109") == '平潭岛', 'same-name store in target city must not be renamed (unique key)'
    assert got("select city from kg_stay where stay_id=4") == '平潭' and got("select city from kg_activity where activity_id=1") == '平潭'
    assert got("select count(*) from information_schema.tables where table_schema=database() and table_name='kg_city_alias_v27'") == '0'
    assert got("select count(*) from kg_schema_version where version=27") == '1'
    checks.append('V27 maps alias/full name/district to dictionary city, skips unique-key conflicts, leaves unknown names')
    before_rows = got("select tbl,row_id,old_city,new_city from kg_city_fix_v27 order by tbl,row_id")
    migrate(db, 27)
    assert got("select tbl,row_id,old_city,new_city from kg_city_fix_v27 order by tbl,row_id") == before_rows
    assert got("select city from kg_store where store_id=102") == '平潭'
    checks.append('V27 rerun is idempotent and keeps original values')
    rollback = (MIG / 'V27__rollback.sql').read_text(encoding='utf-8')
    sql(rollback, db, error='explicit authorization')
    sql("update kg_store set city='平潭综合实验区' where store_id=105;", db)  # changed by ops after V27: must stay as is
    sql('set @kg_allow_city_alias_rollback=1;\n' + rollback, db)
    assert got("select city from kg_store where store_id=102") == '平潭岛'
    assert got("select city from kg_store where store_id=106") == '思明区'
    assert got("select city from kg_store where store_id=105") == '平潭综合实验区'
    assert got("select city from kg_stay where stay_id=4") == '平潭岛' and got("select city from kg_activity where activity_id=1") == '平潭岛'
    assert got("select count(*) from information_schema.tables where table_schema=database() and table_name='kg_city_fix_v27'") == '0'
    assert got("select count(*) from kg_schema_version where version=27") == '0'
    checks.append('V27 rollback requires authorization, restores only rows still holding V27 values, removes ledger row')
    migrate(db, 27)
    assert got("select city from kg_store where store_id=102") == '平潭'
    checks.append('V27 reapplies after rollback')

    # ---------------------------------------------------------------- V25 回滚
    db2 = 'kg_alias_test_' + uuid.uuid4().hex[:12]; fresh(db2)
    migrate(db2, 25)
    rb25 = (MIG / 'V25__rollback.sql').read_text(encoding='utf-8')
    sql(rb25, db2, error='explicit authorization')
    sql("insert into kg_store(store_id,store_name,city,status,del_flag) values (201,'思明小院','思明区','0','0');", db2)
    sql('set @kg_allow_national_cities_rollback=1;\n' + rb25, db2, error='used by business data')
    sql("delete from kg_store where store_id=201;", db2)
    sql('set @kg_allow_national_cities_rollback=1;\n' + rb25, db2)
    assert sql("select count(*) from kg_city where create_by='V25'", db2) == '0'
    assert sql("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_city' and column_name in ('adcode','parent_name','level','search_aliases')", db2) == '0'
    assert sql("select count(*) from kg_schema_version where version=25", db2) == '0'
    migrate(db2, 25)
    assert int(sql("select count(*) from kg_city where create_by='V25'", db2)) > 3000
    checks.append('V25 rollback requires authorization, refuses while business data uses added cities, removes rows/columns/ledger, reapplies')
    blocked = 'kg_alias_test_' + uuid.uuid4().hex[:12]; fresh(blocked)
    migrate(blocked, 25, 26)
    sql('set @kg_allow_national_cities_rollback=1;\n' + rb25, blocked, error='later migrations')
    checks.append('V25 rollback refuses while V26+ are applied')
finally:
    for d in made:
        sql('drop database if exists ' + d)
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'city-alias-database-result.json').write_text(json.dumps({'scope': 'isolated local MySQL; temporary databases removed', 'checks': checks}, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(checks, ensure_ascii=False, indent=2))
