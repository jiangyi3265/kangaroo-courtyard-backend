"""V25 migration and real service tests in an isolated, disposable local MySQL database."""
from pathlib import Path
import os,re,subprocess,sys,uuid,json,hashlib
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from dbcred import db_password
BE=ROOT/'RuoYi-Vue'
database='kg_city_test_'+uuid.uuid4().hex[:12]
assert re.fullmatch(r'kg_city_test_[a-f0-9]{12}',database)
out=ROOT/'artifacts/city-coverage-20261002'
env=dict(os.environ,MYSQL_PWD=db_password(str(BE)),KG_CITY_TEST_DB=database,KG_CITY_TEST_OUTPUT=str(out/'service-directory.json'))
mysql=os.environ.get('KG_MYSQL',r'D:\MySQL Server 8.0\bin\mysql.exe')
def sql(text,use_db=True):
    args=[mysql,'--protocol=TCP','-h127.0.0.1','-uroot','--default-character-set=utf8mb4','-n','-N','-B']
    if use_db:args.append(database)
    p=subprocess.run(args,input=text,encoding='utf8',capture_output=True,env=env)
    assert p.returncode==0,p.stderr
    return p.stdout.strip()
created=False
checks=[]
try:
    sql('create database '+database+' character set utf8mb4;',False);created=True
    v11=(BE/'sql/kangaroo/V11__ext_user.sql').read_text(encoding='utf8')
    sql(re.search(r'create table if not exists kg_city \([\s\S]+?\) engine=innodb[^;]*;',v11).group(0))
    start=v11.index('insert ignore into kg_city')
    sql(v11[start:v11.index('-- 演示门店坐标',start)])
    sql("create table kg_schema_version(version int primary key,name varchar(128),applied_time datetime,checksum char(64));insert into kg_schema_version values(24,'fixture',now(),'fixture');")
    sql("update kg_city set status='1',sort=77,region='运营自定义' where name='厦门';insert into kg_city(name,full_name,province,pinyin,initial,status,hot) values('海岛营地','海岛营地','福建省','haidaoyingdi','H','0','1');")
    migration=(BE/'sql/kangaroo/V25__national_cities.sql').read_text(encoding='utf8')
    checksum=hashlib.sha256(migration.split('-- @kg-ledger')[0].encode()).hexdigest()
    assert checksum in migration.split('-- @kg-ledger')[1]
    sql(migration);count=sql('select count(*) from kg_city')
    sql("update kg_city set level='destination' where name='厦门';")
    sql(migration)
    assert sql('select count(*) from kg_city')==count=='3211'
    assert sql("select count(*) from kg_city where name='厦门' and status='1' and sort=77 and region='运营自定义' and hot='1' and level='destination'")=='1'
    assert sql("select count(*) from kg_city where name='平潭' and adcode='350128' and search_aliases like '%平潭岛%'")=='1'
    assert sql("select count(*) from kg_city where province='福建省'")=='94'
    assert sql("select count(*) from (select adcode from kg_city where adcode is not null group by adcode having count(*)>1) d")=='0'
    checks+=['V25 checksum and rerun/idempotence','preserve custom city and operator hot/status/sort/region','Fujian cities/counties including Pingtan and aliases; unique codes']
    run=subprocess.run(['mvn.cmd','-pl','ruoyi-kangaroo','-am','-Dtest=CityBoundaryIndexTest,KgCityCatalogIT','-Dsurefire.failIfNoSpecifiedTests=false','test'],cwd=BE,env=env)
    assert run.returncode==0,'City tests failed'
    checks+=['real database city service + offline boundaries + disabled district + invalid coordinates']
finally:
    if created:sql('drop database '+database,False)
print(json.dumps({'scope':'isolated local MySQL removed after test','rows':count,'checks':checks},ensure_ascii=False,indent=2))
