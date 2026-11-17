# -*- coding: utf-8 -*-
"""小院评论（V65）和采访视频去掉 60 秒下限的真实接口回归（2026-10-31）。全部在本机：一次性克隆库 + Redis db 14 + 自己挑的空闲端口，跑完关进程、删库。不连线上。

  1. 克隆验收库，补 V64，跑 V65。
  2. 老 jar（线上基线，不认识新表）在 V65 库上启动：能起、不碰新表（performance_schema 计数 = 0）。
  3. 新 jar + legacy + review-mode=mock：完整流程（提交、待审不公开、本人可见、通过后公开、驳回带理由、下架、限流、三种身份、只通知 keeper、举报、删除、注销、导出、权限）。
  4. 库里改成 new 阶段，新 jar + review-mode=auto：同一流程再跑一遍（换一家小院、一批新账号）。
  5. 老 jar 在有评论数据的库上再启动一次；有数据时回滚必须拒绝。

用法（项目根目录）：
    set KG_STAY_COMMENT_JAR=<新 jar：类补丁包，不带 V61>
    python -B -X utf8 RuoYi-Vue/e2e/test_stay_comment.py
可选：KG_STAY_COMMENT_OLD_JAR 老 jar；KG_STAY_COMMENT_SOURCE_DB 克隆来源（默认 kg_acceptance_admin_20261028）；KG_STAY_COMMENT_WORK 上传目录和日志放哪；KG_MYSQL mysql.exe。
"""
import io
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BE = ROOT / 'RuoYi-Vue'
sys.path.insert(0, str(Path(__file__).resolve().parent))
from dbcred import db_password  # noqa: E402
from migration_guard_policy import validate_guard_databases, authorized_rollback_prefix  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8')
MIG = BE / 'sql' / 'kangaroo'
SOURCE = os.environ.get('KG_STAY_COMMENT_SOURCE_DB', 'kg_acceptance_admin_20261028')
GUARD = 'acceptance_staycomment_' + uuid.uuid4().hex[:12] + '_guardtest'
validate_guard_databases(SOURCE, GUARD)
NEW_JAR = os.environ['KG_STAY_COMMENT_JAR']
OLD_JAR = os.environ.get('KG_STAY_COMMENT_OLD_JAR', str(ROOT / 'artifacts/release-20261030-role-switch/backend/ruoyi-admin.jar'))
WORK = Path(os.environ.get('KG_STAY_COMMENT_WORK') or tempfile.mkdtemp(prefix='kg-stay-comment-'))
WORK.mkdir(parents=True, exist_ok=True)
MYSQL = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
DUMP = str(Path(MYSQL).with_name('mysqldump.exe'))
PW = db_password(str(BE))
ENV = dict(os.environ, MYSQL_PWD=PW)
SECRET = secrets.token_urlsafe(64)  # 这次跑现生成，不落盘
ADMIN_HASH = '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2'  # 若依默认 admin123，只写进这个一次性克隆库
RUN = uuid.uuid4().hex[:6]
PNG = (ROOT / '袋鼠旅游2/static/media/cover-empty.png').read_bytes()
results, fails = [], []
PORT = None
PHONE = [13900000000 + (int(RUN, 16) % 10000000)]


def check(name, ok, detail=''):
    results.append(('ok   ' if ok else 'FAIL ') + name + ('' if ok else ('  <<< ' + str(detail)[:500])))
    if not ok:
        fails.append(name)


def sql(text, db=GUARD, tolerate=False):
    cmd = [MYSQL, '--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', '-n', '-N', '-B', '--raw']
    if db:
        cmd.append(db)
    # 和后端同一个时区：mysql 客户端默认用系统时区，now() 写进去的时间和应用的对不上
    r = subprocess.run(cmd, input=("set time_zone='+08:00';\n" + text).encode('utf-8'), capture_output=True, env=ENV, timeout=300)
    if r.returncode != 0:
        if tolerate:
            return None, r.stderr.decode('utf-8', 'replace')
        raise RuntimeError(r.stderr.decode('utf-8', 'replace')[:800] + ' :: ' + text[:300])
    out = r.stdout.decode('utf-8', 'replace').replace('\r', '').strip()
    return (out, '') if tolerate else out


def http(method, path, body=None, token=None, raw=None, headers=None):
    data = raw if raw is not None else (None if body is None else json.dumps(body).encode('utf-8'))
    req = urllib.request.Request('http://127.0.0.1:%d%s' % (PORT, path), method=method, data=data)
    if raw is None:
        req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-App-Token' if path.startswith('/app/') else 'Authorization', token if path.startswith('/app/') else 'Bearer ' + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            b = r.read()
    except urllib.error.HTTPError as e:
        b = e.read()
        try:
            return json.loads(b.decode('utf-8'))
        except Exception:
            return {'code': e.code}
    try:
        return json.loads(b.decode('utf-8'))
    except Exception:
        return {'code': 200, 'bytes': b}


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class Jar:
    """启动一个 jar，只停自己启动的进程"""

    def __init__(self, jar, label, review_mode):
        global PORT
        PORT = free_port()
        url = ('jdbc:mysql://127.0.0.1:3306/%s?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&allowPublicKeyRetrieval=true'
               '&serverTimezone=GMT%%2B8&sessionVariables=time_zone%%3D%%27%%2B08%%3A00%%27' % GUARD)
        self.log = open(WORK / ('backend-%s.log' % label), 'w', encoding='utf-8')
        self.proc = subprocess.Popen(['java', '-Dfile.encoding=UTF-8', '-Dkangaroo.wx.mock-enabled=true', '-Dserver.port=%d' % PORT, '-Dkangaroo.pay.mode=mock', '-Dkangaroo.content.review-mode=' + review_mode,
                                      '-Dkangaroo.sms.mode=mock', '-Dkangaroo.realname.mode=mock', '-Dkangaroo.subscribe.mode=mock', '-Dspring.datasource.druid.master.url=' + url,
                                      '-Dspring.redis.database=14', '-Druoyi.profile=' + str(WORK / 'profile'), '-jar', jar],
                                     cwd=str(WORK), stdout=self.log, stderr=subprocess.STDOUT, env=dict(os.environ, DB_USERNAME='root', DB_PASSWORD=PW, JWT_SECRET=SECRET, DRUID_USERNAME='guardtest', DRUID_PASSWORD=secrets.token_hex(8)))
        self.up = False
        for _ in range(240):
            try:
                if http('GET', '/app/auth/ping').get('code') == 200:
                    self.up = True
                    break
            except Exception:
                pass
            if self.proc.poll() is not None:
                break
            time.sleep(1)

    def stop(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(30)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.log.close()


def login(tag):
    b = http('POST', '/app/auth/wx-login', {'code': 'sc-%s-%s' % (RUN, tag)})
    assert b.get('code') == 200, b
    return b['token'], int(b['account']['id'])


def verified(uid, phone=None):
    if phone is None:
        PHONE[0] += 1
        phone = str(PHONE[0])
    sql("update kg_app_user set phone='%s', phone_verified_time=now() where user_id=%d" % (phone, uid))
    return phone


def consent(token):
    b = http('POST', '/app/community/consent', {'doc': 'community'}, token)
    assert b.get('code') == 200, b


def upload_image(token):
    boundary = 'x' + uuid.uuid4().hex
    body = (('--%s\r\nContent-Disposition: form-data; name="file"; filename="a.png"\r\nContent-Type: image/png\r\n\r\n' % boundary).encode() + PNG + ('\r\n--%s--\r\n' % boundary).encode())
    b = http('POST', '/app/upload/image', raw=body, token=token, headers={'Content-Type': 'multipart/form-data; boundary=' + boundary, 'X-App-Upload-Version': '2'})
    assert b.get('code') == 200, b
    return b['data']['url']


def mp4(size=4096):
    head = b'\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isommp41'
    return head + os.urandom(size - len(head))


def upload_video(token, duration=10, base='/app/upload/video/parts'):
    payload = mp4()
    b = http('POST', base, {'name': 'a.mp4', 'size': len(payload), 'duration': duration}, token)
    assert b.get('code') == 200, b
    uid = b['data']['uploadId']
    a = http('POST', '%s/%s/0' % (base, uid), raw=payload, token=token, headers={'Content-Type': 'application/octet-stream'})
    assert a.get('code') == 200, a
    f = http('POST', '%s/%s/finish' % (base, uid), token=token)
    assert f.get('code') == 200, f
    return f['data']['url']


def code(b):
    return b.get('code')


def public_ids(stay, token=None):
    d = http('GET', '/app/stay/%d/comments' % stay, token=token)['data']
    return [r['commentId'] for r in d['rows']]


def mine_ids(stay, token):
    return [r['commentId'] for r in http('GET', '/app/stay/%d/comments' % stay, token=token)['data']['mine']]


def msgs(uid, comment_id=None):
    where = "recipient_id=%d and biz_type='stay_comment'" % uid
    if comment_id:
        where += ' and biz_id=%d' % comment_id
    return sql("select mode, title, content, route from kg_message where %s and del_flag='0' order by msg_id" % where)


def scenario(label, stay, store, admin, expect_phase):
    def c(name, ok, detail=''):
        check('[%s] %s' % (label, name), ok, detail)

    def u(tag, phone=True, agree=True):
        t, i = login('%s-%s' % (label, tag))
        if phone:
            verified(i)
        if agree:
            consent(t)
        return t, i

    ping = http('GET', '/app/auth/ping')['data']
    c('ping 阶段 = %s' % expect_phase, ping.get('rolePhase') == expect_phase, ping)
    base_review = sql("select count(*), ifnull(sum(rating),0), ifnull(max(update_time),'') from kg_review")
    base_diary = sql("select count(*), ifnull(max(update_time),'') from kg_stay_diary")
    base_stay = sql("select * from kg_stay where stay_id=%d" % stay)
    base_points = sql("select count(*) from kg_point_entry")

    # ---- 门店的人（legacy 和 new 下都是这些授权，阶段只是库里一行）
    roles = {}
    for role in ('keeper', 'manager', 'staff', 'merchant', 'online_staff', 'city_lead', 'merchant_service'):
        roles[role] = login('%s-%s' % (label, role))
    keeper2 = login('%s-keeper2' % label)  # 同时带专员授权的小院主理人：消息也要看得到
    rows = ["(%d,%d,'%s','0','0','guardtest',now(),now())" % (i, store, r) for r, (_, i) in roles.items()]
    rows.append("(%d,%d,'keeper','0','0','guardtest',now(),now())" % (keeper2[1], store))
    rows.append("(%d,%s,'online_staff','0','0','guardtest',now(),now())" % (keeper2[1], sql("select store_id from kg_store where store_id<>%d and del_flag='0' order by store_id limit 1" % store)))
    sql('insert into kg_staff_grant (app_user_id, store_id, role_code, status, del_flag, granted_by, granted_time, create_time) values ' + ','.join(rows))

    # ---- 权限门槛
    tourist = http('GET', '/app/stay/%d/comments' % stay)
    c('游客能看列表', code(tourist) == 200 and tourist['data']['mine'] == [], tourist)
    c('游客不能写', code(http('POST', '/app/stay/%d/comments' % stay, {'content': 'x'})) == 401)
    c('游客不能看「我」', code(http('GET', '/app/stay/%d/comments/me' % stay)) == 401)
    t_new, i_new = u('nophone', phone=False, agree=False)
    me = http('GET', '/app/stay/%d/comments/me' % stay, token=t_new)['data']
    c('写前提示：没手机号没同意、未核实', me['phoneVerified'] is False and me['consented'] is False and me['identity'] == 'unverified' and me['identityText'] == '未核实' and me['pendingMax'] == 3, me)
    c('没验证手机号 428', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '你好'}, t_new)) == 428)
    verified(i_new)
    r = http('POST', '/app/stay/%d/comments' % stay, {'content': '你好'}, t_new)
    c('没同意公约 423 CONSENT', code(r) == 423 and str(r.get('msg')).startswith('CONSENT:community'), r)
    consent(t_new)
    c('小院不存在 404', code(http('POST', '/app/stay/99999999/comments', {'content': '你好'}, t_new)) == 404)

    # ---- 文字
    c('空文字 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '   '}, t_new)) == 422)
    for name, blank in (('全角空格', '\u3000\u3000'), ('零宽空格', '\u200b\u200b'), ('不换行空格', '\u00a0'), ('单独的表情变体符', '\ufe0f'), ('韩文填充符', '\u3164'), ('混着来', ' \u3000\u200b\n')):
        c('只有%s 422' % name, code(http('POST', '/app/stay/%d/comments' % stay, {'content': blank}, t_new)) == 422)
    c('501 字 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '好' * 501}, t_new)) == 422)
    c('命中拦截词 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '这里有拦截测试词哦'}, t_new)) == 422)
    long_ok = http('POST', '/app/stay/%d/comments' % stay, {'content': '好' * 500}, t_new)
    c('500 字能提交（带表情也存得下）', code(long_ok) == 200, long_ok)
    emoji = http('POST', '/app/stay/%d/comments' % stay, {'content': '院子真好😀🌅'}, t_new)
    c('表情存取不变', code(emoji) == 200 and emoji['data']['content'] == '院子真好😀🌅', emoji)

    # ---- 提交后：待审不公开、本人可见
    t_a, i_a = u('author')
    before = len(public_ids(stay))
    sub = http('POST', '/app/stay/%d/comments' % stay, {'content': '早餐很好吃，老板人很好', 'status': 'online', 'sourceType': 'guest'}, t_a)
    cid = sub['data']['commentId']
    c('提交成功，提示等审核', code(sub) == 200 and sub['msg'] == '已提交，审核通过后展示' and sub['data']['status'] == 'pending' and sub['data']['statusText'] == '审核中', sub)
    c('客户端传 status、sourceType 无效', sql('select status, source_type from kg_stay_comment where comment_id=%d' % cid) == 'pending\tunverified')
    c('待审不公开：游客看不到', cid not in public_ids(stay))
    t_x, i_x = u('stranger')
    c('待审不公开：别人登录也看不到', cid not in public_ids(stay, t_x) and cid not in mine_ids(stay, t_x))
    d = http('GET', '/app/stay/%d/comments' % stay, token=t_a)['data']
    c('本人在 mine 里看得到（rows 里没有）', cid in [x['commentId'] for x in d['mine']] and cid not in [x['commentId'] for x in d['rows']], d)
    c('总数 N 不算待审', d['total'] == before, d['total'])
    c('本人第二页没有 mine', http('GET', '/app/stay/%d/comments?page=2' % stay, token=t_a)['data']['mine'] == [])
    c('待审内容不发给门店的任何人', all(msgs(i) == '' for (_, i) in roles.values()) and msgs(keeper2[1]) == '')
    c('待审时本人也没有通知', msgs(i_a) == '')
    c('待审内容不进评价数', sql("select count(*) from kg_review where target_type='room' and target_id=%d and content like '%%早餐很好吃%%'" % stay) == '0')

    # ---- 联系方式：仍进待审，后台标出
    contact = http('POST', '/app/stay/%d/comments' % stay, {'content': '有事加我微信 wxabc12345'}, t_a)
    c('含联系方式仍进待审', code(contact) == 200 and contact['data']['status'] == 'pending', contact)
    contact_id = contact['data']['commentId']
    c('含联系方式：后台标 contact', sql('select risk_flag from kg_stay_comment where comment_id=%d' % contact_id) == 'contact')
    word = http('POST', '/app/stay/%d/comments' % stay, {'content': '说到了复核测试词'}, t_x)
    c('命中复核词：标 word', code(word) == 200 and sql('select risk_flag from kg_stay_comment where comment_id=%d' % word['data']['commentId']) == 'word', word)

    # ---- 媒体
    t_m, i_m = u('media')
    img = upload_image(t_m)
    img2 = upload_image(t_m)
    cover = upload_image(t_m)
    vid = upload_video(t_m)
    c('带图没确认肖像 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '图', 'images': [img]}, t_m)) == 422)
    c('带视频没确认肖像 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '视频', 'videos': [{'url': vid, 'cover': cover}]}, t_m)) == 422)
    c('图和视频都带 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '都', 'images': [img], 'videos': [{'url': vid}], 'portraitConfirmed': True}, t_m)) == 422)
    c('十张图 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '多', 'images': [img.replace('.', '%d.' % i) for i in range(10)], 'portraitConfirmed': True}, t_m)) == 422)
    c('别处的网址 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '外', 'images': ['https://evil.example/a.jpg'], 'portraitConfirmed': True}, t_m)) == 422)
    c('文件不存在的图片 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '无', 'images': [img.replace('.', 'x.')], 'portraitConfirmed': True}, t_m)) == 422)
    c('把视频路径当图片 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '错', 'images': [vid], 'portraitConfirmed': True}, t_m)) == 422)
    c('把图片路径当视频 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '错', 'videos': [{'url': img}], 'portraitConfirmed': True}, t_m)) == 422)
    with_img = http('POST', '/app/stay/%d/comments' % stay, {'content': '带图', 'images': [img, img2], 'portraitConfirmed': True}, t_m)
    c('带两张图提交成功', code(with_img) == 200 and with_img['data']['images'] == [img, img2] and with_img['data']['video'] is None, with_img)
    c('作者确认肖像落库', sql('select portrait_confirmed from kg_stay_comment where comment_id=%d' % with_img['data']['commentId']) == '1')
    with_vid = http('POST', '/app/stay/%d/comments' % stay, {'content': '带视频', 'videos': [{'url': vid, 'cover': cover}], 'portraitConfirmed': True}, t_m)
    c('带一段视频提交成功', code(with_vid) == 200 and with_vid['data']['video'] == {'url': vid, 'cover': cover} and with_vid['data']['images'] == [], with_vid)
    c('别人不能引用已用过的图片', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '盗', 'images': [img], 'portraitConfirmed': True}, t_x)) == 422)
    c('别人不能引用已用过的视频', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '盗', 'videos': [{'url': vid}], 'portraitConfirmed': True}, t_x)) == 422)
    c('别人不能引用已用过的封面', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '盗', 'images': [cover], 'portraitConfirmed': True}, t_x)) == 422)
    old = '/profile/upload/2020/01/01/old.png'
    os.makedirs(WORK / 'profile/upload/2020/01/01', exist_ok=True)
    (WORK / 'profile/upload/2020/01/01/old.png').write_bytes(PNG)
    c('7 天前上传的路径 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '老', 'images': [old], 'portraitConfirmed': True}, t_x)) == 422)
    (WORK / 'profile/upload/legacy-nodate.png').write_bytes(PNG)
    c('路径没有日期的图片 422', code(http('POST', '/app/stay/%d/comments' % stay, {'content': '老', 'images': ['/profile/upload/legacy-nodate.png'], 'portraitConfirmed': True}, t_x)) == 422)
    # 已知限制（写明）：待审视频文件其实能直接访问，只是路径不可猜
    raw_vid = http('GET', vid)
    c('已知限制：待审视频文件可以直接取到（路径不可猜）', raw_vid.get('bytes') is not None and len(raw_vid['bytes']) == 4096, raw_vid.get('code'))

    # ---- 三种身份
    room = sql("select min(room_type_id) from kg_stay_room_type where stay_id=%d and status='0' and del_flag='0'" % stay)

    def order(uid, status, phone='13000000000'):
        no = 'SC' + uuid.uuid4().hex[:18]
        sql("insert into kg_stay_order (order_no,stay_id,store_id,room_type_id,app_user_id,check_in,check_out,nights,guest_name,guest_phone,pay_fen,status,create_time) "
            "values ('%s',%d,%d,%s,%d,'2026-10-01','2026-10-02',1,'测','%s',100,'%s',now())" % (no, stay, store, room, uid, phone, status))
        return int(sql("select order_id from kg_stay_order where order_no='%s'" % no))

    def application(uid, job, status, app_store):
        no = 'SC' + uuid.uuid4().hex[:18]
        sql("insert into kg_application (app_no, job_id, store_id, app_user_id, real_name, phone, status, del_flag) values ('%s',%d,%d,%d,'测','13000000001','%s','0')" % (no, job, app_store, uid, status))
        return int(sql("select app_id from kg_application where app_no='%s'" % no))

    t, i = u('g-own')
    oid = order(i, 'checked_in')
    guest_own = http('POST', '/app/stay/%d/comments' % stay, {'content': '住客本人（在住）'}, t)
    c('身份：本人下单 checked_in = 已核实住客', guest_own['data']['identity'] == 'guest' and guest_own['data']['identityText'] == '已核实住客', guest_own)
    c('身份依据：source_id 是这个订单', sql('select source_type, source_id from kg_stay_comment where comment_id=%d' % guest_own['data']['commentId']) == 'guest\t%d' % oid)
    t, i = u('g-done')
    order(i, 'completed')
    c('身份：本人下单 completed = 已核实住客', http('POST', '/app/stay/%d/comments' % stay, {'content': '离店的'}, t)['data']['identity'] == 'guest')
    t, i = u('g-phone', phone=False)
    p = verified(i)
    other_buyer = login('%s-buyer' % label)[1]
    order(other_buyer, 'completed', p)
    t_pre = http('GET', '/app/stay/%d/comments/me' % stay, token=t)['data']
    c('写前提示：guest_phone = 本人已验证手机号 = 已核实住客', t_pre['identity'] == 'guest' and t_pre['identityText'] == '已核实住客', t_pre)
    guest_phone = http('POST', '/app/stay/%d/comments' % stay, {'content': '别人给我订的'}, t)
    c('身份：入住人手机号 = 本人已验证手机号 = 已核实住客', guest_phone['data']['identity'] == 'guest', guest_phone)
    t, i = u('g-confirmed')
    order(i, 'confirmed')
    c('身份：订单只是 confirmed（没核销）= 未核实，但能写', http('POST', '/app/stay/%d/comments' % stay, {'content': '付了没核销'}, t)['data']['identity'] == 'unverified')
    t, i = u('g-otherphone', phone=False)
    verified(i)
    other = login('%s-buyer2' % label)[1]
    order(other, 'completed', '13900009999')
    c('身份：别人的订单、手机号也不同 = 未核实', http('POST', '/app/stay/%d/comments' % stay, {'content': '路过'}, t)['data']['identity'] == 'unverified')

    # 义工：招募门店 = 小院门店才算。岗位是克隆库里的，改招募门店（一次性库）
    jobs = sql("select job_id from kg_job where job_type<>'online' and del_flag='0' order by job_id limit 2").split('\n')
    online_job = sql("select job_id from kg_job where job_type='online' order by job_id limit 1")
    job_here, job_there = int(jobs[0]), int(jobs[1])
    sql('update kg_job set recruit_store_id=%d where job_id=%d' % (store, job_here))
    sql('update kg_job set recruit_store_id=99999 where job_id=%d' % job_there)
    sql('update kg_job set recruit_store_id=%d where job_id=%s' % (store, online_job))
    t, i = u('v-on')
    aid = application(i, job_here, 'onboarded', 99999)
    vol = http('POST', '/app/stay/%d/comments' % stay, {'content': '在这里做义工'}, t)
    c('身份：招募门店是这家、onboarded = 已核实义工（a.store_id 不同也算）', vol['data']['identity'] == 'volunteer' and vol['data']['identityText'] == '已核实义工', vol)
    c('身份依据：source_id 是这条报名', sql('select source_type, source_id from kg_stay_comment where comment_id=%d' % vol['data']['commentId']) == 'volunteer\t%d' % aid)
    t, i = u('v-done')
    application(i, job_here, 'completed', store)
    c('身份：completed = 已核实义工', http('POST', '/app/stay/%d/comments' % stay, {'content': '做完了'}, t)['data']['identity'] == 'volunteer')
    t, i = u('v-online')
    application(i, int(online_job), 'onboarded', store)
    c('身份：线上任务不算义工 = 未核实', http('POST', '/app/stay/%d/comments' % stay, {'content': '线上的'}, t)['data']['identity'] == 'unverified')
    t, i = u('v-apply')
    application(i, job_here, 'submitted', store)
    c('身份：只是报名没入职 = 未核实', http('POST', '/app/stay/%d/comments' % stay, {'content': '报了名'}, t)['data']['identity'] == 'unverified')
    t, i = u('v-other')
    application(i, job_there, 'onboarded', store)
    c('身份：招募门店是别家（a.store_id 是这家）= 未核实', http('POST', '/app/stay/%d/comments' % stay, {'content': '别家的'}, t)['data']['identity'] == 'unverified')
    t, i = u('both')
    order(i, 'completed')
    application(i, job_here, 'onboarded', store)
    c('身份：既住过又做过义工，按住客', http('POST', '/app/stay/%d/comments' % stay, {'content': '都有'}, t)['data']['identity'] == 'guest')

    # ---- 通过
    keeper_tok, keeper_id = roles['keeper']
    c('还没通过时游客看不到', cid not in public_ids(stay))
    r = http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % cid, token=admin)
    c('后台通过', code(r) == 200, r)
    d = http('GET', '/app/stay/%d/comments' % stay)['data']
    row = next((x for x in d['rows'] if x['commentId'] == cid), None)
    c('通过后公开：游客看得到', row is not None, d['rows'][:2])
    c('公开卡片：脱敏昵称、未核实标签、没有内部字段', row and row['name'].endswith('**') and row['identityText'] == '未核实' and row['canReport'] is False and 'riskFlag' not in row and 'appUserId' not in row, row)
    c('总数 N 加 1', d['total'] == before + 1, d['total'])
    mine_view = next(x for x in http('GET', '/app/stay/%d/comments' % stay, token=t_a)['data']['rows'] if x['commentId'] == cid)
    c('本人看到完整昵称、isMe', mine_view['isMe'] is True and not mine_view['name'].endswith('**'), mine_view)
    other_view = next(x for x in http('GET', '/app/stay/%d/comments' % stay, token=t_x)['data']['rows'] if x['commentId'] == cid)
    c('别人登录看到可举报', other_view['canReport'] is True and other_view['isMe'] is False, other_view)
    route = '/pages/stays/diary?stayId=%d&tab=comment' % stay
    author_msg = msgs(i_a, cid)
    c('通过：本人收到站内信，跳 diary 带参数', author_msg.startswith('user\t你的小院评论已通过审核') and author_msg.endswith(route), author_msg)
    keeper_msg = msgs(keeper_id, cid)
    c('通过：小院主理人收到（staff 身份），跳 diary 带参数', keeper_msg.startswith('staff\t小院有新评论') and keeper_msg.endswith(route), keeper_msg)
    c('通过：同时带专员授权的小院主理人也收到', msgs(keeper2[1], cid).startswith('staff\t'))
    for role in ('manager', 'staff', 'merchant', 'online_staff', 'city_lead', 'merchant_service'):
        c('通过：%s 收不到' % role, msgs(roles[role][1]) == '', msgs(roles[role][1]))
    inbox = http('GET', '/app/message/list?mode=staff', token=keeper_tok)
    c('小院主理人消息列表里看得到', any(str(m.get('bizType')) == 'stay_comment' and m.get('route') == route for m in inbox.get('rows', [])), inbox)
    # 账号带 online_staff 授权的员工消息默认按业务收窄：这条要放行，否则红点清不掉
    keeper2_fresh = login('%s-keeper2' % label)
    inbox2 = http('GET', '/app/message/list?mode=staff', token=keeper2_fresh[0])
    c('带专员授权的小院主理人消息列表里也看得到', any(str(m.get('bizType')) == 'stay_comment' for m in inbox2.get('rows', [])), inbox2)
    c('已通过的不能再通过 409', code(http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % cid, token=admin)) == 409)
    c('已通过的不能驳回 409', code(http('PUT', '/kangaroo/stayops/staycomment/%d/reject' % cid, {'reason': 'x'}, admin)) == 409)

    # ---- 驳回
    c('待审不能下架 409', code(http('PUT', '/kangaroo/stayops/staycomment/%d/offline' % contact_id, {'reason': 'x'}, admin)) == 409)
    c('驳回没理由 422', code(http('PUT', '/kangaroo/stayops/staycomment/%d/reject' % contact_id, {'reason': '  '}, admin)) == 422)
    r = http('PUT', '/kangaroo/stayops/staycomment/%d/reject' % contact_id, {'reason': '不能留联系方式'}, admin)
    c('后台驳回', code(r) == 200, r)
    c('驳回后不公开', contact_id not in public_ids(stay))
    card = next(x for x in http('GET', '/app/stay/%d/comments' % stay, token=t_a)['data']['mine'] if x['commentId'] == contact_id)
    c('驳回：本人卡片带理由', card['status'] == 'rejected' and card['statusText'] == '未通过' and card['auditReason'] == '不能留联系方式', card)
    m = msgs(i_a, contact_id)
    c('驳回：站内信带理由，只发本人', m.startswith('user\t你的小院评论未通过审核') and '不能留联系方式' in m and msgs(keeper_id, contact_id) == '', m)
    c('驳回：站内信不引用评论原文（后台消息管理读得到正文）', 'wxabc12345' not in m, m)
    c('审核人和时间落库', sql('select audit_by, audit_time is not null from kg_stay_comment where comment_id=%d' % contact_id) == 'admin\t1')

    # ---- 下架
    c('下架没理由 422', code(http('PUT', '/kangaroo/stayops/staycomment/%d/offline' % cid, {}, admin)) == 422)
    r = http('PUT', '/kangaroo/stayops/staycomment/%d/offline' % cid, {'reason': '内容不实'}, admin)
    c('后台下架', code(r) == 200, r)
    c('下架后不公开', cid not in public_ids(stay))
    card = next(x for x in http('GET', '/app/stay/%d/comments' % stay, token=t_a)['data']['mine'] if x['commentId'] == cid)
    c('下架：本人卡片带理由', card['status'] == 'offline' and card['auditReason'] == '内容不实', card)
    m = msgs(i_a, cid)
    c('下架：站内信带理由', '你的小院评论已被下架' in m and '内容不实' in m, m)
    words = sql('select content from kg_stay_comment where comment_id=%d' % cid)[:12]
    c('下架：作者那几条站内信不再引用原文', words not in m, m)
    c('下架：小院主理人那条收回了', msgs(keeper_id, cid) == '' and msgs(keeper2[1], cid) == '', msgs(keeper_id, cid))
    c('下架：小院主理人消息列表、库里的正文都没有原文', words not in json.dumps(http('GET', '/app/message/list?mode=staff', token=keeper_tok), ensure_ascii=False)
      and words not in sql("select concat_ws('|', title, content, ifnull(route, '')) from kg_message where biz_type='stay_comment' and biz_id=%d" % cid), sql("select concat_ws('|', title, content, del_flag) from kg_message where biz_type='stay_comment' and biz_id=%d and mode='staff'" % cid))
    keeper_book = http('GET', '/app/me/export.xlsx', token=keeper_tok)
    c('下架：小院主理人的数据导出里没有原文', keeper_book.get('bytes') and words not in zipfile.ZipFile(io.BytesIO(keeper_book['bytes'])).read('xl/sharedStrings.xml').decode('utf-8'))
    c('下架：后台消息管理查不到原文', words not in json.dumps(http('GET', '/kangaroo/message/list?bizType=stay_comment&pageSize=100', token=admin), ensure_ascii=False))
    c('不存在的评论 404', code(http('PUT', '/kangaroo/stayops/staycomment/99999999/approve', token=admin)) in (404, 500), 'must 的错误码按现有 must')

    # ---- 限流
    t_l, i_l = u('limit')
    first = [http('POST', '/app/stay/%d/comments' % stay, {'content': '第%d条' % n}, t_l) for n in range(3)]
    c('每院 3 条待审都能提交', all(code(x) == 200 for x in first), first)
    r = http('POST', '/app/stay/%d/comments' % stay, {'content': '第4条'}, t_l)
    c('第 4 条待审 429', code(r) == 429, r)
    me = http('GET', '/app/stay/%d/comments/me' % stay, token=t_l)['data']
    c('写前提示：待审 3 / 3', me['pending'] == 3 and me['pendingMax'] == 3, me)
    r = http('DELETE', '/app/stay/comments/%d' % first[0]['data']['commentId'], token=t_l)
    c('删掉一条待审', code(r) == 200, r)
    r = http('POST', '/app/stay/%d/comments' % stay, {'content': '删了再写'}, t_l)
    c('删掉之后能再写', code(r) == 200, r)
    # 每小时 10 条：把这个账号已有的算上，补到 9 条（删除的也算），第 10 条成功、第 11 条拒绝。放在别家小院写，避开每院 3 条
    written = int(sql('select count(*) from kg_stay_comment where app_user_id=%d' % i_l))
    sql("insert into kg_stay_comment (stay_id, store_id, app_user_id, content, status, del_flag, create_time) select %d, %d, %d, '补', 'pending', '2', now() from kg_store limit %d" % (stay, store, i_l, 9 - written))
    other_stay = int(sql("select s.stay_id from kg_stay s where s.status='online' and s.del_flag='0' and s.stay_id<>%d order by s.stay_id desc limit 1" % stay))
    r = http('POST', '/app/stay/%d/comments' % other_stay, {'content': '第10条'}, t_l)
    c('一小时第 10 条能写', code(r) == 200, r)
    r = http('POST', '/app/stay/%d/comments' % other_stay, {'content': '第11条'}, t_l)
    c('一小时第 11 条 429（删除的也算）', code(r) == 429 and '一小时' in r.get('msg', ''), r)
    sql("update kg_stay_comment set create_time = date_sub(now(), interval 2 hour) where app_user_id=%d and content='补'" % i_l)
    r = http('POST', '/app/stay/%d/comments' % other_stay, {'content': '过了一小时'}, t_l)
    c('一小时窗口过了又能写', code(r) == 200, r)

    # ---- 删除
    del_id = with_img['data']['commentId']
    c('别人删不了 404', code(http('DELETE', '/app/stay/comments/%d' % del_id, token=t_x)) == 404)
    c('本人删除', code(http('DELETE', '/app/stay/comments/%d' % del_id, token=t_m)) == 200)
    c('删除后本人也看不到', del_id not in mine_ids(stay, t_m))
    c('再删 404', code(http('DELETE', '/app/stay/comments/%d' % del_id, token=t_m)) == 404)
    c('已删除的不能审核', code(http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % del_id, token=admin)) != 200)
    c('删除是软删', sql('select del_flag from kg_stay_comment where comment_id=%d' % del_id) == '2')
    t_w, i_w = u('willdel')
    gone = http('POST', '/app/stay/%d/comments' % stay, {'content': '通过以后作者又删了WILLDEL'}, t_w)['data']['commentId']
    http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % gone, token=admin)
    c('通过后小院主理人收到这条', 'WILLDEL' in msgs(keeper_id, gone), msgs(keeper_id, gone))
    unread_before = http('GET', '/app/message/unread?mode=staff', token=keeper_tok)
    c('作者删除', code(http('DELETE', '/app/stay/comments/%d' % gone, token=t_w)) == 200)
    c('作者删除：小院主理人那条收回了', msgs(keeper_id, gone) == '' and 'WILLDEL' not in json.dumps(http('GET', '/app/message/list?mode=staff', token=keeper_tok), ensure_ascii=False), msgs(keeper_id, gone))
    c('作者删除：库里所有站内信都没有原文', sql("select count(*) from kg_message where biz_type='stay_comment' and biz_id=%d and concat(title, content) like '%%WILLDEL%%'" % gone) == '0')
    c('作者删除：作者自己那条留着，正文已换', msgs(i_w, gone).startswith('user\t你的小院评论已通过审核\t这条评论已删除'), msgs(i_w, gone))
    unread_after = http('GET', '/app/message/unread?mode=staff', token=keeper_tok)
    c('作者删除：小院主理人的未读少一条（红点不会挂着一条打不开的）', code(unread_before) == 200 and code(unread_after) == 200 and unread_after['data']['unread'] == unread_before['data']['unread'] - 1, (unread_before, unread_after))

    # ---- 封禁
    t_b, i_b = u('banned')
    sql("update kg_app_user set status='1' where user_id=%d" % i_b)
    r = http('POST', '/app/stay/%d/comments' % stay, {'content': '封禁了'}, t_b)
    c('封禁账号不能写', code(r) in (401, 403, 409), r)

    # ---- 举报
    rid = vol['data']['commentId']
    http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % rid, token=admin)
    c('游客不能举报', code(http('POST', '/app/stay/comments/%d/report' % rid, {})) == 401)
    r = http('POST', '/app/stay/comments/%d/report' % rid, {'reason': '虚假', 'detail': '说明'}, t_x)
    c('举报成功', code(r) == 200 and '已提交' in r.get('msg', ''), r)
    r = http('POST', '/app/stay/comments/%d/report' % rid, {}, t_x)
    c('重复举报不重复记', code(r) == 200 and '已经举报过' in r.get('msg', '') and sql('select reported from kg_stay_comment where comment_id=%d' % rid) == '1', r)
    c('举报自己 422', code(http('POST', '/app/stay/comments/%d/report' % with_vid['data']['commentId'], {}, t_m)) in (404, 422))
    pending_id = word['data']['commentId']
    c('没公开的评论不能举报 404', code(http('POST', '/app/stay/comments/%d/report' % pending_id, {}, t_m)) == 404)
    report_id = int(sql("select report_id from kg_report where target_type='stay_comment' and target_id=%d" % rid))
    ctx = http('GET', '/kangaroo/core/report/%d/context' % report_id, token=admin)
    c('后台「查看原文」能看到评论', code(ctx) == 200 and '在这里做义工' in ctx['data']['reviewContext']['text'], ctx)
    r = http('PUT', '/kangaroo/core/report/%d/uphold' % report_id, {'reason': '核实'}, admin)
    c('举报成立', code(r) == 200 and '下架' in str(r.get('msg')), r)
    c('举报成立后不公开', rid not in public_ids(stay))
    c('举报成立：状态 offline、理由落库', sql('select status, audit_reason from kg_stay_comment where comment_id=%d' % rid) == 'offline\t举报成立，平台下架')

    # ---- 注销和导出
    t_d, i_d = u('erase')
    online_row = http('POST', '/app/stay/%d/comments' % stay, {'content': '我要注销了'}, t_d)['data']['commentId']
    http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % online_row, token=admin)
    c('注销前：小院主理人收到通过通知', '我要注销了' in msgs(keeper_id, online_row), msgs(keeper_id, online_row))
    t_o, _ = u('bystander')
    someone_else = http('POST', '/app/stay/%d/comments' % stay, {'content': '别人写的还留着'}, t_o)['data']['commentId']
    http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % someone_else, token=admin)
    pending_row = http('POST', '/app/stay/%d/comments' % stay, {'content': '还在审'}, t_d)['data']['commentId']
    x = http('GET', '/app/me/export.xlsx', token=t_d)
    names = zipfile.ZipFile(io.BytesIO(x['bytes'])).read('xl/workbook.xml').decode('utf-8') if x.get('bytes') else ''
    book = zipfile.ZipFile(io.BytesIO(x['bytes'])).read('xl/sharedStrings.xml').decode('utf-8') if x.get('bytes') else ''
    c('数据导出有「小院评论」表和内容', '小院评论' in names and '我要注销了' in book, names[:200])
    nick = sql('select nickname from kg_app_user where user_id=%d' % i_d)
    r = http('POST', '/app/me/deactivate', {'confirm': '确认注销', 'reason': '测试'}, t_d)
    c('注销成功', code(r) == 200, r)
    c('注销：已公开的转匿名', sql('select anonymous, del_flag, status from kg_stay_comment where comment_id=%d' % online_row) == '1\t0\tonline')
    c('注销：待审的不留', sql('select del_flag from kg_stay_comment where comment_id=%d' % pending_row) == '2')
    c('注销：小院主理人那条抹掉了', msgs(keeper_id, online_row) == '' and '我要注销了' not in json.dumps(http('GET', '/app/message/list?mode=staff', token=keeper_tok), ensure_ascii=False), msgs(keeper_id, online_row))
    c('注销：库里小院主理人那条没有原文', sql("select count(*) from kg_message where biz_type='stay_comment' and biz_id=%d and mode='staff' and concat(title, content) like '%%我要注销了%%'" % online_row) == '0')
    c('注销：别人写的评论的通知不受影响', '别人写的还留着' in msgs(keeper_id, someone_else), msgs(keeper_id, someone_else))
    row = next(x for x in http('GET', '/app/stay/%d/comments' % stay)['data']['rows'] if x['commentId'] == online_row)
    c('注销后读者看到匿名', row['name'] == '已注销用户' and row['avatar'] == '' and nick not in json.dumps(row, ensure_ascii=False), row)

    # ---- 拉黑
    t_bl, i_bl = u('blocker')
    sql('insert into kg_user_block (user_id, blocked_id, create_time) values (%d, %d, now())' % (i_bl, i_a))
    http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % guest_own['data']['commentId'], token=admin)
    book_row = http('POST', '/app/stay/%d/comments' % stay, {'content': '被拉黑的人写的'}, t_a)['data']['commentId']
    http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % book_row, token=admin)
    c('拉黑的人的评论不显示给我', book_row not in public_ids(stay, t_bl) and book_row in public_ids(stay))

    # ---- 后台列表和权限
    lst = http('GET', '/kangaroo/ext/staycomment/list?stayId=%d&pageNum=1&pageSize=10' % stay, token=admin)
    c('后台列表：待审排最前', code(lst) == 200 and lst['rows'][0]['status'] == 'pending', lst)
    c('后台列表：能看身份、风险、媒体', all(k in lst['rows'][0] for k in ('sourceType', 'riskFlag', 'images', 'videoUrl', 'portraitConfirmed', 'stayName', 'appUserId', 'content')), lst['rows'][0])
    lst = http('GET', '/kangaroo/ext/staycomment/list?riskFlag=word&stayId=%d' % stay, token=admin)
    c('后台列表：按风险筛', code(lst) == 200 and lst['total'] >= 1 and all(x['riskFlag'] == 'word' for x in lst['rows']), lst)
    c('后台不能新增', code(http('POST', '/kangaroo/ext/staycomment', {'content': 'x', 'stayId': stay}, admin)) != 200)
    c('后台不能改', code(http('PUT', '/kangaroo/ext/staycomment', {'commentId': cid, 'status': 'online'}, admin)) != 200)
    c('后台不能删', code(http('DELETE', '/kangaroo/ext/staycomment/%d' % cid, token=admin)) != 200)
    c('小程序 token 调不了审核', code(http('PUT', '/kangaroo/stayops/staycomment/%d/approve' % pending_id, token=t_a)) in (401, 403))

    # ---- 家人评价、碎片、积分、小院评分都没动
    c('kg_review 一行没动', sql("select count(*), ifnull(sum(rating),0), ifnull(max(update_time),'') from kg_review") == base_review)
    c('kg_stay_diary 一行没动', sql("select count(*), ifnull(max(update_time),'') from kg_stay_diary") == base_diary)
    c('小院那行没动（综合分等）', sql("select * from kg_stay where stay_id=%d" % stay) == base_stay)
    c('积分流水没增加（基线 %s）' % base_points, sql("select count(*) from kg_point_entry") == base_points)
    d = http('GET', '/app/stay/%d/reviews/summary' % stay)
    c('家人评价汇总接口照常', code(d) == 200, d)
    d = http('GET', '/app/stay/%d/diary' % stay)
    c('日记接口照常，返回没变', code(d) == 200 and 'header' in d['data'], d)

    # ---- 分页
    sql("insert into kg_stay_comment (stay_id, store_id, app_user_id, content, status, create_time) select %d, %d, %d, concat('分页', user_id), 'online', now() from kg_app_user limit 25" % (stay, store, i_x))
    p1 = http('GET', '/app/stay/%d/comments?page=1' % stay)['data']
    p2 = http('GET', '/app/stay/%d/comments?page=2' % stay)['data']
    c('每页 20 条，hasMore 对', len(p1['rows']) == 20 and p1['hasMore'] is True and p1['total'] >= 25 and p2['pageNum'] == 2 and not (set(x['commentId'] for x in p1['rows']) & set(x['commentId'] for x in p2['rows'])), (len(p1['rows']), p1['total']))
    c('页码 0 报 422', code(http('GET', '/app/stay/%d/comments?page=0' % stay)) == 422)


def interview_checks(admin, store):
    """去掉采访视频 60 秒下限（后台接口）"""
    base = '/kangaroo/store/profile/video/parts'
    for seconds, want in ((0.5, True), (5, True), (59.99, True), (60, True), (3600, True), (0, False), (-1, False), ('bad', False), (None, False)):
        b = http('POST', base, {'name': 'a.mp4', 'size': 1000, 'duration': seconds}, admin)
        if want:
            check('采访视频 %s 秒能开始上传' % seconds, code(b) == 200, b)
            if code(b) == 200:
                http('DELETE', '%s/%s' % (base, b['data']['uploadId']), token=admin)
        else:
            check('采访视频 %s 秒被拒，提示说秒数' % seconds, code(b) == 422 and '秒数' in b.get('msg', ''), b)
    b = http('POST', base, {'name': 'a.mp4', 'size': 300 * 1024 * 1024 + 1, 'duration': 5}, admin)
    check('采访视频上限 300MB 不变', code(b) == 422 and '300MB' in b.get('msg', ''), b)
    url = upload_video(admin, 5, base)
    b = http('PUT', '/kangaroo/store/profile/%d' % store, {'interviewVideo': url, 'interviewSeconds': 5, 'interviewMode': 'onsite'}, admin)
    check('门店资料：5 秒的采访视频能保存', code(b) == 200, b)
    check('门店资料：秒数落库', sql('select interview_seconds from kg_store where store_id=%d' % store) == '5')
    b = http('PUT', '/kangaroo/store/profile/%d' % store, {'interviewSeconds': 0.4}, admin)
    check('门店资料：不足 1 秒按 1 秒存', code(b) == 200 and sql('select interview_seconds from kg_store where store_id=%d' % store) == '1', b)
    b = http('PUT', '/kangaroo/store/profile/%d' % store, {'interviewSeconds': 0}, admin)
    check('门店资料：0 秒被拒', code(b) == 422, b)
    other = upload_video(admin, 7, base)
    for name, body in (('不带秒数', {'interviewVideo': other}), ('秒数 null', {'interviewVideo': other, 'interviewSeconds': None}), ('秒数空串', {'interviewVideo': other, 'interviewSeconds': ''})):
        b = http('PUT', '/kangaroo/store/profile/%d' % store, body, admin)
        check('门店资料：换视频%s被拒，库不变' % name, code(b) == 422 and '秒数' in b.get('msg', '') and sql('select interview_video, interview_seconds from kg_store where store_id=%d' % store) == url + '\t1', b)
    b = http('PUT', '/kangaroo/store/profile/%d' % store, {'interviewSeconds': None}, admin)
    check('门店资料：有视频时不能把秒数清空', code(b) == 422 and sql('select interview_seconds from kg_store where store_id=%d' % store) == '1', b)
    b = http('PUT', '/kangaroo/store/profile/%d' % store, {'interviewVideo': other, 'interviewSeconds': 7}, admin)
    check('门店资料：换视频带秒数能存', code(b) == 200 and sql('select interview_video, interview_seconds from kg_store where store_id=%d' % store) == other + '\t7', b)
    sql('update kg_store set interview_seconds = null where store_id=%d' % store)
    b = http('PUT', '/kangaroo/store/profile/%d' % store, {'interviewVideo': other, 'interviewSeconds': None, 'managerName': '老数据'}, admin)
    check('门店资料：老数据没存时长，视频没换，只改别的项能存', code(b) == 200 and sql('select manager_name from kg_store where store_id=%d' % store) == '老数据', b)
    b = http('PUT', '/kangaroo/store/profile/%d' % store, {'interviewVideo': '', 'interviewSeconds': None}, admin)
    check('门店资料：去掉视频（空视频 + 空秒数）能存', code(b) == 200 and sql("select concat('[', ifnull(interview_video, ''), ']', ifnull(interview_seconds, 'NULL')) from kg_store where store_id=%d" % store) == '[]NULL', b)


jar = None
created = False
try:
    sql('create database %s character set utf8mb4 collate utf8mb4_unicode_ci' % GUARD, db=None)
    created = True
    dump = subprocess.run([DUMP, '--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', '--single-transaction', '--routines', '--no-tablespaces', '--set-gtid-purged=OFF', '--skip-triggers', '--hex-blob', SOURCE], capture_output=True, env=ENV, timeout=300)
    assert dump.returncode == 0, dump.stderr[:500]
    r = subprocess.run([MYSQL, '--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', GUARD], input=dump.stdout, capture_output=True, env=ENV, timeout=600)
    assert r.returncode == 0, r.stderr[:500]
    if sql('select count(*) from kg_schema_version where version=64') == '0':
        sql((MIG / 'V64__role_switch.sql').read_text(encoding='utf-8'))
    sql((MIG / 'V65__stay_comment.sql').read_text(encoding='utf-8'))
    # 只写进这个一次性克隆库：关验证码、给 admin 一个已知的测试密码、一个没有任何菜单的后台账号、两个测试敏感词
    sql("update sys_config set config_value='false' where config_key='sys.account.captchaEnabled';"
        "update sys_user set password='%s' where user_name='admin';"
        "insert into sys_user (dept_id, user_name, nick_name, password, status, del_flag, create_by, create_time) values (103,'sc_noperm','无权限','%s','0','0','guardtest',now());"
        "insert into kg_sensitive_word (word, level, status, create_time) values ('拦截测试词','block','0',now()),('复核测试词','review','0',now());" % (ADMIN_HASH, ADMIN_HASH))
    stays = sql("select s.stay_id, s.store_id from kg_stay s where s.status = 'online' and s.del_flag = '0' and exists (select 1 from kg_store st where st.store_id = s.store_id and st.status = '0' and st.del_flag = '0') "
                "and exists (select 1 from kg_stay_room_type rt where rt.stay_id=s.stay_id and rt.status='0' and rt.del_flag='0') order by s.stay_id limit 2").split('\n')
    assert len(stays) == 2, stays
    (stay1, store1), (stay2, store2) = [tuple(int(x) for x in s.split('\t')) for s in stays]

    def io_count():
        return sql("select ifnull(sum(count_star),0) from performance_schema.table_io_waits_summary_by_table where object_schema='%s' and object_name='kg_stay_comment'" % GUARD)

    # ---- 老 jar 在 V65 空库上
    jar = Jar(OLD_JAR, 'old-empty', 'mock')
    check('老 jar 在 V65 库上能启动', jar.up, (WORK / 'backend-old-empty.log'))
    if jar.up:
        ping = http('GET', '/app/auth/ping')['data']
        check('老 jar：指纹 7446、legacy', str(ping.get('build', '')).startswith('7446584416c3') or '7446584416c3' in json.dumps(ping), ping)
        check('老 jar：日记接口照常', code(http('GET', '/app/stay/%d/diary' % stay1)) == 200)
        check('老 jar：评价接口照常', code(http('GET', '/app/stay/%d/reviews/summary' % stay1)) == 200)
        check('老 jar：没有评论接口', code(http('GET', '/app/stay/%d/comments' % stay1)) != 200)
        admin_old = http('POST', '/login', {'username': 'admin', 'password': 'admin123', 'code': '', 'uuid': ''}).get('token')
        check('老 jar：后台能登录、菜单能取', code(http('GET', '/getRouters', token=admin_old)) == 200)
    jar.stop()
    check('老 jar 没碰 kg_stay_comment（performance_schema 计数 0）', io_count() == '0', io_count())

    # ---- 新 jar + legacy + review-mode=mock
    jar = Jar(NEW_JAR, 'new-legacy', 'mock')
    check('新 jar 在 V65 库上能启动（legacy）', jar.up, WORK / 'backend-new-legacy.log')
    assert jar.up
    admin = http('POST', '/login', {'username': 'admin', 'password': 'admin123', 'code': '', 'uuid': ''}).get('token')
    assert admin
    noperm = http('POST', '/login', {'username': 'sc_noperm', 'password': 'admin123', 'code': '', 'uuid': ''}).get('token')
    check('没有菜单的后台账号看不了列表', code(http('GET', '/kangaroo/ext/staycomment/list', token=noperm)) == 403)
    check('没有菜单的后台账号不能审核', code(http('PUT', '/kangaroo/stayops/staycomment/1/approve', token=noperm)) == 403)
    routers = json.dumps(http('GET', '/getRouters', token=admin), ensure_ascii=False)
    check('超管菜单里有「小院评论审核」', '小院评论审核' in routers and 'kangaroo/ext/staycomment/index' in routers, routers[:200])
    meta = http('GET', '/kangaroo/ext/staycomment/meta', token=admin)
    check('后台实体元数据：只读 + 三个动作', code(meta) == 200 and meta['data']['allowAdd'] is False and meta['data']['allowEdit'] is False and meta['data']['allowDelete'] is False and [a['key'] for a in meta['data']['actions']] == ['approve', 'reject', 'offline'], meta)
    scenario('legacy', stay1, store1, admin, 'legacy')
    interview_checks(admin, store1)
    check('控制组：新 jar 跑过后计数大于 0（说明上面的 0 是真的）', int(io_count()) > 0, io_count())
    jar.stop()

    # ---- 切到 new 阶段（直接改库里的一行，只为验证评论不看阶段），review-mode=auto
    sql("update kg_role_switch set phase='new' where id=1")
    jar = Jar(NEW_JAR, 'new-new', 'auto')
    check('新 jar 能启动（new 阶段）', jar.up, WORK / 'backend-new-new.log')
    assert jar.up
    admin = http('POST', '/login', {'username': 'admin', 'password': 'admin123', 'code': '', 'uuid': ''}).get('token')
    scenario('new', stay2, store2, admin, 'new')
    jar.stop()
    sql("update kg_role_switch set phase='legacy' where id=1")

    # ---- 老 jar 在有评论数据的库上
    rows_before = sql("select count(*), max(comment_id), ifnull(max(update_time),'') from kg_stay_comment")
    jar = Jar(OLD_JAR, 'old-data', 'mock')
    check('老 jar 在有评论数据的库上也能启动', jar.up)
    if jar.up:
        check('老 jar：日记接口照常', code(http('GET', '/app/stay/%d/diary' % stay1)) == 200)
    jar.stop()
    check('老 jar 跑完评论数据没变', sql("select count(*), max(comment_id), ifnull(max(update_time),'') from kg_stay_comment") == rows_before)

    # ---- 有数据时回滚必须拒绝
    res, err = sql(authorized_rollback_prefix(65, SOURCE, GUARD) + (MIG / 'V65__rollback.sql').read_text(encoding='utf-8'), tolerate=True)
    check('有评论数据时回滚拒绝', res is None and 'has data' in err, err[:200])
    check('回滚被拒后评论数据还在', sql("select count(*), max(comment_id), ifnull(max(update_time),'') from kg_stay_comment") == rows_before)
finally:
    if jar is not None:
        jar.stop()
    if created:
        sql('drop database if exists ' + GUARD, db=None)
        results.append('已删除 ' + GUARD)
    shutil.rmtree(WORK / 'profile', ignore_errors=True)
print('\n'.join(results))
print('通过 %d 项，失败 %d 项' % (sum(1 for r in results if r.startswith('ok')), len(fails)))
sys.exit(1 if fails else 0)
