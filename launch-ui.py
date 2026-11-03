#!/usr/bin/env python3
"""Open the built plugin in the isolated, visible IntelliJ sandbox."""
import os
from pathlib import Path
import subprocess
from ide_env import ide_home, launch_config, vm_options

root = Path(__file__).resolve().parent
ide = ide_home()
sandbox = root / 'build/sandbox'
if not (sandbox / 'plugins/tibero-poc/lib/tibero-intellij-poc.jar').exists():
    raise SystemExit('Run python3 verify-ide.py first to prepare the sandbox.')
vm = sandbox / 'idea-ui.vmoptions'
vm.write_text(vm_options(ide))
with (sandbox / 'ui-launch.log').open('a') as log:
    launcher = ide / ('Resources' if (ide / 'Resources/product-info.json').is_file() else '.') / launch_config(ide)['launcherPath']
    process = subprocess.Popen([str(launcher), str(root)],
        env=dict(os.environ, IDEA_PROPERTIES=str(sandbox / 'idea.properties'), IDEA_VM_OPTIONS=str(vm)),
        stdout=log, stderr=log, start_new_session=True)
print('Sandbox IDE PID:', process.pid)
