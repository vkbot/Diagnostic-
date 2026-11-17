#!/usr/bin/env python3
"""
OMODA AutoPilot: сборка релизного APK, выравнивание, подпись platform-ключом,
проверка подписи по отпечатку сертификата и (опционально) установка на ГУ.

Запуск из корня проекта (Windows, PowerShell или cmd):

    python scripts\\build_and_sign.py                 # только выравнивание и подпись
    python scripts\\build_and_sign.py --build         # сначала собрать релиз через gradlew
    python scripts\\build_and_sign.py --build --install

Ключи по умолчанию ищутся в C:\\Apktool (platform.pk8 и platform.x509.pem).
Ключи не должны лежать внутри папки проекта: скрипт проверяет это и останавливается.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
UNSIGNED_APK = PROJECT / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"

# SHA-256 сертификата platform_x509.pem (без двоеточий, в нижнем регистре).
EXPECTED_SHA256 = (
    "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
)


def fail(message: str) -> None:
    print(f"ОШИБКА: {message}", file=sys.stderr)
    sys.exit(1)


def step(title: str) -> None:
    print(f"\n== {title}")


def run(cmd: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess:
    print("  $ " + " ".join(f'"{c}"' if " " in c else c for c in cmd))
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if result.stdout.strip():
        print(result.stdout.rstrip())
    if result.returncode != 0:
        if result.stderr.strip():
            print(result.stderr.rstrip(), file=sys.stderr)
        fail(f"команда завершилась с кодом {result.returncode}")
    return result


def project_version() -> str:
    text = (PROJECT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    return match.group(1) if match else "unknown"


def find_sdk() -> Path:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value and Path(value).is_dir():
            return Path(value)
    local = os.environ.get("LOCALAPPDATA")
    if local and (Path(local) / "Android" / "Sdk").is_dir():
        return Path(local) / "Android" / "Sdk"
    fail("Android SDK не найден. Укажите переменную ANDROID_HOME.")
    raise SystemExit  # для статического анализа


def find_build_tools(sdk: Path, version: str | None) -> Path:
    base = sdk / "build-tools"
    if not base.is_dir():
        fail(f"нет папки {base}. Установите Build-Tools в SDK Manager.")
    dirs = [d for d in base.iterdir() if d.is_dir()]
    if version:
        chosen = base / version
        if not chosen.is_dir():
            fail(f"build-tools {version} не найден в {base}")
        return chosen
    if not dirs:
        fail(f"в {base} нет установленных build-tools")

    def key(d: Path):
        return tuple(int(p) if p.isdigit() else 0 for p in re.split(r"[.\-]", d.name))

    return sorted(dirs, key=key)[-1]


def tool(build_tools: Path, name: str) -> str:
    for candidate in (name + ".exe", name + ".bat", name):
        path = build_tools / candidate
        if path.exists():
            return str(path)
    fail(f"не найден {name} в {build_tools}")
    raise SystemExit


def check_outside_project(*paths: Path) -> None:
    for p in paths:
        resolved = p.resolve()
        if PROJECT in resolved.parents:
            fail(f"ключ лежит внутри проекта: {resolved}. Перенесите его в C:\\Apktool.")


def build_release() -> None:
    step("Сборка релизного APK (gradlew assembleRelease)")
    gradlew = PROJECT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if gradlew.exists():
        run([str(gradlew), "assembleRelease"], cwd=PROJECT)
    elif shutil.which("gradle"):
        run(["gradle", "assembleRelease"], cwd=PROJECT)
    else:
        fail("нет gradlew.bat и команды gradle. Создайте wrapper или установите Gradle.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Сборка, выравнивание и подпись OMODA AutoPilot")
    parser.add_argument("--build", action="store_true", help="сначала собрать релиз через Gradle")
    parser.add_argument("--install", action="store_true", help="установить подписанный APK через adb")
    parser.add_argument("--keys-dir", default=str(Path(r"C:\Apktool")), help="папка с ключами")
    parser.add_argument("--key", default="platform.pk8", help="имя файла приватного ключа")
    parser.add_argument("--cert", default="platform.x509.pem", help="имя файла сертификата")
    parser.add_argument("--build-tools", default=None, help="версия build-tools, например 35.0.0")
    parser.add_argument("--out", default=None, help="путь к подписанному APK")
    args = parser.parse_args()

    keys = Path(args.keys_dir)
    key_path = keys / args.key
    cert_path = keys / args.cert
    check_outside_project(key_path, cert_path)
    for p in (key_path, cert_path):
        if not p.is_file():
            fail(f"файл не найден: {p}")

    if args.build:
        build_release()

    if not UNSIGNED_APK.is_file():
        fail(f"нет неподписанного APK: {UNSIGNED_APK}\nСначала соберите релиз (--build или Build APK в Android Studio).")

    sdk = find_sdk()
    build_tools = find_build_tools(sdk, args.build_tools)
    print(f"Build-tools: {build_tools}")
    zipalign = tool(build_tools, "zipalign")
    apksigner = tool(build_tools, "apksigner")

    out = Path(args.out) if args.out else PROJECT / f"OMODA_AutoPilot-{project_version()}-signed.apk"

    with tempfile.TemporaryDirectory() as tmp:
        aligned = Path(tmp) / "aligned.apk"

        step("Выравнивание (zipalign)")
        run([zipalign, "-p", "-f", "4", str(UNSIGNED_APK), str(aligned)])

        step("Подпись platform-ключом (apksigner)")
        run([apksigner, "sign", "--key", str(key_path), "--cert", str(cert_path),
             "--out", str(out), str(aligned)])

    step("Проверка подписи и отпечатка сертификата")
    result = run([apksigner, "verify", "--print-certs", str(out)])
    digests = re.findall(r"SHA-256 digest:\s*([0-9a-fA-F:]+)", result.stdout)
    if not digests:
        fail("apksigner не показал SHA-256 сертификата")
    actual = [d.replace(":", "").lower() for d in digests]
    if EXPECTED_SHA256 not in actual:
        fail("отпечаток сертификата не совпадает с platform_x509.pem: подпись не та.")
    print("  Отпечаток совпал с platform_x509.pem.")

    print(f"\nГотово: {out}")

    if args.install:
        step("Установка на ГУ (adb install -r)")
        if not shutil.which("adb"):
            fail("adb не найден в PATH. Установите Platform-Tools или запустите установку вручную.")
        run(["adb", "install", "-r", str(out)])
        print("Установлено. Откройте приложение один раз на ГУ.")


if __name__ == "__main__":
    main()
