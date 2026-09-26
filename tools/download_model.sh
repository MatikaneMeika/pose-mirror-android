#!/bin/bash
# Downloads the MediaPipe PoseLandmarker model bundle into
# app/src/main/assets/ (git-ignored). Run once before building.
set -euo pipefail

URL="https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/pose_landmarker_full.task"

mkdir -p "$(dirname "$OUT")"
if [ -f "$OUT" ]; then
    echo "already exists: $OUT"
    exit 0
fi
echo "downloading pose model..."
curl -L --progress-bar -o "$OUT" "$URL"
echo "saved to $OUT"
