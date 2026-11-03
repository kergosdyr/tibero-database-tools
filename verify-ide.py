#!/usr/bin/env python3
"""Launch a separate headless IDE; leave the user's IDE configuration untouched."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
from ide_env import ide_home, headless_command, vm_options

root = Path(__file__).resolve().parent
ide = ide_home()
if not os.environ.get('TIBERO_POC_SKIP_BUILD'):
    subprocess.run([sys.executable, str(root / 'build.py')], check=True)
sandbox = Path(os.environ.get('TIBERO_POC_SANDBOX', str(root / 'build/sandbox')))
plugin = sandbox / 'plugins/tibero-poc/lib'
plugin.mkdir(parents=True, exist_ok=True)
artifact = Path(os.environ.get('TIBERO_POC_ARTIFACT', str(root / 'build/tibero-intellij-verification.jar')))
shutil.copy2(artifact, plugin / 'tibero-intellij-poc.jar')
result = sandbox / 'result.txt'
if result.exists():
    result.unlink()
properties = sandbox / 'idea.properties'
properties.write_text('\n'.join(f'idea.{key}.path={sandbox/key}' for key in ['config', 'system', 'plugins', 'log'])
                      + '\nidea.initially.ask.config=false\ntibero.poc.result=' + str(result) + '\n')
vmoptions = sandbox / 'idea.vmoptions'
vmoptions.write_text(vm_options(ide)
                     + '\n-Djava.awt.headless=true\n-Didea.is.command.line=true\n')
env = dict(os.environ, IDEA_PROPERTIES=str(properties), IDEA_VM_OPTIONS=str(vmoptions))
run = subprocess.run(headless_command(ide, properties), env=env, timeout=120)
if run.returncode or not result.exists():
    sys.exit(f'IDE verification failed. See {sandbox / "log/idea.log"}')
print(result.read_text())
