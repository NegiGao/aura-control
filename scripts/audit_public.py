"""Audit only Git-tracked files; never scan or upload build output or runtime data."""
import pathlib
import re
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
files = subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0')
forbidden = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|/Users/gaotianfu|xreal\.feishu\.cn|com\.Gugenka|com\.resolutiongames|com\.magicbean|com\.brink|V1L[0-9A-Z]{8,}|L1X[0-9A-Z]{8,}')
issues = []
for name in filter(None, files):
    p = pathlib.PurePosixPath(name)
    if any(part in {'build','.gradle','.idea','recordings','verification','release'} for part in p.parts) or p.suffix in {'.apk','.log','.jks','.keystore','.pem'} or p.name.startswith('adbkey'):
        issues.append(name + ': prohibited artifact')
    data = (root/name).read_bytes()
    if b'\0' not in data and name != 'scripts/audit_public.py' and forbidden.search(data.decode('utf8',errors='ignore')):
        issues.append(name + ': private content marker')
assert not issues, '\n'.join(issues)
print(f'PASS: {len(list(filter(None, files)))} tracked public files; no private keys, device records, internal paths, company demo packages or build artifacts')
