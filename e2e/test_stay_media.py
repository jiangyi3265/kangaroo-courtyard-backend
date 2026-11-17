# -*- coding: utf-8 -*-
"""旅居改版 R3（2026-10-17，V53）：小院视频 / 房型视频 / 生活碎片 / 评价视频 / 区县全称 的接口回归（走真实 HTTP，不碰线上）。

  M1 视频分段上传（/app/upload/video/parts）：一个 9MB 多的 mp4（ftyp 头 + 随机字节）按 8MB 一段原样传、拼好；
     公开地址下载回来逐字节相同、Content-Type 是 video/mp4、Range 请求回 206（video 拖进度条要它）；
     不是视频（改了扩展名的网页）第一段就 422 且什么都没存；超 50MB / 超 60 秒 / avi 登记就 422；跳段 409；没登录 401
  M2 上线中的房源直接挂小院视频（不用下架）→ 详情接口 introVideo；别处的视频地址 422；别家门店的主理人 403
  M3 房型视频 → /room-types 和详情 roomTypes 都带 videoUrl / videoCover
  M4 生活碎片：顶部背景图 / 标题 / 文案；加一张照片一段视频 → 公开页 /diary 两条、详情 diary.count = 2；
     后台「生活碎片」隐藏一条 → 公开页只剩一条、员工端看得到「已被平台隐藏」；排序；删除
  M5 上线中的房源：只改出行参考直接保存（还是 online、记操作日志）；改名称照旧要先下架
  M6 住宿评价附视频：完成的住宿订单写评价带 videos → 小院评价页「有图·视频」页签能看到、视频地址原样；员工端评价列表带 videos；别处的视频 422
  M7 区县全称：员工选「宾」存成「宾县」；R2 时存的简称（宾）和全称（宾县）在区县接口里合成一个、按全称筛两种都出来；详情显示全称
  收尾：IsolatedStayFixture 按本轮门店 / 账号清理（kg_stay_diary、kg_review 都带 store_id）；视频文件留在本机上传目录（不影响库）

前置：后端以联调模式运行（kangaroo.pay.mode=mock、kangaroo.wx.mock-enabled=true），库已执行 V53。
用法：
    set KG_BASE=http://127.0.0.1:8195
    set KG_DB=dkxy_acceptance_20261004
    set KG_REDIS_DB=13
    python e2e/test_stay_media.py
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import BASE, http, scalar, sql_exec, check, section, PASSED, FAILED  # noqa: E402
from isolated_stay_fixture import IsolatedStayFixture  # noqa: E402
from test_ext_manage import upload  # noqa: E402

assert BASE.startswith(('http://127.0.0.1:', 'http://localhost:')), '只连本机后端：' + BASE
RUN = uuid.uuid4().hex[:8]
PART = 8 * 1024 * 1024
MEDIA = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), '袋鼠旅游2', 'static', 'media')
FIXTURE = None


def data(body):
    return body.get('data') or {}


def app_login(tag):
    status, body = http('POST', '/app/auth/wx-login', {'code': 'media-%s-%s' % (RUN, tag)})
    assert body.get('code') == 200, body
    FIXTURE.user(body['token'], body['account']['id'])
    return body['token'], int(body['account']['id'])


def mp4(size, brand=b'isom'):
    head = b'\x00\x00\x00\x18ftyp' + brand + b'\x00\x00\x02\x00' + brand + b'mp41'
    return head + os.urandom(size - len(head))


def raw_post(path, payload, token):
    headers = {'Content-Type': 'application/octet-stream'}
    if token:
        headers['X-App-Token'] = token
    req = urllib.request.Request(BASE + path, data=payload, headers=headers, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        text = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {'raw': text}


def upload_video(token, blob, name='wxfile://tmp_clip.mp4', duration=42):
    status, b = http('POST', '/app/upload/video/parts', {'name': name, 'size': len(blob), 'duration': duration}, app_token=token)
    assert b.get('code') == 200, b
    begin = data(b)
    uid = begin['uploadId']
    for i in range(begin['parts']):
        status, r = raw_post('/app/upload/video/parts/%s/%d' % (uid, i), blob[i * PART:(i + 1) * PART], token)
        assert r.get('code') == 200, r
    status, f = http('POST', '/app/upload/video/parts/%s/finish' % uid, {}, app_token=token)
    assert f.get('code') == 200, f
    return begin, data(f)


def get_bytes(path, headers=None):
    req = urllib.request.Request(BASE + path, headers=headers or {}, method='GET')
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def image(token, filename='rooms-cover-1.jpg'):
    with open(os.path.join(MEDIA, filename), 'rb') as f:
        content = f.read()
    boundary = uuid.uuid4().hex
    body = (('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: image/jpeg\r\n\r\n' % (boundary, filename)).encode('utf-8')
            + content + ('\r\n--%s--\r\n' % boundary).encode('utf-8'))
    req = urllib.request.Request(BASE + '/app/upload/image', data=body, method='POST', headers={
        'Content-Type': 'multipart/form-data; boundary=' + boundary, 'X-App-Token': token, 'X-App-Upload-Version': '2'})
    with urllib.request.urlopen(req, timeout=30) as r:
        out = json.loads(r.read().decode('utf-8'))
    assert out.get('code') == 200, out
    return out['data']['url']


def main(fx):
    # 夹具里的主理人（授权之后重新登录一次，拿带员工授权的会话）
    status, login = http('POST', '/app/auth/wx-login', {'code': fx.prefix + '-manager'})
    assert login.get('code') == 200, login
    manager = login['token']
    fx.user(manager, login['account']['id'])
    stay_id, room_type_id, store_id = fx.stay_id, fx.room_type_id, fx.store_id

    section('M1 视频分段上传：按 8MB 一段原样传，拼好后公开可下载、逐字节相同、支持 Range')
    blob = mp4(PART + 1234567)
    begin, saved = upload_video(manager, blob)
    url = saved.get('url', '')
    check('M1 两段（8MB + 剩下的）', 2, begin.get('parts'))
    check('M1 平台视频路径 /profile/upload/videos/yyyy/MM/dd/{32位}.mp4', True,
          url.startswith('/profile/upload/videos/') and url.endswith('.mp4') and len(url.rsplit('/', 1)[1]) == 36, ok=url.startswith('/profile/upload/videos/'))
    check('M1 服务端记的大小 = 原文件', len(blob), saved.get('size'))
    status, headers, body = get_bytes(url)
    check('M1 公开地址下载回来逐字节相同', (200, hashlib.sha256(blob).hexdigest()), (status, hashlib.sha256(body).hexdigest()))
    ctype = {k.lower(): v for k, v in headers.items()}.get('content-type', '')
    check('M1 Content-Type 是 video/mp4（浏览器、小程序 video 认它）', True, ctype.startswith('video/mp4'), ok=ctype.startswith('video/mp4'))
    status, headers, part = get_bytes(url, {'Range': 'bytes=100-199'})
    h = {k.lower(): v for k, v in headers.items()}
    check('M1 Range 请求回 206 + 正好那 100 个字节（拖动进度条不用整段重下）', (206, 100, True), (status, len(part), part == blob[100:200]))
    check('M1 Content-Range 正确', 'bytes 100-199/%d' % len(blob), h.get('content-range'))
    check('M1 带 nosniff（公开目录统一加）', 'nosniff', h.get('x-content-type-options'))
    mov_begin, mov = upload_video(manager, mp4(3000, b'qt  '), name='IMG_0001.MOV', duration=5)
    check('M1 mov（iPhone 原片）也收，扩展名保留 .mov', True, mov.get('url', '').endswith('.mov'), ok=mov.get('url', '').endswith('.mov'))
    # 不是视频：第一段就拒、什么都没存
    html = b'<!doctype html><script>alert(1)</script>' + b' ' * 200
    status, b = http('POST', '/app/upload/video/parts', {'name': 'evil.mp4', 'size': len(html), 'duration': 3}, app_token=manager)
    uid = data(b).get('uploadId')
    status, r = raw_post('/app/upload/video/parts/%s/0' % uid, html, manager)
    check('M1 改了扩展名的网页：第一段 422', (422, True), (r.get('code'), '不是能播放的' in str(r.get('msg'))))
    status, f = http('POST', '/app/upload/video/parts/%s/finish' % uid, {}, app_token=manager)
    check('M1 被拒后这次上传作废（finish 404）', 404, f.get('code'))
    for why, body in [('超 50MB', {'name': 'a.mp4', 'size': 50 * 1024 * 1024 + 1, 'duration': 10}), ('超 60 秒', {'name': 'a.mp4', 'size': 1000, 'duration': 75}),
                      ('avi', {'name': 'a.avi', 'size': 1000, 'duration': 10})]:
        status, b = http('POST', '/app/upload/video/parts', body, app_token=manager)
        check('M1 登记就拒：' + why, 422, b.get('code'))
    status, b = http('POST', '/app/upload/video/parts', {'name': 'b.mp4', 'size': PART + 10, 'duration': 3}, app_token=manager)
    uid = data(b).get('uploadId')
    status, r = raw_post('/app/upload/video/parts/%s/1' % uid, b'x' * 10, manager)
    check('M1 跳段 409', 409, r.get('code'))
    http('DELETE', '/app/upload/video/parts/%s' % uid, app_token=manager)
    status, b = http('POST', '/app/upload/video/parts', {'name': 'a.mp4', 'size': 1000, 'duration': 10})
    check('M1 没登录 401', 401, b.get('code'))

    section('M2 上线中的房源直接挂「3分钟看小院」视频（不用下架）')
    cover = image(manager)
    check('M2 房源是上线状态', 'online', scalar('select status from kg_stay where stay_id=%d' % stay_id))
    status, b = http('PUT', '/app/staff/stay/stays/%d/intro-video' % stay_id, {'url': url, 'cover': cover}, app_token=manager)
    check('M2 挂上小院视频', 200, b.get('code'))
    check('M2 还是上线状态（不退回草稿）', 'online', scalar('select status from kg_stay where stay_id=%d' % stay_id))
    check('M2 记了员工操作日志', True, int(scalar("select count(*) from kg_staff_op_log where store_id=%d and action='stay_media'" % store_id) or 0) >= 1,
          ok=int(scalar("select count(*) from kg_staff_op_log where store_id=%d and action='stay_media'" % store_id) or 0) >= 1)
    d = data(http('GET', '/app/stay/%d' % stay_id)[1])
    check('M2 详情 introVideo = {url, cover}', (url, cover), ((d.get('introVideo') or {}).get('url'), (d.get('introVideo') or {}).get('cover')))
    check('M2 详情不带原始列', False, any(k in d for k in ('introVideoCover', 'diaryBg', 'diaryTitle', 'diaryIntro')))
    status, b = http('PUT', '/app/staff/stay/stays/%d/intro-video' % stay_id, {'url': 'https://evil.example.com/a.mp4'}, app_token=manager)
    check('M2 别处的视频地址 422', 422, b.get('code'))
    fake = '/profile/upload/videos/2026/10/17/' + uuid.uuid4().hex + '.mp4'
    status, b = http('PUT', '/app/staff/stay/stays/%d/intro-video' % stay_id, {'url': fake}, app_token=manager)
    check('M2 格式对但文件不存在的路径 422', 422, b.get('code'))
    other, other_uid = app_login('other-manager')
    http('POST', '/kangaroo/grant', {'appUserId': other_uid, 'storeId': fx.other_store_id, 'roleCode': 'manager'}, token=fx.admin)
    other, _ = app_login('other-manager')
    status, b = http('PUT', '/app/staff/stay/stays/%d/intro-video' % stay_id, {'url': ''}, app_token=other)
    check('M2 别家门店的主理人改不了（403）', 403, b.get('code'))
    check('M2 视频没被别人删掉', url, scalar('select intro_video from kg_stay where stay_id=%d' % stay_id))

    section('M3 房型短视频（≤1分钟）')
    room_video = mov.get('url')
    status, b = http('PUT', '/app/staff/stay/room-types/%d/video' % room_type_id, {'url': room_video, 'cover': cover}, app_token=manager)
    check('M3 挂上房型视频', 200, b.get('code'))
    rts = data(http('GET', '/app/stay/%d/room-types' % stay_id)[1]) or []
    rt = next((x for x in rts if x.get('roomTypeId') == room_type_id), {})
    check('M3 /room-types 带 videoUrl / videoCover', (room_video, cover), (rt.get('videoUrl'), rt.get('videoCover')))
    d = data(http('GET', '/app/stay/%d' % stay_id)[1])
    rt2 = next((x for x in d.get('roomTypes') or [] if x.get('roomTypeId') == room_type_id), {})
    check('M3 详情 roomTypes 也带', room_video, rt2.get('videoUrl'))

    section('M4 生活碎片：顶部、加照片 / 视频、公开页只给显示中的、后台隐藏、排序、删除')
    status, b = http('PUT', '/app/staff/stay/stays/%d/diary-header' % stay_id,
                     {'bg': cover, 'title': '小院日记', 'intro': '花开有信，晚风有约\n路过的人，都会留下一点故事'}, app_token=manager)
    check('M4 顶部保存', 200, b.get('code'))
    photo = image(manager, 'stays-kangaroo-stamp.png') if os.path.exists(os.path.join(MEDIA, 'stays-kangaroo-stamp.png')) else cover
    status, b = http('POST', '/app/staff/stay/stays/%d/diary' % stay_id, {'mediaType': 'image', 'url': photo, 'caption': '火烧云', 'shotDate': '2026-10-12'}, app_token=manager)
    check('M4 加一张照片', 200, b.get('code'))
    status, b = http('POST', '/app/staff/stay/stays/%d/diary' % stay_id, {'mediaType': 'video', 'url': url, 'cover': cover, 'caption': '星空', 'shotDate': '2026-10-11'},
                     app_token=manager)
    check('M4 加一段视频', 200, b.get('code'))
    entries = data(b).get('entries') or []
    check('M4 员工端两条，新的照片在前（按拍摄日期）', ['火烧云', '星空'], [e.get('caption') for e in entries])
    status, b = http('POST', '/app/staff/stay/stays/%d/diary' % stay_id, {'mediaType': 'image', 'url': url, 'caption': 'x'}, app_token=manager)
    check('M4 把视频当照片交 422', 422, b.get('code'))
    status, b = http('POST', '/app/staff/stay/stays/%d/diary' % stay_id, {'mediaType': 'image', 'url': photo, 'caption': '字' * 33}, app_token=manager)
    check('M4 一句话超 32 字 422', 422, b.get('code'))
    pub = data(http('GET', '/app/stay/%d/diary?page=1' % stay_id)[1])
    check('M4 公开页两条、顶部是员工填的', (2, '小院日记', ['花开有信，晚风有约', '路过的人，都会留下一点故事'], cover),
          (len(pub.get('rows') or []), (pub.get('header') or {}).get('title'), (pub.get('header') or {}).get('lines'), (pub.get('header') or {}).get('bg')))
    check('M4 卡片日期角标 10.12 / 视频带封面', [('10.12', 'image', photo), ('10.11', 'video', cover)],
          [(r.get('date'), r.get('type'), r.get('cover')) for r in pub.get('rows') or []])
    d = data(http('GET', '/app/stay/%d' % stay_id)[1])
    check('M4 详情 diary.count = 2', 2, (d.get('diary') or {}).get('count'))
    video_id = [e['diaryId'] for e in entries if e.get('type') == 'video'][0]
    photo_id = [e['diaryId'] for e in entries if e.get('type') == 'image'][0]
    status, b = http('PUT', '/kangaroo/ext/staydiary', {'diaryId': video_id, 'status': '1'}, token=fx.admin)
    check('M4 后台隐藏一条', 200, b.get('code'))
    pub = data(http('GET', '/app/stay/%d/diary' % stay_id)[1])
    check('M4 公开页只剩显示中的那条', [photo_id], [r.get('diaryId') for r in pub.get('rows') or []])
    staff_view = data(http('GET', '/app/staff/stay/stays/%d/media' % stay_id, app_token=manager)[1])
    check('M4 员工端看得到「已被平台隐藏」', True, any(e.get('hidden') for e in staff_view.get('entries') or []))
    status, b = http('PUT', '/app/staff/stay/stays/%d/diary-order' % stay_id, {'ids': [video_id, photo_id]}, app_token=manager)
    check('M4 排序：视频排到前面', [video_id, photo_id], [e.get('diaryId') for e in data(b).get('entries') or []])
    status, b = http('DELETE', '/app/staff/stay/diary/%d' % video_id, app_token=manager)
    check('M4 删除', (200, 1), (b.get('code'), len(data(b).get('entries') or [])))
    status, b = http('DELETE', '/app/staff/stay/diary/%d' % photo_id, app_token=other)
    check('M4 别家门店删不了（403）', 403, b.get('code'))
    status, raw_list = http('GET', '/kangaroo/ext/staydiary/list?stayId=%d' % stay_id, token=fx.admin)
    check('M4 后台「生活碎片」列表能查到（删了的不在里面）', (200, 1), (raw_list.get('code'), raw_list.get('total')))

    section('M5 上线中的房源：补充信息直接改，核心内容照旧要先下架')
    st = data(http('GET', '/app/staff/stay/stays/%d' % stay_id, app_token=manager)[1])
    body = {'stayName': st.get('stayName'), 'address': st.get('address'), 'travelPros': '步行 5 分钟到古城（%s）' % RUN, 'hostSkills': '手冲咖啡'}
    status, b = http('PUT', '/app/staff/stay/stays/%d' % stay_id, body, app_token=manager)
    check('M5 只改出行参考 / 擅长：直接保存', 200, b.get('code'))
    check('M5 写进库、还是上线状态', ('步行 5 分钟到古城（%s）' % RUN, '手冲咖啡', 'online'),
          tuple(scalar("select concat_ws('|', travel_pros, host_skills, status) from kg_stay where stay_id=%d" % stay_id).split('|')))
    check('M5 记了「修改房源补充信息」', True, int(scalar("select count(*) from kg_staff_op_log where store_id=%d and action='stay_edit_extra'" % store_id) or 0) >= 1,
          ok=int(scalar("select count(*) from kg_staff_op_log where store_id=%d and action='stay_edit_extra'" % store_id) or 0) >= 1)
    status, b = http('PUT', '/app/staff/stay/stays/%d' % stay_id, dict(body, stayName=st.get('stayName') + '改'), app_token=manager)
    check('M5 改名称：照旧要先下架', (500, True), (b.get('code'), '请先下架再修改内容' in str(b.get('msg'))), ok='请先下架再修改内容' in str(b.get('msg')))
    check('M5 名称没变', st.get('stayName'), scalar('select stay_name from kg_stay where stay_id=%d' % stay_id))

    section('M6 住宿评价附视频：评价页「有图·视频」能看到；员工端列表带 videos')
    guest, guest_uid = app_login('guest')
    check_in = time.strftime('%Y-%m-%d', time.localtime(time.time() + 3 * 86400))
    check_out = time.strftime('%Y-%m-%d', time.localtime(time.time() + 4 * 86400))
    status, b = http('POST', '/app/stay/orders', {'roomTypeId': room_type_id, 'checkIn': check_in, 'checkOut': check_out, 'guests': 1, 'guestName': '视频住客',
                                                  'guestPhone': '13800138000', 'agreementVersion': '2026-09', 'idemKey': 'media-' + RUN}, app_token=guest)
    o = data(b)
    check('M6 下单', 200, b.get('code'))
    http('POST', '/app/pay/%s/prepay' % o.get('payNo'), {}, app_token=guest)
    status, b = http('POST', '/app/pay/%s/mock-confirm' % o.get('payNo'), {}, app_token=guest)
    check('M6 联调支付到账', 200, b.get('code'))
    order_id = int(scalar("select order_id from kg_stay_order where order_no='%s'" % o.get('orderNo')))
    # 离店后系统自动把订单记成已完成（定时任务）；这里直接把这笔测试订单记成已完成，好写评价
    sql_exec("update kg_stay_order set status='completed' where order_id=%d and status='confirmed'" % order_id)
    review_video_begin, review_video = upload_video(guest, mp4(500000), duration=18)
    review_cover = image(guest)
    status, b = http('POST', '/app/reviews', {'bizType': 'stay_order', 'bizId': order_id, 'dims': [5, 5, 4, 5, 5], 'content': '【卫生】很干净',
                                              'videos': ['https://evil.example.com/a.mp4']}, app_token=guest)
    check('M6 别处的视频 422（不落库）', 422, b.get('code'))
    status, b = http('POST', '/app/reviews', {'bizType': 'stay_order', 'bizId': order_id, 'dims': [5, 5, 4, 5, 5], 'content': '【卫生】很干净\n【安静舒适】晚上很安静',
                                              'videos': [{'url': review_video['url'], 'cover': review_cover}], 'anonymous': True}, app_token=guest)
    check('M6 带视频的评价提交成功', 200, b.get('code'))
    check('M6 库里 videos / video_covers', '%s|%s' % (review_video['url'], review_cover),
          scalar("select concat(videos, '|', video_covers) from kg_review where biz_type='stay_order' and biz_id=%d" % order_id))
    lst = data(http('GET', '/app/stay/%d/reviews?tab=media&pageNum=1&pageSize=10' % stay_id)[1])
    row = (lst.get('rows') or [{}])[0]
    check('M6 「有图·视频」页签里有它，videos[0] = {url, cover}', [{'url': review_video['url'], 'cover': review_cover}], row.get('videos'))
    summ = data(http('GET', '/app/stay/%d/reviews/summary' % stay_id)[1])
    check('M6 汇总的「有图·视频」条数算上只带视频的评价', 1, (summ.get('tabs') or {}).get('media'))
    status, headers, body = get_bytes(review_video['url'])
    check('M6 评价视频公开可播', 200, status)
    staff_reviews = data(http('GET', '/app/staff/reviews?storeId=%d' % store_id, app_token=manager)[1])
    srow = next((r for r in staff_reviews.get('rows') or [] if r.get('videos')), {})
    check('M6 员工端评价列表带 videos', review_video['url'], ((srow.get('videos') or [{}])[0]).get('url'))
    status, b = http('GET', '/kangaroo/ext/review/list?storeId=%d' % store_id, token=fx.admin)
    check('M6 后台评价列表带 videos 列', review_video['url'], ((b.get('rows') or [{}])[0]).get('videos'))

    section('M7 区县全称：选「宾」存「宾县」；简称 / 全称两种老数据都算同一个区县')
    sql_exec("update kg_stay set city='哈尔滨' where stay_id=%d" % stay_id)
    opts = data(http('GET', '/app/staff/stay/form-options?stayId=%d' % stay_id, app_token=manager)[1])
    check('M7 员工端区县选项给全称（宾县、不是宾）', (True, False), ('宾县' in (opts.get('districts') or []), '宾' in (opts.get('districts') or [])))
    status, b = http('PUT', '/app/staff/stay/stays/%d' % stay_id, {'district': '宾'}, app_token=manager)
    check('M7 上线中直接改区县；选的简称存成全称', (200, '宾县', 'online'), (b.get('code'),) + tuple(scalar("select concat(district, '|', status) from kg_stay where stay_id=%d" % stay_id).split('|')))
    d = data(http('GET', '/app/stay/%d' % stay_id)[1])
    check('M7 详情显示全称', '宾县', d.get('district'))
    sql_exec("update kg_stay set district='宾' where stay_id=%d" % stay_id)  # R2 时存进来的简称
    ds = data(http('GET', '/app/stay/districts?city=%s' % urllib.request.quote('哈尔滨'))[1])
    names = [x.get('name') for x in ds.get('districts') or []]
    check('M7 区县接口给全称，没有「宾」', (True, False), ('宾县' in names, '宾' in names))
    lst = data(http('GET', '/app/stay/list?city=%s&district=%s&pageSize=50' % (urllib.request.quote('哈尔滨'), urllib.request.quote('宾县')))[1])
    check('M7 按「宾县」筛，存简称的老房源也出来', True, any(r.get('stayId') == stay_id for r in lst.get('rows') or []))
    d = data(http('GET', '/app/stay/%d' % stay_id)[1])
    check('M7 存简称的老房源详情也显示全称', '宾县', d.get('district'))


def run():
    global FIXTURE
    with IsolatedStayFixture('media-' + RUN) as fx:
        FIXTURE = fx
        main(fx)
    print('\n' + '=' * 78)
    print('  PASS %d / FAIL %d' % (len(PASSED), len(FAILED)))
    for name in FAILED:
        print('  FAIL: ' + name)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(run())
