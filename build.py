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
compile_build = product_info(ide)['buildNumber']
build_number = lambda value: tuple(map(int, value.split('.')))
profiles = json.loads((root / 'release-profiles.json').read_text())
profile_name = 'current' if build_number(compile_build) >= build_number(profiles['current']['since-build']) else 'legacy'
profile = profiles[profile_name]
upper_bound = profile['until-build']
exceeds_upper_bound = (
    build_number(compile_build)[:len(upper_bound.split('.')) - 1] > build_number(upper_bound.removesuffix('.*'))
    if upper_bound.endswith('.*') else build_number(compile_build) > build_number(upper_bound)
)
if build_number(compile_build) < build_number(profile['since-build']) or exceeds_upper_bound:
    sys.exit(f'IDE {compile_build} is outside the supported {profile_name} API profile')
source_roots = [root / 'src', root / 'test']
if profile_name == 'legacy':
    # 2026.2.4 relocated the JDBC API. Compile the same implementation against
    # the older namespace; the two artifacts advertise disjoint IDE ranges.
    relocations = {
        'com.intellij.database.jdbc.wrappers': 'com.intellij.database.dialects.base.introspector.jdbc.wrappers',
        'com.intellij.database.jdbc.JdbcSourceLoader': 'com.intellij.database.dialects.base.introspector.jdbc.JdbcSourceLoader',
        'com.intellij.database.jdbc.JdbcIntrospectorHelper': 'com.intellij.database.dialects.base.introspector.jdbc.JdbcIntrospectorHelper',
    }
    generated_sources = build / 'legacy-sources'
    if generated_sources.exists():
        shutil.rmtree(generated_sources)
    for source_root in source_roots:
        for source in source_root.rglob('*.java'):
            target = generated_sources / source.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            text = source.read_text()
            for current, legacy in relocations.items():
                text = text.replace(current, legacy)
            target.write_text(text)
    source_roots = [generated_sources / 'src', generated_sources / 'test']
print(f'API profile: {profile_name}, plugin {profile["version"]}, IDE {compile_build}', flush=True)
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
run([str(java / 'javac'), '--release', release, '-proc:none', '-cp', cp, '-d', str(classes), *map(str, sorted(source_roots[0].rglob('*.java')))])
run([str(java / 'javac'), '--release', release, '-proc:none', '-cp', str(classes) + os.pathsep + cp, '-d', str(tests), *map(str, sorted(source_roots[1].rglob('*.java')))])
run([str(java / 'java'), '-Djava.awt.headless=true', '-Dtibero.verify.driver.required=' + str(driver is not None).lower(), '-cp', os.pathsep.join([str(tests), str(classes), str(root/'resources'), cp] + ([str(driver)] if driver else [])), 'poc.OfflineCheck'])
descriptor = ET.parse(root / 'resources/META-INF/plugin.xml').getroot()
minimum = profile['since-build']
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
                if not verification and relative.startswith(('poc/VerifyStarter', 'poc/StoredObjectsVerification', 'poc/MetadataRefreshVerification', 'poc/RoutineVerification', 'poc/NativeTreeVerification', 'poc/SourceEditVerification', 'poc/DbLinkVerification')):
                    continue
                if relative == 'META-INF/plugin.xml':
                    production = ET.parse(path).getroot()
                    production.find('version').text = profile['version']
                    production.find('idea-version').set('since-build', effective_minimum)
                    production.find('idea-version').set('until-build', profile['until-build'])
                    supported = '2026.1.5–2026.2.3' if profile_name == 'legacy' else '2026.2.4–2026.2.x'
                    for field in ['description', 'change-notes']:
                        production.find(field).text = production.findtext(field).replace('2026.1.5–2026.2.x', supported)
                    if not verification:
                        extensions = production.find("extensions[@defaultExtensionNs='com.intellij']")
                        extensions.remove(extensions.find('appStarter'))
                    z.writestr(relative, ET.tostring(production, encoding='utf-8'))
                else:
                    z.write(path, relative)
    print('Verification artifact:' if verification else 'Plugin artifact:', artifact)
version = profile['version']
distribution = build / 'distributions' / f'tibero-database-tools-{version}.zip'
distribution.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(distribution, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(build / 'tibero-intellij-poc.jar', 'tibero-database-tools/lib/tibero-database-tools.jar')
    for legal in ['LICENSE', 'NOTICE']:
        if (root / legal).is_file():
            z.write(root / legal, 'tibero-database-tools/' + legal)
print('Distribution:', distribution)
