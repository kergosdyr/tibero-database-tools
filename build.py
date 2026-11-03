#!/usr/bin/env python3
"""Build against an installed IDE without copying proprietary IDE/driver classes."""
import os
import pathlib
import subprocess
import zipfile
import sys
import shutil
import json
import xml.etree.ElementTree as ET
from ide_env import ide_home, java_bin, product_info

def run(command):
    result = subprocess.run(command)
    if result.returncode:
        sys.exit(result.returncode)

root = pathlib.Path(__file__).resolve().parent
ide = ide_home()
driver = pathlib.Path(os.environ['TIBERO_POC_DRIVER']) if os.environ.get('TIBERO_POC_DRIVER') else None
if driver is not None and not driver.is_file():
    sys.exit('TIBERO_POC_DRIVER does not point to a readable JDBC JAR')
java = java_bin(ide)
build = pathlib.Path(os.environ.get('TIBERO_POC_BUILD', str(root / 'build')))
release = os.environ.get('TIBERO_POC_JAVA_RELEASE', '17')
# Newer SDKs (253+) move grid and other platform modules to lib/modules; older ones have no such folder.
libs = sorted((ide / 'lib').glob('*.jar')) + sorted((ide / 'lib/modules').glob('*.jar')) + sorted((ide / 'plugins/DatabaseTools/lib').rglob('*.jar'))
for plugin in ['grid-core-plugin', 'grid-plugin', 'grid', 'grid-core-impl', 'grid-impl', 'uml']:
    libs += sorted((ide / 'plugins' / plugin / 'lib').rglob('*.jar'))
classes = build / 'classes'
tests = build / 'test-classes'
for generated in [classes, tests]:
    if generated.exists():
        shutil.rmtree(generated)
classes.mkdir(parents=True, exist_ok=True)
tests.mkdir(parents=True, exist_ok=True)
cp = os.pathsep.join(map(str, libs))
run([str(java / 'javac'), '--release', release, '-proc:none', '-cp', cp, '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))])
run([str(java / 'javac'), '--release', release, '-proc:none', '-cp', str(classes) + os.pathsep + cp, '-d', str(tests), *map(str, sorted((root / 'test').rglob('*.java')))])
run([str(java / 'java'), '-Djava.awt.headless=true', '-Dtibero.verify.driver.required=' + str(driver is not None).lower(), '-cp', os.pathsep.join([str(tests), str(classes), str(root/'resources'), cp] + ([str(driver)] if driver else [])), 'poc.OfflineCheck'])
descriptor = ET.parse(root / 'resources/META-INF/plugin.xml').getroot()
compile_build = product_info(ide)['buildNumber']
minimum = descriptor.find('idea-version').attrib['since-build']
# A newer SDK build must not silently produce a JAR advertising old-IDE compatibility.
effective_minimum = max([minimum, compile_build], key=lambda value: tuple(map(int, value.split('.'))))
for extension in descriptor.find("extensions[@defaultExtensionNs='com.intellij.database']"):
    assert extension.attrib['dbms'] == 'TIBERO'
    assert (classes / (extension.attrib['implementationClass'].replace('.', '/') + '.class')).is_file()
print('PASS: descriptor XML and implementation class entries')
for verification in [False, True]:
    artifact = build / ('tibero-intellij-verification.jar' if verification else 'tibero-intellij-poc.jar')
    with zipfile.ZipFile(artifact, 'w', zipfile.ZIP_DEFLATED) as z:
        for legal in ['LICENSE', 'NOTICE']:
            if (root / legal).is_file():
                z.write(root / legal, 'META-INF/' + legal)
        for base in [classes, root / 'resources']:
            for path in sorted(base.rglob('*')):
                if not path.is_file():
                    continue
                relative = path.relative_to(base).as_posix()
                if not verification and relative.startswith(('poc/VerifyStarter', 'poc/StoredObjectsVerification', 'poc/RoutineVerification', 'poc/NativeTreeVerification', 'poc/SourceEditVerification', 'poc/DbLinkVerification')):
                    continue
                if relative == 'META-INF/plugin.xml':
                    production = ET.parse(path).getroot()
                    production.find('idea-version').set('since-build', effective_minimum)
                    if not verification:
                        extensions = production.find("extensions[@defaultExtensionNs='com.intellij']")
                        extensions.remove(extensions.find('appStarter'))
                    z.writestr(relative, ET.tostring(production, encoding='utf-8'))
                else:
                    z.write(path, relative)
    print('Verification artifact:' if verification else 'Plugin artifact:', artifact)
version = descriptor.findtext('version')
distribution = build / 'distributions' / f'tibero-database-tools-{version}.zip'
distribution.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(distribution, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(build / 'tibero-intellij-poc.jar', 'tibero-database-tools/lib/tibero-database-tools.jar')
    for legal in ['LICENSE', 'NOTICE']:
        if (root / legal).is_file():
            z.write(root / legal, 'tibero-database-tools/' + legal)
print('Distribution:', distribution)
