#!/usr/bin/env python3
"""
Apply tgwear modifications to DrKLO/Telegram source tree.
Run from the Telegram/ root directory (the cloned DrKLO/Telegram repo).

This script replaces the old git-apply patch approach with direct file
modifications, which are more robust against line-ending and version drift
issues.

Usage:
    cd Telegram
    python3 ../tgwear-android-patch/scripts/apply_modifications.py
"""

import os
import re
import shutil
import sys

# Paths
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PATCH_ROOT = os.path.dirname(SCRIPT_DIR)  # tgwear-android-patch/
TELEGRAM_ROOT = os.getcwd()               # Telegram/

SIGNING_DIR = os.path.join(PATCH_ROOT, 'signing')
SRC_DIR = os.path.join(PATCH_ROOT, 'src', 'org', 'telegram', 'tgwear')


def log(msg):
    print(f'[tgwear] {msg}')


def read_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def write_file(path, content):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)


def modify_gradle_properties():
    """Set APP_PACKAGE and signing credentials in gradle.properties."""
    path = os.path.join(TELEGRAM_ROOT, 'gradle.properties')
    content = read_file(path)

    # APP_PACKAGE: 改为 com.hrk.tgwear
    content = re.sub(
        r'APP_PACKAGE\s*=.*',
        'APP_PACKAGE=com.hrk.tgwear',
        content
    )
    # 如果没有 APP_PACKAGE 行，加上
    if 'APP_PACKAGE' not in content:
        content += '\nAPP_PACKAGE=com.hrk.tgwear\n'

    # 签名密码
    for key, val in [
        ('RELEASE_STORE_PASSWORD', 'tgwear2026'),
        ('RELEASE_KEY_ALIAS', 'tgwear'),
        ('RELEASE_KEY_PASSWORD', 'tgwear2026'),
    ]:
        # 替换已有行
        pattern = rf'{key}\s*=.*'
        replacement = f'{key}={val}'
        if re.search(pattern, content):
            content = re.sub(pattern, replacement, content)
        else:
            content += f'\n{key}={val}\n'

    write_file(path, content)
    log(f'Modified gradle.properties: APP_PACKAGE=com.hrk.tgwear')


def copy_keystore():
    """Copy tgwear.jks to TMessagesProj/config/release.keystore."""
    src = os.path.join(SIGNING_DIR, 'tgwear.jks')
    dst_dir = os.path.join(TELEGRAM_ROOT, 'TMessagesProj', 'config')
    os.makedirs(dst_dir, exist_ok=True)
    dst = os.path.join(dst_dir, 'release.keystore')
    shutil.copy2(src, dst)
    log(f'Copied tgwear.jks -> TMessagesProj/config/release.keystore')


def modify_android_manifest():
    """Add WearBridgeService and BootReceiver to the release AndroidManifest."""
    path = os.path.join(TELEGRAM_ROOT, 'TMessagesProj', 'config', 'release', 'AndroidManifest.xml')
    content = read_file(path)

    # 检查是否已经加过
    if 'WearBridgeService' in content:
        log('AndroidManifest already has WearBridgeService, skip')
        return

    # 在 </application> 之前插入 service 和 receiver
    service_xml = """        <!-- tgwear: bridge service for Vela wearable communication -->
        <service
            android:name="org.telegram.tgwear.WearBridgeService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <!-- tgwear: auto-start bridge on boot -->
        <receiver
            android:name="org.telegram.tgwear.BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.PACKAGE_REPLACED" />
                <data android:scheme="package" />
            </intent-filter>
        </receiver>

"""

    content = content.replace('</application>', service_xml + '</application>')
    write_file(path, content)
    log(f'Modified AndroidManifest.xml: added WearBridgeService + BootReceiver')


def modify_application_loader():
    """Add WearBridgeService.start() call after super.onCreate() in ApplicationLoader."""
    path = os.path.join(
        TELEGRAM_ROOT,
        'TMessagesProj', 'src', 'main', 'java',
        'org', 'telegram', 'messenger', 'ApplicationLoader.java'
    )
    content = read_file(path)

    # 检查是否已经加过
    if 'WearBridgeService' in content:
        log('ApplicationLoader already has WearBridgeService, skip')
        return

    # 在 super.onCreate(); 之后插入
    inject_code = (
        'super.onCreate();\n'
        '        // tgwear: start bridge service for wearable communication\n'
        '        try { org.telegram.tgwear.WearBridgeService.start(this); } catch (Throwable t) { FileLog.e(t); }\n'
    )
    if 'super.onCreate();' in content:
        content = content.replace('super.onCreate();\n', inject_code, 1)
    else:
        log('WARNING: super.onCreate() not found in ApplicationLoader, skipping')
        return

    write_file(path, content)
    log('Modified ApplicationLoader.java: added WearBridgeService.start()')


def copy_tgwear_sources():
    """Copy org/telegram/tgwear/ source files into TMessagesProj."""
    dst_dir = os.path.join(
        TELEGRAM_ROOT,
        'TMessagesProj', 'src', 'main', 'java',
        'org', 'telegram', 'tgwear'
    )
    if os.path.exists(dst_dir):
        shutil.rmtree(dst_dir)
    shutil.copytree(SRC_DIR, dst_dir)

    # 统计文件数
    count = sum(len(files) for _, _, files in os.walk(dst_dir))
    log(f'Copied {count} files to TMessagesProj/src/main/java/org/telegram/tgwear/')


def main():
    log(f'TELEGRAM_ROOT = {TELEGRAM_ROOT}')
    log(f'PATCH_ROOT = {PATCH_ROOT}')

    # 验证关键文件存在
    gradle_props = os.path.join(TELEGRAM_ROOT, 'gradle.properties')
    if not os.path.exists(gradle_props):
        log(f'ERROR: {gradle_props} not found')
        sys.exit(1)

    jks = os.path.join(SIGNING_DIR, 'tgwear.jks')
    if not os.path.exists(jks):
        log(f'ERROR: {jks} not found')
        sys.exit(1)

    if not os.path.isdir(SRC_DIR):
        log(f'ERROR: {SRC_DIR} not found')
        sys.exit(1)

    # 执行修改
    modify_gradle_properties()
    copy_keystore()
    modify_android_manifest()
    modify_application_loader()
    copy_tgwear_sources()

    log('All modifications applied successfully!')


if __name__ == '__main__':
    main()
