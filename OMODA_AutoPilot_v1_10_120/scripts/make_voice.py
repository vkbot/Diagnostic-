#!/usr/bin/env python3
"""Генерирует готовые фразы ответов офлайн (Piper, русский голос).

Запуск на ПК:
    python make_voice.py ru_RU-irina-medium.onnx ../app/src/main/assets/voice

Нужен piper в PATH: https://github.com/rhasspy/piper
Голоса: https://huggingface.co/rhasspy/piper-voices (ru_RU).
Тексты должны совпадать с LpPhrases.TEXTS в приложении.
"""
import pathlib
import subprocess
import sys

PHRASES = {
    "listening": "Слушаю.",
    "done": "Готово.",
    "not_understood": "Не поняла команду.",
    "wifi_on": "Wi-Fi включён.",
    "wifi_off": "Wi-Fi выключен.",
}

if len(sys.argv) != 3:
    sys.exit("usage: make_voice.py <model.onnx> <output_dir>")
model, out_dir = sys.argv[1], pathlib.Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)
for key, text in PHRASES.items():
    wav = out_dir / f"{key}.wav"
    subprocess.run(["piper", "--model", model, "--output_file", str(wav)],
                   input=text.encode("utf-8"), check=True)
    print("ok", wav)
