#!/usr/bin/env python3
"""Build once against the oldest IDE, then check that exact artifact on every target."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from ide_env import product_info, java_bin

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ide', type=Path, action='append', required=True, help='IDE Contents directory; oldest first')
parser.add_argument('--verifier', type=Path, required=True, help='Official JetBrains verifier CLI JAR')
args = parser.parse_args()
root = Path(__file__).resolve().parent
output = root / 'build/compatibility'
output.mkdir(parents=True, exist_ok=True)
ides = [p.resolve() for p in args.ide]
infos = [product_info(p) for p in ides]
builds = [tuple(map(int, info['buildNumber'].split('.'))) for info in infos]
if builds[0] != min(builds):
    parser.error('The first IDE must be the oldest verification target')

def run(command, log, env=None, timeout=240):
    with log.open('w') as stream:
        result = subprocess.run(command, cwd=root, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise SystemExit(f'Failed ({result.returncode}); see {log}')

run([sys.executable, str(root / 'build.py')], output / 'build.log',
    dict(os.environ, TIBERO_POC_IDE=str(ides[0]), TIBERO_POC_BUILD=str(root / 'build'), TIBERO_POC_JAVA_RELEASE='17'))
artifact = root / 'build/tibero-intellij-poc.jar'
verification = root / 'build/tibero-intellij-verification.jar'
reports = output / 'reports'
run([str(java_bin(ides[-1]) / 'java'), '-Xmx3g', '-jar', str(args.verifier.resolve()),
     'check-plugin', str(artifact), *map(str, ides), '-verification-reports-dir', str(reports), '-offline'],
    output / 'verifier.log')
results = []
for ide, info in zip(ides, infos):
    name = info['productCode'] + '-' + info['buildNumber']
    verdicts = list((reports / name / 'plugins/local.tibero.offline-poc').glob('*/verification-verdict.txt'))
    if not verdicts:
        raise SystemExit(f'No verifier result for {name}; see {output / "verifier.log"}')
    verdict = max(verdicts, key=lambda p: p.stat().st_mtime).read_text().strip()
    if not verdict.startswith('Compatible.'):
        raise SystemExit(f'Incompatible {name}: {verdict}')
    sandbox = output / ('sandbox-' + name)
    run([sys.executable, str(root / 'verify-ide.py')], output / (name + '.log'),
        dict(os.environ, TIBERO_POC_SKIP_BUILD='1', TIBERO_POC_IDE=str(ide),
             TIBERO_POC_ARTIFACT=str(verification), TIBERO_POC_SANDBOX=str(sandbox)), timeout=90)
    result = (sandbox / 'result.txt').read_text()
    if not result.startswith('PASS:'):
        raise SystemExit(f'Runtime verification failed for {name}: {result}')
    results.append(dict(ide=name, version=info['version'], verifier=verdict, sandbox=result))
    print('PASS:', name, info['version'], flush=True)
manifest = dict(artifact=str(artifact), sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
                compile_ide=infos[0]['buildNumber'], java_release=17, results=results,
                limitations=['No live Tibero 6/7 database', 'No Tibero 7 JDBC JAR',
                             'Headless UI/model checks; no interactive UI test for each IDE',
                             'Internal Database Tools APIs remain version-sensitive'])
(output / 'results.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print('Compatibility results:', output / 'results.json')
