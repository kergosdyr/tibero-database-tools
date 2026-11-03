#!/usr/bin/env python3
"""Build the mock Tibero JDBC driver (mock-tibero.jar) and run its smoke test against the real Tibero 6 driver SQL."""
import os
import pathlib
import subprocess
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(root.parent))
from ide_env import ide_home, java_bin
ide = ide_home()
java = java_bin(ide)
h2 = pathlib.Path(os.environ.get('MOCK_TIBERO_H2', str(pathlib.Path.home() / '.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar')))
if not os.environ.get('TIBERO_POC_DRIVER'):
    sys.exit('Set TIBERO_POC_DRIVER to your licensed Tibero 6 JDBC JAR; the mock reads its metadata SQL.')
driver = pathlib.Path(os.environ['TIBERO_POC_DRIVER'])
if not h2.is_file() or not driver.is_file():
    sys.exit('MOCK_TIBERO_H2 and TIBERO_POC_DRIVER must point to readable JAR files.')
build = root / 'build'
classes, tests = build / 'classes', build / 'test-classes'
for directory in [classes, tests]:
    directory.mkdir(parents=True, exist_ok=True)

def run(command):
    if subprocess.run(command).returncode:
        sys.exit(1)

run([str(java / 'javac'), '--release', '17', '-cp', str(h2), '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))])
jar = build / 'mock-tibero.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for path in sorted(classes.rglob('*.class')):
        z.write(path, path.relative_to(classes).as_posix())
    z.writestr('META-INF/services/java.sql.Driver', 'mock.tibero.MockTiberoDriver\n')
print('Mock driver:', jar)
print('Driver classpath for the IDE:', os.pathsep.join(map(str, [jar, h2, driver])))
if '--no-test' not in sys.argv:
    cp = os.pathsep.join(map(str, [jar, h2, driver]))
    run([str(java / 'javac'), '--release', '17', '-cp', cp, '-d', str(tests), *map(str, sorted((root / 'test').rglob('*.java')))])
    run([str(java / 'java'), '-cp', os.pathsep.join([str(tests), cp]), 'mock.tibero.MockSmoke'])
