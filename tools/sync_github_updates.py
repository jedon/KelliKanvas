#!/usr/bin/env python3
"""Mirror authenticated public GitHub release assets to the Kanvas HTTPS update feed."""
import argparse
import hashlib
import json
import os
import re
import shutil
import tempfile
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

try:
    from .verify_update_envelope import verify_envelope, EnvelopeError
except ImportError:
    from verify_update_envelope import verify_envelope, EnvelopeError

REPOSITORY = 'jedon/KelliKanvas'
FEED = 'https://kanvas.kelli.photo/updates'
APK_MAX_BYTES = 500 * 1024 * 1024
HOSTS = {'api.github.com', 'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}

def digest(path):
    result=hashlib.sha256()
    with path.open('rb') as stream:
        while chunk:=stream.read(1024*1024):result.update(chunk)
    return result.hexdigest()

def allowed_url(url):
    parsed = urlparse(url)
    if parsed.scheme != 'https' or parsed.hostname not in HOSTS or parsed.port not in (None, 443) or parsed.username is not None or parsed.password is not None:
        raise ValueError('Unapproved release download origin')
    return url

class Redirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        allowed_url(newurl)
        return super().redirect_request(request, fp, code, message, headers, newurl)

def download(url, path, maximum):
    request = urllib.request.Request(allowed_url(url), headers={'User-Agent':'KelliKanvas-update-mirror','Accept':'application/vnd.github+json' if urlparse(url).hostname=='api.github.com' else 'application/octet-stream'})
    with urllib.request.build_opener(Redirects()).open(request, timeout=60) as response, path.open('wb') as output:
        count = 0
        while chunk := response.read(1024 * 1024):
            count += len(chunk)
            if count > maximum: raise ValueError('Release asset exceeds size limit')
            output.write(chunk)
    return path

def publish(release, destination, staging, public_key, key_id, fetch=download):
    """No files become visible until signature, hashes and monotonicity are checked."""
    destination = Path(destination)
    with tempfile.TemporaryDirectory(dir=staging) as temporary:
        root = Path(temporary)
        assets = {asset['name']:asset['browser_download_url'] for asset in release['assets']}
        def asset(name, maximum):
            url = assets.get(name)
            expected = f'https://github.com/{REPOSITORY}/releases/download/{release["tag_name"]}/{name}'
            if url != expected: raise ValueError('Missing or unapproved release asset')
            return fetch(url, root/name, maximum)
        envelope = asset('update-envelope.json', 64*1024)
        manifest = verify_envelope(envelope, public_key, key_id)
        version = manifest['versionName']
        if not isinstance(version,str) or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',version): raise ValueError('Invalid app version')
        filename = f'KelliKanvas-{version}.apk'
        checksum_name = filename + '.sha256'
        if release['tag_name'] != 'v'+version or release.get('draft') or release.get('prerelease'): raise ValueError('Not a stable versioned release')
        if manifest['packageName']!='com.jedon.kellikanvas' or manifest['schema']!=1 or manifest['versionCode']<=0 or manifest['sequence']<=0 or not 0<manifest['sizeBytes']<=APK_MAX_BYTES: raise ValueError('Invalid update identity or size')
        if manifest['apkUrl']!=f'{FEED}/{filename}' or manifest['checksumUrl']!=f'{FEED}/{checksum_name}': raise ValueError('Update is not addressed to this feed')
        if not re.fullmatch('[a-f0-9]{64}',manifest['sha256']) or not re.fullmatch('[A-F0-9]{64}',manifest['signerSha256']): raise ValueError('Invalid hash or signer')
        current = destination/'update-envelope.json'
        if current.exists():
            previous = verify_envelope(current, public_key, key_id)
            if manifest == previous: return False
            if manifest['sequence']<=previous['sequence'] or manifest['versionCode']<=previous['versionCode']: raise ValueError('Release rollback rejected')
        checksum = asset(checksum_name,4096)
        expected_checksum = f'{manifest["sha256"]}  {filename}\n'
        if checksum.read_text('ascii') != expected_checksum: raise ValueError('Checksum file mismatch')
        apk = asset(filename,manifest['sizeBytes'])
        if apk.stat().st_size!=manifest['sizeBytes'] or digest(apk)!=manifest['sha256']: raise ValueError('APK hash or size mismatch')
        for source in (apk,checksum):
            target = destination/source.name
            if target.exists() and (target.stat().st_size!=source.stat().st_size or digest(target)!=digest(source)): raise ValueError('Immutable release asset collision')
        for source in (apk,checksum,envelope):
            # systemd mounts each writable directory separately; rename must stay
            # inside the public mount. Inputs have already been authenticated.
            descriptor, name = tempfile.mkstemp(prefix='.kanvas-update-',dir=destination)
            pending = Path(name)
            try:
                with os.fdopen(descriptor,'wb') as output, source.open('rb') as input_stream:
                    shutil.copyfileobj(input_stream,output,1024*1024)
                    output.flush()
                    os.fsync(output.fileno())
                os.chmod(pending,0o644)
                os.replace(pending,destination/source.name)
            finally:
                pending.unlink(missing_ok=True)
        return True

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--destination',type=Path,required=True)
    parser.add_argument('--staging',type=Path,required=True)
    parser.add_argument('--public-key',type=Path,required=True)
    parser.add_argument('--key-id',required=True)
    args=parser.parse_args()
    try:
        with tempfile.TemporaryDirectory(dir=args.staging) as temporary:
            release_path=download(f'https://api.github.com/repos/{REPOSITORY}/releases/latest',Path(temporary)/'release.json',1024*1024)
            release=json.loads(release_path.read_bytes())
        changed=publish(release,args.destination,args.staging,args.public_key,args.key_id)
        print('Published verified update.' if changed else 'Update feed is current.')
    except (EnvelopeError,ValueError,KeyError,OSError):
        raise SystemExit('No compatible verified release could be published; current update feed retained.')

if __name__=='__main__':main()
