"""Prepare manifest and compile-only platform API stub, without copying app code."""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
JAVA = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin')
GEN = HERE / 'generated'
GEN.mkdir(exist_ok=True)
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
ET.register_namespace('tools', 'http://schemas.android.com/tools')
tree = ET.parse(ROOT / 'a9_accessibility_service/AndroidManifest.xml')
tree.getroot().attrib.pop('package')  # AGP uses namespace; Soong manifest stays intact.
if '--public' in sys.argv:
    # The public release never talks to the network; drop the unused network permissions.
    name = '{http://schemas.android.com/apk/res/android}name'
    unused = {'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
              'android.permission.ACCESS_WIFI_STATE'}
    for node in list(tree.getroot()):
        if node.tag == 'uses-permission' and node.get(name) in unused:
            tree.getroot().remove(node)
tree.write(GEN / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
stub = GEN / 'stubs/android/os/SystemProperties.java'
stub.parent.mkdir(parents=True, exist_ok=True)
stub.write_text('package android.os; public final class SystemProperties { public static String get(String key, String def) { throw new UnsupportedOperationException("Compile-only stub"); } }\n')
classes = GEN / 'stub-classes'
classes.mkdir(exist_ok=True)
subprocess.run([str(JAVA/'javac'), '-d', str(classes), str(stub)], check=True)
subprocess.run([str(JAVA/'jar'), 'cf', str(GEN/'hidden-api-stubs.jar'), '-C', str(classes), '.'], check=True)
print('Prepared compile-only stub and manifest; no install performed.')
