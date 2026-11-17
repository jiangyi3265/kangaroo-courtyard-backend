# -*- coding: utf-8 -*-
"""极简 Redis 客户端（RESP 协议），只用于端到端测试从本机 Redis 读取图形验证码答案。

这样自动化测试可以走真实的管理后台登录流程（含验证码校验），
而不需要为了方便测试去关闭验证码开关。
"""
import socket


class MiniRedis(object):
    def __init__(self, host='127.0.0.1', port=6379, db=0, timeout=5):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.f = self.sock.makefile('rb')
        if db:
            self.cmd('SELECT', str(db))

    def cmd(self, *args):
        out = [b'*' + str(len(args)).encode()]
        for a in args:
            b = a if isinstance(a, bytes) else str(a).encode('utf-8')
            out.append(b'$' + str(len(b)).encode())
            out.append(b)
        self.sock.sendall(b'\r\n'.join(out) + b'\r\n')
        return self._read()

    def _read(self):
        line = self.f.readline()
        if not line:
            raise IOError('redis connection closed')
        t, rest = line[:1], line[1:].strip()
        if t == b'+':
            return rest.decode('utf-8')
        if t == b'-':
            raise IOError('redis error: ' + rest.decode('utf-8', 'replace'))
        if t == b':':
            return int(rest)
        if t == b'$':
            n = int(rest)
            if n == -1:
                return None
            data = self.f.read(n + 2)[:-2]
            return data
        if t == b'*':
            n = int(rest)
            if n == -1:
                return None
            return [self._read() for _ in range(n)]
        raise IOError('unexpected redis reply: %r' % line)

    def get(self, key):
        return self.cmd('GET', key)

    def keys(self, pattern):
        return self.cmd('KEYS', pattern)

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass
