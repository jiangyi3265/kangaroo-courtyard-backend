# -*- coding: utf-8 -*-
"""给已发岗位补填筛选条件（10-16 跟进，PUT /app/staff/jobs/{id}/conditions）的接口回归。

老岗位发布时还没有 性别偏好 / 休息排班 / 住宿条件 / 最短在店（V48 才有），用户按这些条件筛时看不到它们。
门店主理人、管家在员工端岗位列表里补填，立即生效、不用重新审核。这里走真实接口证明：

  F1 员工端岗位列表每行带出 genderPref / restMode(+Label) / roomType(+Label) / minDays / housing / conditionsMissing，
     顶上给出没填的已上架线下岗位数 conditionsMissingCount 和最新的一个 conditionsMissingJob
  F2 补填之前，按 性别=女 + 排班 + 房型 + 在店天数 筛的用户岗位列表里没有这个岗位；补填之后有；
     换成别的排班 / 性别=男 就又没有（证明是按刚填的值筛到的，不是碰巧）
  F3 重复提交同样的值结果一样；白名单外的值 422 且库里没变；普通用户（没有员工授权）被拒；本店线上任务 422
  F4 收尾：把岗位的四项条件改回原来的值（库里不留测试痕迹）

前置：后端以联调模式运行（kangaroo.wx.mock-enabled=true），库里有演示基线：h5-seed-app-01 是 107 号门店的主理人。
只连本机后端（KG_BASE 必须是 127.0.0.1 / localhost），不碰线上。

用法：
    set KG_BASE=http://127.0.0.1:8196
    set KG_DB=dkxy_acceptance_20261004
    python e2e/test_job_conditions_fill.py
"""
import os
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import BASE, http, scalar, check, section, PASSED, FAILED  # noqa: E402

assert BASE.startswith(('http://127.0.0.1:', 'http://localhost:')), '只连本机后端：' + BASE

STORE = 107
# 这次填的值：换宿岗位四项都能填
FILL = {'genderPref': 'F', 'restMode': '4-3', 'roomType': 'triple', 'minDays': 15}


def app_login(code):
    status, body = http('POST', '/app/auth/wx-login', {'code': code})
    assert body.get('code') == 200, body
    return body['token']


def data(body):
    return body.get('data') or {}


def staff_jobs(token, **params):
    q = urllib.parse.urlencode(dict(storeId=STORE, **params))
    status, body = http('GET', '/app/staff/jobs?' + q, app_token=token)
    assert body.get('code') == 200, body
    return data(body)


def user_list_has(job_id, **params):
    """用户端岗位列表（游客口径）按条件筛，翻几页看有没有这个岗位"""
    for page in range(1, 9):
        q = urllib.parse.urlencode(dict(params, pageNum=page, pageSize=50))
        status, body = http('GET', '/app/job/list?' + q)
        assert body.get('code') == 200, body
        rows = body.get('rows') or data(body).get('rows') or []
        if any(int(r.get('jobId') or 0) == job_id for r in rows):
            return True
        if len(rows) < 50:
            return False
    return False


def put_conditions(token, job_id, payload):
    return http('PUT', '/app/staff/jobs/%s/conditions' % job_id, payload, app_token=token)


def db_conditions(job_id):
    row = scalar("select concat_ws('|', gender_pref, rest_mode, room_type, ifnull(min_days, 'null')) from kg_job where job_id = %d" % job_id)
    return row


def main():
    mgr = app_login('h5-seed-app-01')
    guest_user = app_login('h5-seed-app-04')

    section('F1 员工端岗位列表带出筛选条件和「还没填」提示')
    listing = staff_jobs(mgr, type='volunteer')
    check('107 号店主理人有岗位编辑权限', True, listing.get('canEdit'))
    rows = listing.get('rows') or []
    for k in ('genderPref', 'restMode', 'restModeLabel', 'roomType', 'roomTypeLabel', 'minDays', 'housing', 'conditionsMissing'):
        check('每行都有 %s' % k, True, bool(rows) and all(k in r for r in rows), ok=bool(rows) and all(k in r for r in rows))
    check('顶上有 conditionsMissingCount', True, 'conditionsMissingCount' in listing)
    before_missing = int(listing.get('conditionsMissingCount') or 0)

    # 找一个已上架、用户端看得到的换宿岗位：优先四项都没填的（老岗位），没有就挑一个填过的，结束时改回原值
    published = [r for r in rows if r.get('status') == 'published']
    published.sort(key=lambda r: (not r.get('conditionsMissing'), -int(r['jobId'])))
    job = None
    for r in published:
        if user_list_has(int(r['jobId']), type='volunteer', cities=r.get('city') or ''):
            job = r
            break
    if job is None:
        check('107 号店有一个用户端看得到的已上架换宿岗位', True, False)
        return
    job_id = int(job['jobId'])
    city = job.get('city') or ''
    original = {'genderPref': job.get('genderPref') or '', 'restMode': job.get('restMode') or '', 'roomType': job.get('roomType') or '',
                'minDays': job.get('minDays')}
    original_db = db_conditions(job_id)
    print('选中岗位 %s（%s，%s），原条件 %s' % (job_id, job.get('title'), city, original))
    if job.get('conditionsMissing'):
        missing_job = listing.get('conditionsMissingJob') or {}
        check('有没填的岗位时给出最新的一个', True, bool(missing_job.get('jobId')), ok=bool(missing_job.get('jobId')))
    try:
        section('F2 补填前筛不到，补填后按刚填的值筛得到')
        filt = dict(type='volunteer', cities=city, gender='F', restModes=FILL['restMode'], roomTypes=FILL['roomType'], minStay=FILL['minDays'])
        if original['restMode'] != FILL['restMode']:
            check('补填前：按 排班=做四休三 筛不到', False, user_list_has(job_id, **filt))
        status, body = put_conditions(mgr, job_id, FILL)
        check('主理人补填成功', 200, body.get('code'))
        check('提示「已保存」', '已保存', body.get('msg'))
        saved = data(body)
        check('返回保存后的值和文案', ('F', '女', '4-3', '做四休三', 'triple', '三人间', 15),
              (saved.get('genderPref'), saved.get('genderPrefLabel'), saved.get('restMode'), saved.get('restModeLabel'),
               saved.get('roomType'), saved.get('roomTypeLabel'), saved.get('minDays')))
        check('库里四列已更新', 'F|4-3|triple|15', db_conditions(job_id))
        check('状态没变（不用重新审核）', 'published', scalar('select status from kg_job where job_id = %d' % job_id))
        check('补填后：性别=女 + 做四休三 + 三人间 + 在店 15 天 筛得到', True, user_list_has(job_id, **filt))
        check('换成 做六休一 就筛不到', False, user_list_has(job_id, **dict(filt, restModes='6-1')))
        check('性别=男 筛不到（偏好女）', False, user_list_has(job_id, **dict(filt, gender='M')))
        check('在店只能 7 天 筛不到（要 15 天）', False, user_list_has(job_id, **dict(filt, minStay=7)))
        after = staff_jobs(mgr, type='volunteer')
        row = next((r for r in after.get('rows') or [] if int(r['jobId']) == job_id), {})
        check('员工端列表这一行不再提示「待补条件」', False, row.get('conditionsMissing'))
        if job.get('conditionsMissing'):
            check('顶上「还没填」的数少了一个', before_missing - 1, int(after.get('conditionsMissingCount') or 0))

        section('F3 幂等、校验、越权、线上任务')
        status, again = put_conditions(mgr, job_id, FILL)
        check('同样的值再提交一次，结果一样', saved, data(again))
        status, bad = put_conditions(mgr, job_id, dict(FILL, restMode='7-0'))
        check('不认识的排班 422', 422, bad.get('code'))
        status, bad = put_conditions(mgr, job_id, dict(FILL, minDays=400))
        check('在店天数超过 365 天 422', 422, bad.get('code'))
        check('被拒后库里没变', 'F|4-3|triple|15', db_conditions(job_id))
        status, denied = put_conditions(guest_user, job_id, FILL)
        check('没有员工授权的普通用户被拒', True, denied.get('code') in (401, 403), ok=denied.get('code') in (401, 403))
        status, anon = put_conditions(None, job_id, FILL)
        check('没登录被拒', True, anon.get('code') != 200, ok=anon.get('code') != 200)
        online = scalar("select job_id from kg_job where store_id = %d and job_type = 'online' and del_flag = '0' and status <> 'closed' order by job_id desc limit 1" % STORE)
        if online:
            status, body = put_conditions(mgr, int(online), FILL)
            check('本店线上任务 422「线上任务没有这些条件」', (422, '线上任务没有这些条件'), (body.get('code'), body.get('msg')))
        else:
            print('（107 号店没有未关闭的线上任务，跳过这一条；Java 单测 AppStaffJobConditionsEndpointTest 覆盖）')
    finally:
        section('F4 收尾：改回原来的条件')
        status, body = put_conditions(mgr, job_id, original)
        check('改回原值', 200, body.get('code'))
        check('库里和开始时一样', original_db, db_conditions(job_id))


if __name__ == '__main__':
    main()
    print('\n通过 %d 条，失败 %d 条' % (len(PASSED), len(FAILED)))
    for name in FAILED:
        print('  FAIL ' + name)
    sys.exit(1 if FAILED else 0)
