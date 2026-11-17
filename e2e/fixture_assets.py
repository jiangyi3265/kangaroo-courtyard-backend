"""Verify bundled fixture images before HTTP suites publish their paths."""
import hashlib
import os
from pathlib import Path
import urllib.request
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError('Fixture image endpoint must not redirect')


def require_bundled_asset(asset):
    """Read-only local preflight: exact source bytes must be served as an image.

    This deliberately fails on HTML fallback, broken/missing files and network
    errors instead of allowing a test to persist a nonexistent public cover.
    """
    assert isinstance(asset, str) and asset.startswith('/static/media/')
    media = (ROOT / '袋鼠旅游2/static/media').resolve()
    source = (ROOT / '袋鼠旅游2' / asset.lstrip('/')).resolve()
    assert source.is_relative_to(media) and source.is_file(), 'Bundled fixture image does not exist: ' + asset
    expected = source.read_bytes()
    assert expected, 'Bundled fixture image is empty'
    base = os.environ.get('KG_H5_BASE') or os.environ.get('KG_APP_DEV') or 'http://127.0.0.1:5273'
    endpoint = urlparse(base)
    assert (endpoint.scheme == 'http' and endpoint.hostname in ('localhost', '127.0.0.1')
            and endpoint.port == 5273 and endpoint.path in ('', '/')
            and not endpoint.username and not endpoint.password and not endpoint.query and not endpoint.fragment), \
        'Fixture images must be verified against the isolated H5 on port 5273'
    url = base.rstrip('/') + asset
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(urllib.request.Request(url, method='GET'), timeout=15) as response:
        assert response.status == 200, 'Fixture image HTTP status is not 200'
        assert response.geturl() == url, 'Unexpected fixture image response location'
        assert response.headers.get('Content-Type', '').split(';')[0].startswith('image/'), 'Fixture image returned non-image content'
        actual = response.read(len(expected) + 1)
    assert actual == expected, 'Served fixture image does not match source bytes'
    print('Fixture image verified: %s sha256=%s' % (asset, hashlib.sha256(expected).hexdigest()))
    return asset
