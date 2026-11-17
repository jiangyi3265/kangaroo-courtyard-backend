# -*- coding: utf-8 -*-
"""岗位列表每个页签自己的筛选条件（2026-10-17 主人反馈）的真库回归：兼职日薪三档、全职月薪三档、食宿四项、全职类型五类。

在本地验收库里临时建一批带唯一记号的兼职 / 全职岗位（不同薪资单位、食宿、门店名 / 标题），走真实接口按条件筛，看列出来的正是该列的那几个：

  T1 兼职「日薪」payDay = 80 / 100 / 120：按起薪折成元/天比（日薪原样、时薪 × 8、周薪 ÷ 5、月薪 ÷ 21.75），区间岗位按下限；计件、面议不出现
  T2 全职「月薪」payMonthMin / payMonthMax：3000以下 / 3000–5000 / 5000以上 左闭右开、连续不漏（日薪 × 21.75 也算），面议不出现
  T3 食宿 board：包吃包住 all / 包住 room（含包吃包住）/ 包吃 meal（含包吃包住）/ 不包食宿 self（none 或空），「包部分」哪个都不算
  T4 全职「类型」categories：门店名或标题里有民宿 / 酒店 / 咖啡餐饮 / 酒吧的词，「其他」= 哪一类都不是，多选命中其一
  T5 全部筛选页 POST /app/jobs/search 同一组条件结果一致；老参数（salaryMin、board=none）照旧；不认识的值 422
  T6 收尾：删掉这次建的岗位（库里不留测试痕迹）

前置：后端以联调模式运行，库里有演示基线：107 古城拾光咖啡馆、108 双廊听海民宿、109 喜洲花间手作三个启用的门店。
只连本机后端（KG_BASE 必须是 127.0.0.1 / localhost），不碰线上。

用法：
    set KG_BASE=http://127.0.0.1:8213
    set KG_DB=dkxy_acceptance_20261004
    python e2e/test_job_tab_filters.py
"""
import os
import sys
import uuid
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import BASE, http, scalar, sql, sql_exec, check, section, PASSED, FAILED  # noqa: E402

assert BASE.startswith(('http://127.0.0.1:', 'http://localhost:')), '只连本机后端：' + BASE

TOKEN = 'r4tab' + uuid.uuid4().hex[:6]
CAFE, HOMESTAY, CRAFT = 107, 108, 109   # 古城拾光咖啡馆 / 双廊听海民宿 / 喜洲花间手作

# 名字: (门店, 类型, 标题里的词, salary_type, min, max, unit, board_type)
JOBS = {
    'P1': (CRAFT, 'parttime', '店员', 'daily', 80, 80, '元/天', 'all'),
    'P2': (CRAFT, 'parttime', '店员', 'daily', 100, 150, '元/天', 'room'),
    'P3': (CRAFT, 'parttime', '店员', 'hourly', 15, 15, '元/时', 'meal'),      # 15 × 8 = 120 元/天
    'P4': (CRAFT, 'parttime', '店员', 'monthly', 2000, 2000, '元/月', 'none'),  # 2000 ÷ 21.75 ≈ 91.95 元/天
    'P5': (CRAFT, 'parttime', '店员', 'piece', 50, 50, '元/单', 'none'),
    'P6': (CRAFT, 'parttime', '店员', 'none', None, None, None, 'part'),
    'P7': (CRAFT, 'parttime', '店员', 'weekly', 450, 450, '元/周', ''),        # 450 ÷ 5 = 90 元/天
    'F1': (HOMESTAY, 'fulltime', '管家', 'monthly', 6000, 8000, '元/月', 'room'),
    'F2': (CRAFT, 'fulltime', '酒店前台', 'monthly', 3000, 4000, '元/月', 'all'),
    'F3': (CAFE, 'fulltime', '店长', 'monthly', 2999, 3500, '元/月', 'none'),
    'F4': (CRAFT, 'fulltime', '清吧调酒师', 'daily', 230, 230, '元/天', 'meal'),  # 230 × 21.75 = 5002.5 元/月
    'F5': (CRAFT, 'fulltime', '内容策划', 'monthly', 4999, 6000, '元/月', 'meal'),
    'F6': (CRAFT, 'fulltime', '运营', 'none', None, None, None, ''),
}
IDS = {}


def lit(v):
    if v is None:
        return 'null'
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def create_jobs():
    for name, (store, jt, word, st, lo, hi, unit, board) in JOBS.items():
        title = '%s %s %s' % (TOKEN, name, word)
        sql_exec("insert into kg_job (store_id, job_type, title, city, salary_type, salary_min, salary_max, salary_unit, board_type, status, publish_time, del_flag, headcount) "
                 "values (%d, %s, %s, '大理', %s, %s, %s, %s, %s, 'published', now(), '0', 3)"
                 % (store, lit(jt), lit(title), lit(st), lit(lo), lit(hi), lit(unit), lit(board)))
        IDS[name] = int(scalar("select job_id from kg_job where title = %s" % lit(title)))


def names_of(ids):
    back = {v: k for k, v in IDS.items()}
    return sorted(back[i] for i in ids if i in back)


def listed(**params):
    """用户端岗位列表（游客口径）按条件筛，只看这次建的岗位"""
    q = urllib.parse.urlencode(dict(params, keyword=TOKEN, pageNum=1, pageSize=50))
    status, body = http('GET', '/app/job/list?' + q)
    if body.get('code') != 200:
        return body.get('code'), body.get('msg')
    return names_of(int(r.get('jobId')) for r in body.get('rows') or [])


def searched(**body):
    """全部筛选页（POST /app/jobs/search）：用 types 关键词只看这次建的岗位"""
    status, res = http('POST', '/app/jobs/search', dict(body, types=[TOKEN]))
    if res.get('code') != 200:
        return res.get('code'), res.get('msg')
    return names_of(int(j.get('jobId')) for j in (res.get('data') or {}).get('jobs') or [])


def main():
    stores = sql("select store_id, store_name from kg_store where store_id in (107, 108, 109) and status = '0' and del_flag = '0' order by store_id")
    check('演示门店 107 / 108 / 109 都在', ['107', '108', '109'], [r[0] for r in stores])
    if len(stores) != 3:
        return
    create_jobs()
    print('记号 %s，建了 %d 个岗位' % (TOKEN, len(IDS)))
    try:
        section('T1 兼职「日薪」三档：起薪折成元/天比')
        check('不选档：7 个兼职都在', ['P1', 'P2', 'P3', 'P4', 'P5', 'P6', 'P7'], listed(type='parttime'))
        check('80元/天以上：日 80、100、时 15×8、月 2000÷21.75、周 450÷5', ['P1', 'P2', 'P3', 'P4', 'P7'], listed(type='parttime', payDay=80))
        check('100元/天以上：100-150 按下限 100 算、时薪 120', ['P2', 'P3'], listed(type='parttime', payDay=100))
        check('120元/天以上：只有时薪 15（=120）', ['P3'], listed(type='parttime', payDay=120))
        check('老参数 salaryMin=120（区间上限够就算）照旧：100-150 的也在', ['P2', 'P3'], listed(type='parttime', salaryMin=120))

        section('T2 全职「月薪」三档：左闭右开、连续不漏')
        below = listed(type='fulltime', payMonthMax=3000)
        mid = listed(type='fulltime', payMonthMin=3000, payMonthMax=5000)
        above = listed(type='fulltime', payMonthMin=5000)
        check('3000以下：起薪 2999', ['F3'], below)
        check('3000–5000：正好 3000、4999', ['F2', 'F5'], mid)
        check('5000以上：6000、日薪 230×21.75=5002.5', ['F1', 'F4'], above)
        check('每个有月薪口径的岗位正好落在一档里，面议的不在任何一档', ['F1', 'F2', 'F3', 'F4', 'F5'], sorted(below + mid + above))

        section('T3 食宿四项')
        check('兼职 包吃包住 = all', ['P1'], listed(type='parttime', board='all'))
        check('兼职 包住 = all + room', ['P1', 'P2'], listed(type='parttime', board='room'))
        check('兼职 包吃 = all + meal', ['P1', 'P3'], listed(type='parttime', board='meal'))
        check('兼职 不包食宿 = none + 空（包部分不算）', ['P4', 'P5', 'P7'], listed(type='parttime', board='self'))
        check('全职 不包食宿', ['F3', 'F6'], listed(type='fulltime', board='self'))
        check('老参数 board=none = 不限', 7, len(listed(type='parttime', board='none')))
        check('食宿 + 日薪一起：包住 + 100元/天以上', ['P2'], listed(type='parttime', board='room', payDay=100))

        section('T4 全职「类型」')
        check('民宿：门店「双廊听海民宿」', ['F1'], listed(type='fulltime', categories='homestay'))
        check('酒店：标题「酒店前台」', ['F2'], listed(type='fulltime', categories='hotel'))
        check('咖啡餐饮：门店「古城拾光咖啡馆」', ['F3'], listed(type='fulltime', categories='food'))
        check('酒吧：标题「清吧调酒师」', ['F4'], listed(type='fulltime', categories='bar'))
        check('其他：哪一类的词都没有', ['F5', 'F6'], listed(type='fulltime', categories='other'))
        check('多选命中其一：民宿 + 酒吧', ['F1', 'F4'], listed(type='fulltime', categories='homestay,bar'))
        check('中文也认：酒店', ['F2'], listed(type='fulltime', categories='酒店'))
        check('类型 + 月薪 + 食宿：其他 + 3000–5000 + 包吃', ['F5'], listed(type='fulltime', categories='other', payMonthMin=3000, payMonthMax=5000, board='meal'))

        section('T5 全部筛选页同一口径；不认识的值 422')
        check('search 兼职 100元/天以上', ['P2', 'P3'], searched(jobType='parttime', payDay=100))
        check('search 兼职 不包食宿', ['P4', 'P5', 'P7'], searched(jobType='parttime', board='self'))
        check('search 全职 3000–5000 + 其他', ['F5'], searched(jobType='fulltime', payMonthMin=3000, payMonthMax=5000, categories=['other']))
        check('search 全职 民宿 + 咖啡餐饮', ['F1', 'F3'], searched(jobType='fulltime', categories=['homestay', 'food']))
        check('search 老的 board=none 不限', 6, len(searched(jobType='fulltime', board='none')))
        check('列表：不认识的类型 422', (422, '岗位类型不合法'), listed(type='fulltime', categories='cafe'))
        check('列表：不认识的食宿 422', (422, '食宿条件不合法'), listed(type='parttime', board='part'))
        check('列表：月薪下限 ≥ 上限 422', (422, '薪资区间不合法'), listed(type='fulltime', payMonthMin=5000, payMonthMax=5000))
        check('search：不认识的类型 422', (422, '岗位类型不合法'), searched(jobType='fulltime', categories=['cafe']))
    finally:
        section('T6 收尾：删掉这次建的岗位')
        if IDS:
            sql_exec('delete from kg_job where job_id in (%s)' % ','.join(str(i) for i in IDS.values()))
        check('库里没有这次的岗位了', '0', scalar("select count(*) from kg_job where title like %s" % lit(TOKEN + '%')))


if __name__ == '__main__':
    main()
    print('\n通过 %d 条，失败 %d 条' % (len(PASSED), len(FAILED)))
    for name in FAILED:
        print('  FAIL ' + name)
    sys.exit(1 if FAILED else 0)
