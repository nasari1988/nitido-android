#!/usr/bin/env bash
# Builds Nítido on GitHub Actions. Called from .github/workflows/build.yml.
set -euo pipefail

echo "::group::Modelo Real-ESRGAN"
URL="https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.46.0/real_esrgan_general_x4v3-tflite-float.zip"
curl -fL --retry 3 -o model.zip "$URL"
unzip -o -q model.zip -d model
f=$(find model -name '*.tflite' | head -1)
test -n "$f"
mkdir -p app/src/main/assets
cp "$f" app/src/main/assets/realesrgan_x4.tflite

# Real-ESRGAN x4plus (more detail, slower)
curl -fL --retry 3 -o x4plus.zip "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_x4plus/releases/v0.65.0/real_esrgan_x4plus-tflite-float.zip"
unzip -o -q x4plus.zip -d x4plus
f=$(find x4plus -name '*.tflite' | head -1)
test -n "$f"
cp "$f" app/src/main/assets/realesrgan_x4plus.tflite

# NAFNet (GoPro) to remove blur and camera shake
curl -fL --retry 3 -o app/src/main/assets/nafnet_deblur.tflite \
  "https://huggingface.co/litert-community/NAFNet-GoPro-width32-LiteRT/resolve/main/nafnet_fp16.tflite"

# MediaPipe: tap-to-select (Magic Touch) and hair / skin / clothes segmentation. Optional:
# if a download fails the app still builds and those buttons explain they are missing.
curl -fL --retry 3 -o app/src/main/assets/magic_touch.tflite \
  "https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite" \
  || rm -f app/src/main/assets/magic_touch.tflite
curl -fL --retry 3 -o app/src/main/assets/selfie_multiclass.tflite \
  "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite" \
  || rm -f app/src/main/assets/selfie_multiclass.tflite
ls -la app/src/main/assets
echo "::endgroup::"

bash ci/native.sh

echo "::group::APK"
gradle assembleRelease --no-daemon --stacktrace
cp app/build/outputs/apk/release/app-release.apk Nitido.apk
echo "::endgroup::"
