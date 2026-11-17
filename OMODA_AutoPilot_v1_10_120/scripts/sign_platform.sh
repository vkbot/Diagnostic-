#!/usr/bin/env bash
# Выравнивает и подписывает APK platform-ключом.
#
# Использование:
#   scripts/sign_platform.sh <unsigned.apk> <platform.pk8> <platform_x509.pem> <out.apk>
#
# Ключи хранить ВНЕ папки проекта. Скрипт отказывается работать, если ключ лежит внутри проекта.
set -euo pipefail

if [ "$#" -ne 4 ]; then
    echo "Использование: $0 <unsigned.apk> <platform.pk8> <platform_x509.pem> <out.apk>" >&2
    exit 1
fi

IN_APK="$1"; KEY="$2"; CERT="$3"; OUT_APK="$4"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

for f in "$IN_APK" "$KEY" "$CERT"; do
    [ -f "$f" ] || { echo "Не найден файл: $f" >&2; exit 1; }
done

for key_path in "$KEY" "$CERT"; do
    abs="$(cd "$(dirname "$key_path")" && pwd)/$(basename "$key_path")"
    case "$abs" in
        "$PROJECT_DIR"/*) echo "Ошибка: ключ или сертификат находится внутри проекта: $abs" >&2; exit 1 ;;
    esac
done

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT_DIR="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)"
[ -n "$BT_DIR" ] || { echo "Не найден Android build-tools в $SDK/build-tools" >&2; exit 1; }
ZIPALIGN="$BT_DIR/zipalign"
APKSIGNER="$BT_DIR/apksigner"

TMP_APK="$(mktemp -t omoda-aligned-XXXX.apk)"
trap 'rm -f "$TMP_APK"' EXIT

"$ZIPALIGN" -p -f 4 "$IN_APK" "$TMP_APK"
"$APKSIGNER" sign --key "$KEY" --cert "$CERT" --out "$OUT_APK" "$TMP_APK"
"$APKSIGNER" verify --print-certs "$OUT_APK"
echo "Готово: $OUT_APK"
