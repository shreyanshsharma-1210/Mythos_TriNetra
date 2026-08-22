#!/usr/bin/env bash
# Fetches the two pretrained checkpoints that convert_models.py converts.
# Neither model is trained here — both are published checkpoints used as-is.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p _work

if [ ! -d _work/aasist ]; then
  echo "==> cloning clovaai/aasist (model definition + ASVspoof 2019 LA weights)"
  git clone --depth 1 https://github.com/clovaai/aasist.git _work/aasist
else
  echo "==> aasist already present"
fi

if [ ! -f _work/resemblyzer_pretrained.pt ]; then
  echo "==> fetching Resemblyzer VoiceEncoder checkpoint"
  curl -sSL --max-time 300 -o _work/resemblyzer_pretrained.pt \
    https://github.com/resemble-ai/Resemblyzer/raw/master/resemblyzer/pretrained.pt
else
  echo "==> resemblyzer checkpoint already present"
fi

echo
echo "checkpoints:"
ls -l _work/aasist/models/weights/AASIST.pth _work/resemblyzer_pretrained.pt
echo
echo "next: tools/.venv/Scripts/python.exe tools/convert_models.py"
