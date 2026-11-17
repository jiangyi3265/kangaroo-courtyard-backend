# -*- coding: utf-8 -*-
"""岗位照片（V49，内测反馈 2.0）和「待补条件」新口径的接口回归。

门店在小程序发布岗位时可以带照片、已发岗位可以整组换照片（PUT /app/staff/jobs/{id}/images），最多 9 张，第一张做封面；
用户端岗位列表、详情返回 images（数组）和 cover（第一张 → 岗位封面 → 门店封面）。这里走真实接口证明：

  P1 用小程序的上传接口（/app/upload/image）传 3 张图，主理人换照片：库里 images 是 JSON 数组、cover 是第一张；
     详情和用户端列表都返回这几张，封面是第一张；调换顺序后封面跟着变
  P2 别处的网址、包内图片、10 张、线上任务都 422，库里没变；普通用户（没有员工授权）被拒
  P3 删光照片：封面退回门店封面，详情 images 是空数组
  P4 发布新岗位带照片：落草稿，images / cover 写对；带别处的图 422、不落库
  P5 员工端列表「待补条件」：只数排班没填、或包住却没填房型的已上架线下岗位（性别、在店没填 = 不限，不算）；和库里按同一口径数的一样
  P6 收尾：岗位照片、封面改回原值；测试草稿删掉（逻辑删除）

前置：后端以联调模式运行（kangaroo.wx.mock-enabled=true），库已执行 V49；h5-seed-app-01 是 107 号门店的主理人。
只连本机后端（KG_BASE 必须是 127.0.0.1 / localhost），不碰线上。KG_DB 指向后端连的那个库（可以是 kg_scratch_* 一次性副本）。

用法：
    set KG_BASE=http://127.0.0.1:8202
    set KG_DB=kg_scratch_fb2jobs
    python e2e/test_job_photos.py
"""
import json
import os
import sys
import urllib.parse
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import BASE, http, scalar, sql_exec, check, section, PASSED, FAILED  # noqa: E402

assert BASE.startswith(('http://127.0.0.1:', 'http://localhost:')), '只连本机后端：' + BASE

STORE = 107
MEDIA = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), '袋鼠旅游2', 'static', 'media')


def app_login(code):
    status, body = http('POST', '/app/auth/wx-login', {'code': code})
    assert body.get('code') == 200, body
    return body['token']


def data(body):
    return body.get('data') or {}


def upload(token, filename):
    """小程序同款上传：multipart，带 X-App-Token 和 X-App-Upload-Version: 2"""
    path = os.path.join(MEDIA, filename)
    boundary = uuid.uuid4().hex
    with open(path, 'rb') as f:
        payload = f.read()
    body = (('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: image/%s\r\n\r\n'
             % (boundary, filename, 'png' if filename.endswith('.png') else 'jpeg')).encode('utf-8') + payload + ('\r\n--%s--\r\n' % boundary).encode('utf-8'))
    req = urllib.request.Request(BASE + '/app/upload/image', data=body, method='POST', headers={
        'Content-Type': 'multipart/form-data; boundary=' + boundary, 'X-App-Token': token, 'X-App-Upload-Version': '2'})
    with urllib.request.urlopen(req, timeout=30) as r:
        out = json.loads(r.read().decode('utf-8'))
    assert out.get('code') == 200, out
    return out['data']['url']


def put_images(token, job_id, images):
    return http('PUT', '/app/staff/jobs/%s/images' % job_id, {'images': images}, app_token=token)


def db_images(job_id):
    return scalar("select concat(images, '|', ifnull(cover, '')) from kg_job where job_id = %d" % job_id)


def detail(job_id):
    status, body = http('GET', '/app/job/%s' % job_id)
    assert body.get('code') == 200, body
    return data(body)


def list_row(job_id):
    for page in range(1, 12):
        q = urllib.parse.urlencode(dict(type='volunteer', pageNum=page, pageSize=50))
        status, body = http('GET', '/app/job/list?' + q)
        assert body.get('code') == 200, body
        rows = body.get('rows') or data(body).get('rows') or []
        for r in rows:
            if int(r.get('jobId') or 0) == job_id:
                return r
        if len(rows) < 50:
            return None
    return None


def main():
    mgr = app_login('h5-seed-app-01')
    user = app_login('h5-seed-app-04')
    job_id = int(scalar("select j.job_id from kg_job j join kg_store s on s.store_id = j.store_id where j.store_id = %d and j.job_type = 'volunteer' "
                        "and j.status = 'published' and j.del_flag = '0' and s.status = '0' and s.del_flag = '0' order by j.job_id desc limit 1" % STORE))
    online_id = scalar("select job_id from kg_job where store_id = %d and job_type = 'online' and status <> 'closed' and del_flag = '0' limit 1" % STORE)
    store_cover = scalar("select ifnull(cover, '') from kg_store where store_id = %d" % STORE) or ''
    before = db_images(job_id)
    old_images, old_cover = before.split('|', 1)
    print('岗位', job_id, '原来：', before, '| 门店封面：', store_cover)
    draft_id = None
    try:
        section('P1 传照片、换照片：库里、详情、列表都是这几张，第一张做封面')
        u1, u2, u3 = upload(mgr, 'cover-empty.png'), upload(mgr, 'rooms-cover-1.jpg'), upload(mgr, 'rooms-cover-2.jpg')
        check('上传接口给的是平台路径', True, all(u.startswith('/profile/upload/') for u in (u1, u2, u3)))
        status, body = put_images(mgr, job_id, [u1, u2, u3])
        check('主理人换照片 → 200「照片已更新」', (200, '照片已更新'), (body.get('code'), body.get('msg')))
        check('返回整组照片和封面', ([u1, u2, u3], u1), (data(body).get('images'), data(body).get('cover')))
        check('库里 images 是 JSON 数组、cover 是第一张', json.dumps([u1, u2, u3], separators=(',', ':')) + '|' + u1, db_images(job_id))
        d = detail(job_id)
        check('详情 images / cover', ([u1, u2, u3], u1), (d.get('images'), d.get('cover')))
        r = list_row(job_id)
        check('用户端列表这一行 images / cover', ([u1, u2, u3], u1), (r and r.get('images'), r and r.get('cover')))
        status, body = put_images(mgr, job_id, [u3, u1])
        check('调换顺序：封面跟着变成新的第一张', ([u3, u1], u3), (detail(job_id).get('images'), detail(job_id).get('cover')))
        status, body = http('GET', '/app/staff/jobs?storeId=%d' % STORE, app_token=mgr)
        row = next((x for x in data(body).get('rows') or [] if int(x.get('jobId')) == job_id), None)
        check('员工端列表这一行带上当前照片（换照片弹层打开时带出）', [u3, u1], row and row.get('images'))

        section('P2 不合格的照片、线上任务、普通用户都被拒，库里没变')
        snap = db_images(job_id)
        for name, imgs in [('别处的网址', ['https://example.com/a.jpg']), ('包内图片', ['/static/media/cover-empty.png']),
                           ('带 .. 的路径', ['/profile/upload/../../etc/passwd']), ('10 张', [u1] + ['/profile/upload/2026/10/16/x%d.jpg' % i for i in range(9)])]:
            status, body = put_images(mgr, job_id, imgs)
            check(name + ' → 422', 422, body.get('code'))
        status, body = http('PUT', '/app/staff/jobs/%s/images' % job_id, {}, app_token=mgr)
        check('没带照片 → 422', 422, body.get('code'))
        check('库里没变', snap, db_images(job_id))
        status, body = put_images(user, job_id, [u1])
        check('普通用户（没有员工授权）被拒', True, body.get('code') in (401, 403))
        if online_id:
            status, body = put_images(mgr, int(online_id), [u1])
            check('本店线上任务 → 422', (422, '线上任务没有岗位照片'), (body.get('code'), body.get('msg')))

        section('P3 删光照片：封面退回门店封面')
        status, body = put_images(mgr, job_id, [])
        d = detail(job_id)
        check('详情 images 空数组、封面是门店封面', ([], store_cover), (d.get('images'), d.get('cover')))
        check('库里 images 空、cover 是门店封面', '|' + store_cover, db_images(job_id))

        section('P4 发布新岗位带照片：落草稿，images / cover 写对；带别处的图 422')
        base = {'jobType': 'volunteer', 'title': 'E2E 岗位照片 ' + uuid.uuid4().hex[:6], 'startDate': '2026-12-01', 'hours': '每天 4 小时',
                'pay': 'board', 'boardEnabled': True, 'salaryEnabled': False, 'headcount': 1, 'work': '前台接待', 'hope': '爱笑'}
        status, body = http('POST', '/app/staff/jobs?storeId=%d' % STORE, dict(base, images=['https://example.com/x.jpg']), app_token=mgr)
        check('带别处的图 → 422，不落库', (422, '0'), (body.get('code'), scalar("select count(*) from kg_job where title = '%s'" % base['title'])))
        status, body = http('POST', '/app/staff/jobs?storeId=%d' % STORE, dict(base, images=[u2, u1]), app_token=mgr)
        check('带两张照片发布 → 200', 200, body.get('code'))
        draft_id = int(data(body).get('jobId'))
        check('草稿的 images / cover', json.dumps([u2, u1], separators=(',', ':')) + '|' + u2 + '|draft',
              scalar("select concat(images, '|', cover, '|', status) from kg_job where job_id = %d" % draft_id))

        section('P5 「待补条件」新口径：排班没填、或包住却没填房型（性别、在店没填不算）')
        status, body = http('GET', '/app/staff/jobs?storeId=%d' % STORE, app_token=mgr)
        expect = scalar("select count(*) from kg_job j where j.store_id = %d and j.del_flag = '0' and j.status = 'published' and j.job_type <> 'online' "
                        "and (j.rest_mode = '' or ((j.job_type = 'volunteer' or j.board_type in ('all', 'room')) and j.room_type = ''))" % STORE)
        check('顶上的数和库里按新口径数的一样', int(expect), data(body).get('conditionsMissingCount'))
        old_rule = scalar("select count(*) from kg_job m where m.store_id = %d and m.del_flag = '0' and m.status = 'published' and m.job_type <> 'online' "
                          "and m.gender_pref = '' and m.rest_mode = '' and m.room_type = '' and (m.min_days is null or m.min_days = 0)" % STORE)
        print('       （旧口径「四项全空」会数成 %s 个）' % old_rule)
        rows = data(body).get('rows') or []
        bad = [x['jobId'] for x in rows if x.get('jobType') != 'online' and x.get('status') != 'closed'
               and bool(x.get('conditionsMissing')) != (not x.get('restMode') or (bool(x.get('housing')) and not x.get('roomType')))]
        check('每行的「待补条件」和同一口径对得上', [], bad)
    finally:
        section('P6 收尾：照片、封面改回原值；测试草稿删掉')
        sql_exec("update kg_job set images = '%s', cover = %s where job_id = %d" % (old_images.replace("'", "''"),
                 "'%s'" % old_cover.replace("'", "''") if old_cover else 'null', job_id))
        check('岗位照片、封面改回原值', before, db_images(job_id))
        if draft_id:
            sql_exec("update kg_job set del_flag = '2' where job_id = %d and status = 'draft'" % draft_id)
            check('测试草稿已删', '2', scalar("select del_flag from kg_job where job_id = %d" % draft_id))

    print('\n通过 %d 条，失败 %d 条' % (len(PASSED), len(FAILED)))
    if FAILED:
        print('失败：', FAILED)
        sys.exit(1)


if __name__ == '__main__':
    main()
