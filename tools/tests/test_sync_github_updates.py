import base64
import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.sync_github_updates import publish, allowed_url
from tools.verify_update_envelope import EnvelopeError

@unittest.skipUnless(shutil.which('openssl'),'OpenSSL required')
class ReleaseMirrorTests(unittest.TestCase):
    def test_signed_assets_publish_atomically_and_reject_corruption_and_rollback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); destination=root/'public'; destination.mkdir(); staging=root/'staging'; staging.mkdir()
            private=root/'private.pem'; public=root/'public.pem'
            subprocess.run(['openssl','ecparam','-name','prime256v1','-genkey','-noout','-out',str(private)],check=True,capture_output=True)
            subprocess.run(['openssl','pkey','-in',str(private),'-pubout','-out',str(public)],check=True,capture_output=True)
            apk=b'signed APK fixture'; checksum=hashlib.sha256(apk).hexdigest()
            def assets(version='1.0.22',code=23,sequence=10,corrupt=False):
                filename=f'KelliKanvas-{version}.apk'
                manifest={'apkUrl':f'https://kanvas.kelli.photo/updates/{filename}','checksumUrl':f'https://kanvas.kelli.photo/updates/{filename}.sha256','packageName':'com.jedon.kellikanvas','schema':1,'sequence':sequence,'versionCode':code,'versionName':version,'sha256':checksum,'signerSha256':'A'*64,'sizeBytes':len(apk)}
                payload=(json.dumps(manifest,sort_keys=True,separators=(',',':'))+'\n').encode()
                signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(private)],input=payload,capture_output=True,check=True).stdout
                envelope=(json.dumps({'envelopeSchema':1,'keyId':'fixture','payload':base64.b64encode(payload).decode(),'signature':base64.b64encode(signature).decode()},sort_keys=True,separators=(',',':'))+'\n').encode()
                bodies={filename:apk+b'wrong' if corrupt else apk, filename+'.sha256':f'{checksum}  {filename}\n'.encode(),'update-envelope.json':envelope}
                release={'tag_name':'v'+version,'draft':False,'prerelease':False,'assets':[{'name':name,'browser_download_url':f'https://github.com/jedon/KelliKanvas/releases/download/v{version}/{name}'} for name in bodies]}
                def fetch(url,path,maximum):path.write_bytes(bodies[url.rsplit('/',1)[-1]]);return path
                return release,fetch,bodies
            release,fetch,bodies=assets()
            self.assertTrue(publish(release,destination,staging,public,'fixture',fetch))
            original=(destination/'update-envelope.json').read_bytes()
            self.assertFalse(publish(release,destination,staging,public,'fixture',fetch))
            broken,fetch,_=assets('1.0.23',24,11,True)
            with self.assertRaises(ValueError):publish(broken,destination,staging,public,'fixture',fetch)
            self.assertEqual(original,(destination/'update-envelope.json').read_bytes())
            self.assertFalse((destination/'KelliKanvas-1.0.23.apk').exists())
            old,fetch,_=assets('1.0.21',22,9)
            with self.assertRaises(ValueError):publish(old,destination,staging,public,'fixture',fetch)
            self.assertEqual(original,(destination/'update-envelope.json').read_bytes())
            with self.assertRaises(EnvelopeError):publish(release,destination,staging,public,'wrong-key',assets()[1])
            self.assertEqual(original,(destination/'update-envelope.json').read_bytes())

    def test_download_origin_rejects_private_http_and_embedded_logins(self):
        for url in ['http://github.com/test','https://127.0.0.1/test','https://evil.example/test','https://user:pass@github.com/test']:
            with self.assertRaises(ValueError):allowed_url(url)
        self.assertEqual('https://release-assets.githubusercontent.com/test',allowed_url('https://release-assets.githubusercontent.com/test'))
