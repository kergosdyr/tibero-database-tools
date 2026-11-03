"""Locate an installed IDE SDK on macOS, Linux, or Windows. No IDE binaries are redistributed."""
import json
import os
from pathlib import Path
import platform


def ide_home():
    configured = os.environ.get('TIBERO_POC_IDE')
    candidates = [Path(configured)] if configured else [Path.home() / 'Applications/IntelliJ IDEA.app/Contents', Path('/Applications/IntelliJ IDEA.app/Contents')]
    for path in candidates:
        if (path / 'product-info.json').is_file() or (path / 'Resources/product-info.json').is_file():
            return path.resolve()
    raise SystemExit('Set TIBERO_POC_IDE to an extracted IntelliJ IDEA SDK root (Contents on macOS).')


def product_info(ide):
    path = next((p for p in [ide / 'product-info.json', ide / 'Resources/product-info.json'] if p.is_file()), None)
    if path is None:
        raise SystemExit(f'product-info.json not found under {ide}')
    return json.loads(path.read_text())


def java_bin(ide):
    for path in [ide / 'jbr/Contents/Home/bin', ide / 'jbr/bin']:
        if (path / ('java.exe' if os.name == 'nt' else 'java')).is_file():
            return path
    raise SystemExit('Use an IDE SDK with the bundled JetBrains Runtime (JBR).')


def launch_config(ide):
    system = {'Darwin': 'macOS', 'Linux': 'Linux', 'Windows': 'Windows'}[platform.system()]
    arch = {'arm64': 'aarch64', 'AMD64': 'amd64', 'x86_64': 'amd64'}.get(platform.machine(), platform.machine())
    return next(item for item in product_info(ide)['launch'] if item['os'] == system and item.get('arch', arch) == arch)


def vm_options(ide):
    base = ide / 'Resources' if (ide / 'Resources/product-info.json').is_file() else ide
    return (base / launch_config(ide)['vmOptionsFilePath']).read_text()


def headless_command(ide, properties):
    launch = launch_config(ide)
    def expand(value):
        return value.replace('$APP_PACKAGE/Contents', str(ide)).replace('$IDE_HOME', str(ide)).replace('%IDE_HOME%', str(ide))
    vm = [line.strip() for line in vm_options(ide).splitlines() if line.strip() and not line.startswith('#')]
    args = [expand(arg) for arg in launch.get('additionalJvmArguments', [])]
    cp = os.pathsep.join(str(ide / 'lib' / name) for name in launch['bootClassPathJarNames'])
    return [str(java_bin(ide) / 'java'), *vm, *args,
            '-Djava.awt.headless=true', '-Didea.is.command.line=true', f'-Didea.home.path={ide}',
            f'-Didea.properties.file={properties}', '-cp', cp, launch.get('mainClass', 'com.intellij.idea.Main'), 'tiberoPocCheck']
