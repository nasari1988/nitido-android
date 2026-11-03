#!/usr/bin/env bash
# Builds the native Stable Diffusion engine (libsdjni.so) and the VAE encoder model.
# Run automatically by Gradle before building the app when they are missing.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf mnn

echo "::group::Motor Stable Diffusion (MNN 3.4.0)"
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
test -d "$NDK"
git clone -q --depth 1 --branch 3.4.0 https://github.com/alibaba/MNN.git mnn
# MNN 3.4.0 forgets <cstdint> in the diffusion tokenizer
sed -i 's/#include <vector>/#include <cstdint>\n#include <vector>/' mnn/transformers/diffusion/engine/src/tokenizer.hpp
# MNN 3.4.0's CLIP tokenizer writes past the end on prompts longer than 75 tokens
# and stops on unknown words: cut long prompts and skip unknown pieces instead.
python3 - <<'PY'
p = "mnn/transformers/diffusion/engine/src/tokenizer.cpp"
s = open(p).read()
old = """    for (auto s : result) {
        ids[idx++] = mVocabs.at(s);
    }"""
new = """    for (auto s : result) {
        if (idx >= 2 * maxlen - 1) break;
        auto it = mVocabs.find(s);
        if (it != mVocabs.end()) ids[idx++] = it->second;
    }"""
assert old in s, "tokenizer patch target not found"
open(p, "w").write(s.replace(old, new))
PY
cmake -S mnn -B mnn/build-android \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_static -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.10 -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DMNN_BUILD_FOR_ANDROID_COMMAND=ON -DMNN_USE_LOGCAT=OFF \
  -DMNN_BUILD_SHARED_LIBS=OFF -DMNN_SEP_BUILD=OFF -DMNN_ARM82=ON \
  -DMNN_LOW_MEMORY=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DMNN_OPENCL=ON \
  -DMNN_BUILD_DIFFUSION=ON -DMNN_BUILD_LLM=ON -DMNN_BUILD_OPENCV=ON -DMNN_IMGCODECS=ON
cmake --build mnn/build-android --target MNN -j"$(nproc)"
ls -la mnn/build-android/*.a
mkdir -p app/src/main/jniLibs/arm64-v8a
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$TC/aarch64-linux-android29-clang++" -shared -fPIC -O2 -std=c++17 \
  app/src/main/cpp/sd_jni.cpp \
  -Imnn/include -Imnn/transformers/diffusion/engine/include -Imnn/transformers/diffusion/engine/src -Imnn/tools/cv/include \
  -Wl,--whole-archive mnn/build-android/libMNN.a -Wl,--no-whole-archive \
  -static-libstdc++ -llog -landroid -lnativewindow -ldl -lm \
  -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--gc-sections \
  -o app/src/main/jniLibs/arm64-v8a/libsdjni.so
"$TC/llvm-strip" --strip-unneeded app/src/main/jniLibs/arm64-v8a/libsdjni.so
ls -la app/src/main/jniLibs/arm64-v8a
echo "::endgroup::"

echo "::group::Codificador VAE para editar fotos (img2img)"
source ci/venv.sh
.venv/bin/pip install --quiet "torch==2.4.1" --index-url https://download.pytorch.org/whl/cpu
.venv/bin/pip install --quiet "diffusers==0.30.3" "huggingface_hub==0.25.2" "onnx==1.16.2" "safetensors==0.4.5" "numpy<2"
.venv/bin/python ci/export_vae_encoder.py vae_encoder.onnx
cmake -S mnn -B mnn/build-host -DCMAKE_BUILD_TYPE=Release -DMNN_BUILD_CONVERTER=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.10
cmake --build mnn/build-host --target MNNConvert -j"$(nproc)"
mnn/build-host/MNNConvert -f ONNX --modelFile vae_encoder.onnx --MNNModel app/src/main/assets/vae_encoder.mnn --bizCode MNN --weightQuantBits 8
ls -la app/src/main/assets
echo "::endgroup::"

echo "::group::Upscalers de la comunidad (UltraSharp, Siax, Anime)"
# Optional: if anything here fails the app still builds, just without these three options.
(
  set +e
  .venv/bin/pip install --quiet "torchvision==0.19.1" --index-url https://download.pytorch.org/whl/cpu
  .venv/bin/pip install --quiet "spandrel==0.4.1" "einops" || .venv/bin/pip install --quiet spandrel einops
  .venv/bin/python ci/export_upscalers.py up_onnx
  for f in up_onnx/*.onnx; do
    [ -e "$f" ] || continue
    n=$(basename "$f" .onnx)
    mnn/build-host/MNNConvert -f ONNX --modelFile "$f" --MNNModel "app/src/main/assets/$n.mnn" --bizCode MNN --fp16 \
      || rm -f "app/src/main/assets/$n.mnn"
  done
  rm -rf up_onnx
  ls -la app/src/main/assets/up_*.mnn
  true
)
echo "::endgroup::"

