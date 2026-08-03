# -*- coding: utf-8 -*-
"""FapSkinRefresh 编译脚本"""
import sys, subprocess, os
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, 'src', 'main', 'java')
RES = os.path.join(BASE, 'src', 'main', 'resources')
LIBS = os.path.join(BASE, 'libs')
OUT = os.path.join(BASE, 'build')

JAVAC = r'D:\Program Files\Java\jdk-21\bin\javac.exe'
JAR = r'D:\Program Files\Java\jdk-21\bin\jar.exe'

# Collect java files
java_files = []
for root, dirs, files in os.walk(SRC):
    for f in files:
        if f.endswith('.java'):
            java_files.append(os.path.join(root, f))

if not java_files:
    print("ERROR: No java files found!")
    sys.exit(1)

# Classpath
cp_parts = []
for f in os.listdir(LIBS):
    if f.endswith('.jar'):
        cp_parts.append(os.path.join(LIBS, f))
classpath = ';'.join(cp_parts)

# Clean build dir
if os.path.exists(OUT):
    import shutil
    shutil.rmtree(OUT)
os.makedirs(OUT)

print(f"Compiling {len(java_files)} file(s)...")
print(f"Classpath: {classpath}")

# Compile
cmd = [JAVAC, '-encoding', 'UTF-8', '-cp', classpath, '-d', OUT] + java_files
result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
if result.returncode != 0:
    print("COMPILE ERROR:")
    print(result.stdout)
    print(result.stderr)
    sys.exit(1)
print("Compile OK.")

# Copy resources
import shutil
for item in os.listdir(RES):
    s = os.path.join(RES, item)
    d = os.path.join(OUT, item)
    if os.path.isfile(s):
        shutil.copy2(s, d)
        print(f"Copied: {item}")

# Package jar
jar_name = 'FapSkinRefresh.jar'
jar_path = os.path.join(BASE, jar_name)
if os.path.exists(jar_path):
    os.remove(jar_path)

cmd = [JAR, 'cf', jar_path, '-C', OUT, '.']
result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
if result.returncode != 0:
    print("JAR ERROR:")
    print(result.stderr)
    sys.exit(1)

size = os.path.getsize(jar_path)
print(f"\n✅ {jar_name} created ({size:,} bytes)")

# Copy to delivery
dest = r'E:\FAPIXEL小游戏中国版\插件\FapSkinRefresh.jar'
if os.path.isdir(os.path.dirname(dest)):
    # Remove old jars with same prefix
    old_dir = os.path.dirname(dest)
    for f in os.listdir(old_dir):
        if f.startswith('FapSkinRefresh') and f.endswith('.jar') and os.path.join(old_dir, f) != dest:
            old_path = os.path.join(old_dir, f)
            os.remove(old_path)
            print(f"Removed old: {f}")
    shutil.copy2(jar_path, dest)
    print(f"📦 Deployed to: {dest}")
else:
    print(f"⚠️ Delivery dir not found: {os.path.dirname(dest)}")
