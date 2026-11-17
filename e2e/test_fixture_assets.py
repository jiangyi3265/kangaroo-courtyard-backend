"""Pure fixture-preflight tests; no database or network connection."""
import os
from unittest import TestCase, main
from unittest.mock import MagicMock, patch

try:
    from .fixture_assets import ROOT, NoRedirect, require_bundled_asset
except ImportError:
    from fixture_assets import ROOT, NoRedirect, require_bundled_asset


class FixtureAssetTest(TestCase):
    asset = '/static/media/activity-cover.jpg'
    base = 'http://127.0.0.1:5273'

    def serve(self, body=None, status=200, content_type='image/jpeg'):
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = status
        response.headers = {'Content-Type': content_type}
        response.geturl.return_value = self.base + self.asset
        response.read.return_value = body if body is not None else (ROOT / '袋鼠旅游2' / self.asset.lstrip('/')).read_bytes()
        opener = MagicMock()
        opener.open.return_value = response
        return opener

    def test_exact_real_image_bytes_accepted_without_credentials(self):
        opener = self.serve()
        with patch.dict(os.environ, {'KG_H5_BASE': self.base}), patch('urllib.request.build_opener', return_value=opener):
            self.assertEqual(require_bundled_asset(self.asset), self.asset)
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, self.base + self.asset)
        self.assertEqual(request.header_items(), [])

    def test_html_wrong_bytes_and_bad_status_are_not_success(self):
        for opener in [self.serve(content_type='text/html'), self.serve(body=b'not an image'), self.serve(status=404)]:
            with self.subTest(), patch.dict(os.environ, {'KG_H5_BASE': self.base}), patch('urllib.request.build_opener', return_value=opener):
                with self.assertRaises(AssertionError):
                    require_bundled_asset(self.asset)

    def test_nonexistent_or_outside_asset_rejected_before_network(self):
        with patch('urllib.request.build_opener') as network:
            for asset in ['/profile/upload/nonexistent.jpg', '/static/media/nonexistent.jpg', '/static/media/../../../README.md']:
                with self.subTest(asset=asset), self.assertRaises(AssertionError):
                    require_bundled_asset(asset)
            network.assert_not_called()

    def test_other_project_or_remote_endpoint_rejected_before_network(self):
        for base in ['http://localhost:8080', 'http://localhost:5274', 'https://example.com:5273', 'http://user:secret@localhost:5273']:
            with self.subTest(base=base), patch.dict(os.environ, {'KG_H5_BASE': base}), patch('urllib.request.build_opener') as network:
                with self.assertRaises(AssertionError):
                    require_bundled_asset(self.asset)
                network.assert_not_called()

    def test_network_failure_and_redirect_are_not_swallowed(self):
        opener = self.serve()
        opener.open.side_effect = OSError('no buffer space')
        with patch.dict(os.environ, {'KG_H5_BASE': self.base}), patch('urllib.request.build_opener', return_value=opener):
            with self.assertRaises(OSError):
                require_bundled_asset(self.asset)
        with self.assertRaises(RuntimeError):
            NoRedirect().redirect_request(None, None, 302, '', {}, 'http://example.com/image.jpg')


if __name__ == '__main__':
    main()
