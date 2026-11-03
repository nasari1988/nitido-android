// Stable Diffusion 1.5 family on MNN, called from Kotlin via JNI.
// Modes:
//   0 = standard SD 1.5 model: text-to-image, or image-to-image when a photo is given
//   1 = InstructPix2Pix: edits a photo following an instruction
// Samplers: 0 = PLMS (MNN's original), 1 = DPM++ 2M with Karras sigmas.
// Models converted with dynamic spatial shapes accept any size that is a multiple of 64;
// older fixed models only accept 512×512.
// Pipeline follows MNN 3.4.0 transformers/diffusion/engine/src/stable_diffusion.cpp.
#include <jni.h>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <random>
#include <string>
#include <vector>
#include <unistd.h>
#include <sys/stat.h>
#include <android/log.h>

#include <MNN/expr/Module.hpp>
#include <MNN/expr/Executor.hpp>
#include <MNN/expr/ExecutorScope.hpp>
#include <MNN/expr/ExprCreator.hpp>
#include <cv/cv.hpp>
#include "tokenizer.hpp"
#include "scheduler.hpp"

using namespace MNN;
using namespace MNN::Express;
using namespace MNN::DIFFUSION;
using namespace CV;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NitidoSD", __VA_ARGS__)

namespace {

constexpr int kTextLen = 77;
std::atomic<bool> gCancel(false);

bool exists(const std::string& p) { struct stat st; return stat(p.c_str(), &st) == 0; }

enum Result { OK = 0, LOAD_FAILED = 2, RUN_FAILED = 3, NO_ENCODER = 4, BAD_SIZE = 5, CANCELLED = 6,
              BAD_TEXT = 7, BAD_PHOTO = 8, BAD_UNET = 9, BAD_DECODE = 10 };

// True if a tensor holds NaN/inf (numbers that "overflowed"), which turns pictures black.
bool hasBad(VARP v) {
    if (v.get() == nullptr) return false;
    auto c = _Convert(v, NCHW);
    const float* p = c->readMap<float>();
    if (!p) return true;
    size_t n = c->getInfo()->size;
    for (size_t i = 0; i < n; i++) if (!std::isfinite(p[i])) return true;
    return false;
}

class Pipeline {
public:
    bool load(const std::string& dir, int backend, int mode, bool dynamic) {
        dir_ = dir;
        mode_ = mode;
        dynamic_ = dynamic;
        readInfo(dir);
        ScheduleConfig config;
        BackendConfig backendConfig;
        config.type = (MNNForwardType)backend;
        if (config.type == MNN_FORWARD_CPU) {
            config.numThread = 4;
        } else if (config.type == MNN_FORWARD_OPENCL) {
            config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
        } else {
            config.numThread = 1;
        }
        backendConfig.memory = BackendConfig::Memory_Low;
        backendConfig.precision = BackendConfig::Precision_Low;
        config.backendConfig = &backendConfig;

        auto exe = ExecutorScope::Current();
        exe->lazyEval = false;
        exe->setGlobalExecutorConfig(config.type, backendConfig, config.numThread);

        rt_.reset(Executor::RuntimeManager::createRuntimeManager(config));
        if (config.type == MNN_FORWARD_OPENCL) rt_->setCache(".tempcache");
        if (config.type == MNN_FORWARD_CPU) rt_->setHint(Interpreter::DYNAMIC_QUANT_OPTIONS, 2);

        promptVar_ = _Input({2, kTextLen}, NCHW, halide_type_of<int>());
        timestepVar_ = _Input({1}, NCHW, halide_type_of<int>());
        promptVar_->writeMap<int8_t>();
        timestepVar_->writeMap<int8_t>();

        hasEncoderFile_ = exists(dir + "/vae_encoder.mnn");
        if ((mode_ == 1 || mode_ == 2) && !hasEncoderFile_) return false;
        tok_.reset(new CLIPTokenizer);
        if (!tok_->load(dir)) return false;
        std::unique_ptr<Scheduler> sch(new PNDMScheduler);
        alphas_ = sch->get_alphas();

        // SDXL is big: load each piece only while it is needed (low memory).
        lowRam_ = sdxl_;
        if (lowRam_) return exists(dir + "/unet.mnn") && exists(dir + "/text_encoder.mnn");

        if (!te() || !unet() || !dec()) return false;
        if (hasEncoderFile_ && !enc()) return false;

        // Warm up once at 512×512 so the first real run doesn't pay for setup.
        auto lat = _Input({1, 4, 64, 64}, NCHW, halide_type_of<float>());
        ::memset(lat->writeMap<float>(), 0, 4 * 64 * 64 * sizeof(float));
        auto emb = _Convert(te()->onForward({promptVar_})[0], NCHW);
        VARP u;
        if (mode_ == 1) {
            auto parts = _Split(emb, {2}, 0);
            auto emb3 = _Concat({parts[1], parts[0], parts[0]}, 0);
            auto x = _Concat({lat, lat}, 1);
            u = unet()->onForward({_Concat({x, x, x}, 0), timestepVar_, emb3})[0];
        } else if (mode_ == 2) {
            auto m1 = _Const(0.f, {1, 1, 64, 64}, NCHW);
            auto x = _Concat({lat, m1, lat}, 1);
            u = unet()->onForward({_Concat({x, x}, 0), timestepVar_, emb})[0];
        } else {
            u = unet()->onForward({_Concat({lat, lat}, 0), timestepVar_, emb})[0];
        }
        _Convert(u, NCHW)->readMap<float>();
        _Convert(dec()->onForward({lat})[0], NCHW)->readMap<float>();
        return true;
    }

    // ---- modules, loaded on first use ----
    Module* loadModule(std::shared_ptr<Module>& m, const char* file, const std::vector<std::string>& in,
                       const std::vector<std::string>& out, bool mutableShape) {
        if (!m) {
            Module::Config c;
            c.shapeMutable = mutableShape;
            m.reset(Module::load(in, out, (dir_ + "/" + file).c_str(), rt_, &c));
            if (m && !mutableShape) m->traceOrOptimize(Interpreter::Session_Resize_Fix);
        }
        return m.get();
    }
    Module* te() {
        if (sdxl_) return loadModule(te_, "text_encoder.mnn", {"input_ids"}, {"prompt_embeds", "pooled"}, false);
        return loadModule(te_, "text_encoder.mnn", {"input_ids"}, {"last_hidden_state", "pooler_output"}, false);
    }
    Module* unet() {
        if (sdxl_) return loadModule(unet_, "unet.mnn", {"sample", "timestep", "encoder_hidden_states", "text_embeds", "time_ids"}, {"out_sample"}, dynamic_);
        return loadModule(unet_, "unet.mnn", {"sample", "timestep", "encoder_hidden_states"}, {"out_sample"}, dynamic_);
    }
    Module* dec() { return loadModule(dec_, "vae_decoder.mnn", {"latent_sample"}, {"sample"}, dynamic_); }
    Module* enc() { return hasEncoderFile_ ? loadModule(enc_, "vae_encoder.mnn", {"sample"}, {"latent"}, dynamic_) : nullptr; }
    void drop(std::shared_ptr<Module>& m) { if (lowRam_) m.reset(); }

    void readInfo(const std::string& dir) {
        FILE* f = fopen((dir + "/info.txt").c_str(), "r");
        if (!f) return;
        char line[512];
        while (fgets(line, sizeof line, f)) {
            std::string l(line);
            while (!l.empty() && (l.back() == '\n' || l.back() == '\r')) l.pop_back();
            if (l == "arch=sdxl") sdxl_ = true;
            if (l.rfind("scale=", 0) == 0) scale_ = (float)atof(l.c_str() + 6);
        }
        fclose(f);
        if (scale_ <= 0.f) scale_ = sdxl_ ? 0.13025f : 0.18215f;
    }

    bool hasEncoder() const { return hasEncoderFile_; }
    bool isSdxl() const { return sdxl_; }
    bool dynamic() const { return dynamic_; }

    int run(const std::string& prompt, const std::string& negative, const std::string& out,
            int width, int height, int iterNum, int seed, const float* image, float strength,
            float guidance, float imageGuidance, const float* maskedImage, const float* latMask,
            const std::function<void(int)>& progress,
            int previewMode, const std::function<void(const uint8_t*, int, int)>& preview, int sampler) {
        const int maxSide = sdxl_ ? 1536 : 1024;
        if (width % 64 || height % 64 || width < 256 || height < 256 || width > maxSide || height > maxSide) return BAD_SIZE;
        if (sdxl_ && previewMode == 2) previewMode = 1;   // full previews need the decoder loaded: too much memory
        if (!dynamic_ && (width != 512 || height != 512)) return BAD_SIZE;
        lw_ = width / 8;
        lh_ = height / 8;
        const int nLat = 4 * lw_ * lh_;
        const int nPix = 3 * width * height;

        if (iterNum > 150) iterNum = 150;
        if (iterNum < 1) iterNum = 10;
        std::vector<int> full(iterNum);
        int step = 1000 / iterNum;
        for (int i = iterNum - 1; i >= 0; i--) full[i] = 1 + (iterNum - 1 - i) * step;

        bool pix2pix = mode_ == 1;
        bool inpaint = mode_ == 2;   // inpainting model: 9-channel input (latent, mask, masked photo)
        if (pix2pix && !image) return RUN_FAILED;
        if (inpaint && (!image || !maskedImage || !latMask)) return RUN_FAILED;
        bool img2img = !pix2pix && image != nullptr && hasEncoderFile_ && (!inpaint || strength < 0.999f);
        // normal model + mask: redraw only the masked area, keep the rest from the photo at every step
        bool blend = mode_ == 0 && img2img && latMask != nullptr;
        int start = 0;
        if (img2img) {
            int n = (int)std::lround(iterNum * strength);
            n = std::max(1, std::min(iterNum, n));
            start = iterNum - n;
        }
        ts_.assign(full.begin() + start, full.end());
        ets_.clear();
        int total = (int)ts_.size();

        // Text: row 0 = negative (or empty), row 1 = prompt; each token has a weight.
        std::vector<int> ids(2 * kTextLen);
        std::vector<float> tokW(2 * kTextLen);
        encodeWeighted(negative, ids.data(), tokW.data());
        encodeWeighted(prompt, ids.data() + kTextLen, tokW.data() + kTextLen);
        ::memcpy(promptVar_->writeMap<int>(), ids.data(), 2 * kTextLen * sizeof(int));
        if (!te()) return LOAD_FAILED;
        auto teOut = te()->onForward({promptVar_});
        auto emb = _Convert(teOut[0], NCHW);
        emb.fix(VARP::CONSTANT);
        emb = applyWeights(emb, tokW);
        VARP pooled, timeIds;
        if (sdxl_) {
            pooled = _Convert(teOut[1], NCHW);
            pooled.fix(VARP::CONSTANT);
            timeIds = _Input({2, 6}, NCHW, halide_type_of<float>());
            float* t6 = timeIds->writeMap<float>();
            const float tv[6] = {(float)height, (float)width, 0.f, 0.f, (float)height, (float)width};
            for (int r = 0; r < 2; r++) for (int k = 0; k < 6; k++) t6[r * 6 + k] = tv[k];
            timeIds.fix(VARP::CONSTANT);
        }
        teOut.clear();
        drop(te_);
        if (hasBad(emb)) return BAD_TEXT;
        progress(100 / (total + 3));
        if (gCancel) return CANCELLED;

        // Encode the photo, if any.
        VARP photoLatent;
        if (image && hasEncoderFile_) {
            if (!enc()) return LOAD_FAILED;
            auto in = _Input({1, 3, height, width}, NCHW, halide_type_of<float>());
            ::memcpy(in->writeMap<float>(), image, nPix * sizeof(float));
            photoLatent = _Convert(enc()->onForward({in})[0], NCHW);
            photoLatent.fix(VARP::CONSTANT);
            drop(enc_);
            if (hasBad(photoLatent)) return BAD_PHOTO;
        }
        const int plane = lw_ * lh_;
        VARP maskedLat, maskBin, mask4, origScaled, noiseVar;
        if (inpaint) {
            if (!enc()) return LOAD_FAILED;
            auto in = _Input({1, 3, height, width}, NCHW, halide_type_of<float>());
            ::memcpy(in->writeMap<float>(), maskedImage, nPix * sizeof(float));
            maskedLat = _Convert(enc()->onForward({in})[0], NCHW) * _Scalar(scale_);
            maskedLat.fix(VARP::CONSTANT);
            drop(enc_);
            if (hasBad(maskedLat)) return BAD_PHOTO;
            maskBin = _Input({1, 1, lh_, lw_}, NCHW, halide_type_of<float>());
            float* mb = maskBin->writeMap<float>();
            for (int i = 0; i < plane; i++) mb[i] = latMask[i] > 0.5f ? 1.f : 0.f;
            maskBin.fix(VARP::CONSTANT);
        }
        if (!unet()) return LOAD_FAILED;

        std::mt19937 rng((unsigned)(seed < 0 ? std::random_device()() : seed));
        std::normal_distribution<float> normal(0, 1);
        std::vector<float> noise(nLat);
        for (auto& v : noise) v = normal(rng);

        // Conditioning for InstructPix2Pix: batch = [text, image-only, unconditional].
        VARP emb3, zeros;
        if (pix2pix) {
            auto parts = _Split(emb, {2}, 0);
            emb3 = _Concat({parts[1], parts[0], parts[0]}, 0);
            emb3.fix(VARP::CONSTANT);
            zeros = _Const(0.f, {1, 4, lh_, lw_}, NCHW);
        }

        auto gScale = _Scalar<float>(guidance);
        auto iScale = _Scalar<float>(imageGuidance);
        auto floatVar = _Input({1}, NCHW, halide_type_of<float>());
        float* tp = floatVar->writeMap<float>();

        // Classifier-free guided noise prediction for latent x (already scaled for the model).
        auto predict = [&](VARP x, float t) -> VARP {
            tp[0] = t;
            timestepVar_->input(_Cast(floatVar, halide_type_of<int>()));
            if (inpaint) x = _Concat({x, maskBin, maskedLat}, 1);
            if (pix2pix) {
                auto withImg = _Concat({x, photoLatent}, 1);
                auto noImg = _Concat({x, zeros}, 1);
                auto sample = _Concat({withImg, withImg, noImg}, 0);
                auto pred = _Convert(unet()->onForward({sample, timestepVar_, emb3})[0], NCHW);
                auto p = _Split(pred, {3}, 0);   // text, image, uncond
                return p[2] + gScale * (p[0] - p[1]) + iScale * (p[1] - p[2]);
            }
            auto sample = _Concat({x, x}, 0);
            auto pred = sdxl_
                ? _Convert(unet()->onForward({sample, timestepVar_, emb, pooled, timeIds})[0], NCHW)
                : _Convert(unet()->onForward({sample, timestepVar_, emb})[0], NCHW);
            auto p = _Split(pred, {2}, 0);       // uncond/negative, text
            return p[0] + gScale * (p[1] - p[0]);
        };

        const float* lp = nullptr;
        if (img2img) {
            lp = photoLatent->readMap<float>();
            if (!lp) return RUN_FAILED;
        }
        auto makeLatent = [&](const std::function<float(int)>& f) {
            VARP v = _Input({1, 4, lh_, lw_}, NCHW, halide_type_of<float>());
            float* d = v->writeMap<float>();
            for (int i = 0; i < nLat; i++) d[i] = f(i);
            v.fix(VARP::CONSTANT);
            return v;
        };
        if (blend) {
            mask4 = makeLatent([&](int i) { return std::max(0.f, std::min(1.f, latMask[i % plane])); });
            origScaled = makeLatent([&](int i) { return lp[i] * scale_; });
            noiseVar = makeLatent([&](int i) { return noise[i]; });
        }
        auto one = _Scalar(1.f);
        // keep the photo outside the mask: mix the new latent with the photo noised to the same level
        auto keepOutside = [&](VARP x, VARP photoAtLevel) {
            VARP r = mask4 * x + (one - mask4) * photoAtLevel;
            r.fix(VARP::CONSTANT);
            return r;
        };

        VARP result;
        if (sampler == 1) {
            // DPM++ 2M with Karras sigmas.
            std::vector<float> logSig(1000);
            for (int t = 0; t < 1000; t++) logSig[t] = std::log(std::sqrt((1.f - alphas_[t]) / alphas_[t]));
            auto sigmaToT = [&](float sg) -> float {
                float ls = std::log(sg);
                if (ls <= logSig[0]) return 0.f;
                if (ls >= logSig[999]) return 999.f;
                int lo = 0, hi = 999;
                while (hi - lo > 1) { int mid = (lo + hi) / 2; if (logSig[mid] <= ls) lo = mid; else hi = mid; }
                return lo + (ls - logSig[lo]) / (logSig[hi] - logSig[lo]);
            };
            const float rho = 7.f;
            float smin = std::exp(logSig[0]), smax = std::exp(logSig[999]);
            float a0 = std::pow(smax, 1.f / rho), a1 = std::pow(smin, 1.f / rho);
            std::vector<float> sig(iterNum + 1);
            for (int i = 0; i < iterNum; i++) {
                float f = iterNum > 1 ? (float)i / (iterNum - 1) : 0.f;
                sig[i] = std::pow(a0 + f * (a1 - a0), rho);
            }
            sig[iterNum] = 0.f;
            sig.erase(sig.begin(), sig.begin() + start);
            int n = (int)sig.size() - 1;

            float s0start = sig[0];
            VARP x = makeLatent([&](int i) { return (lp ? lp[i] * scale_ : 0.f) + noise[i] * s0start; });
            x.fix(VARP::CONSTANT);
            VARP old;
            float hLast = 0.f;
            for (int i = 0; i < n; i++) {
                if (gCancel) return CANCELLED;
                float s0 = sig[i], s1 = sig[i + 1];
                auto eps = predict(x * _Scalar(1.f / std::sqrt(s0 * s0 + 1.f)), std::round(sigmaToT(s0)));
                VARP den = x - _Scalar(s0) * eps;
                den.fix(VARP::CONSTANT);
                if ((i == 0 || i % 10 == 9) && hasBad(den)) return BAD_UNET;
                if (previewMode > 0) previewLatent(den, previewMode, preview);
                if (s1 <= 0.f) {
                    x = blend ? keepOutside(den, origScaled) : den;
                } else {
                    float h = std::log(s0) - std::log(s1);
                    VARP d = den;
                    if (old.get() != nullptr && hLast > 0.f) {
                        float r = hLast / h;
                        d = _Scalar(1.f + 1.f / (2.f * r)) * den - _Scalar(1.f / (2.f * r)) * old;
                    }
                    x = _Scalar(s1 / s0) * x - _Scalar(std::expm1(-h)) * d;
                    if (blend) x = keepOutside(x, origScaled + noiseVar * _Scalar(s1));
                    hLast = h;
                }
                x.fix(VARP::CONSTANT);
                old = den;
                progress((2 + i) * 100 / (n + 3));
            }
            result = x;
        } else {
            VARP plms;
            if (img2img) {
                float a = alphas_[ts_[0]];
                float sa = std::sqrt(a), sb = std::sqrt(1.f - a);
                plms = makeLatent([&](int i) { return sa * lp[i] * scale_ + sb * noise[i]; });
            } else {
                plms = makeLatent([&](int i) { return noise[i]; });
            }
            for (int i = 0; i < total; i++) {
                if (gCancel) return CANCELLED;
                auto guided = predict(plms, (float)ts_[i]);
                if ((i == 0 || i % 10 == 9) && hasBad(guided)) return BAD_UNET;
                if (previewMode > 0) sendPreview(plms, guided, ts_[i], previewMode, preview);
                plms = stepPlms(plms, guided, i);
                if (blend) {
                    float a = alphas_[i + 1 < total ? ts_[i + 1] : 0];
                    plms = keepOutside(plms, origScaled * _Scalar(std::sqrt(a)) + noiseVar * _Scalar(std::sqrt(1.f - a)));
                }
                progress((2 + i) * 100 / (total + 3));
            }
            result = plms;
        }
        result.fix(VARP::CONSTANT);
        drop(unet_);
        if (hasBad(result)) return BAD_UNET;
        if (gCancel) return CANCELLED;
        if (!dec()) return LOAD_FAILED;

        decodeBad_ = false;
        auto rgb = decodeRgb(result);
        if (decodeBad_) return BAD_DECODE;
        if (previewMode > 0) {
            const uint8_t* p = rgb->readMap<uint8_t>();
            if (p) preview(p, width, height);
        }
        auto img = cvtColor(rgb, COLOR_BGR2RGB);   // imwrite expects BGR
        img.fix(VARP::CONSTANT);
        bool ok = imwrite(out, img);
        drop(dec_);
        progress(100);
        return ok ? OK : RUN_FAILED;
    }

private:
    // ---- prompt weights: "(red dress:1.3)", "(word)" = ×1.1 per pair of parentheses ----
    struct Piece { std::string text; float weight; };

    static std::vector<Piece> parseWeights(const std::string& s) {
        std::vector<Piece> out;
        std::string cur;
        float mult = 1.f;
        std::vector<float> mults;
        auto flush = [&](float w) { if (!cur.empty()) { out.push_back({cur, w}); cur.clear(); } };
        for (size_t i = 0; i < s.size(); i++) {
            char c = s[i];
            if (c == '\\' && i + 1 < s.size()) { cur += s[++i]; continue; }
            if (c == '(') {
                flush(mult);
                mults.push_back(mult);
                // look ahead for an explicit ":number)" that closes this group
                int depth = 0; size_t j = i + 1; float explicitW = -1.f;
                for (; j < s.size(); j++) {
                    if (s[j] == '(') depth++;
                    else if (s[j] == ')') { if (depth == 0) break; depth--; }
                }
                if (j < s.size()) {
                    size_t colon = s.rfind(':', j);
                    if (colon != std::string::npos && colon > i) {
                        char* end = nullptr;
                        std::string num = s.substr(colon + 1, j - colon - 1);
                        float v = std::strtof(num.c_str(), &end);
                        if (end && end != num.c_str() && *end == '\0') explicitW = v;
                    }
                }
                mult = explicitW >= 0.f ? mult * explicitW : mult * 1.1f;
                continue;
            }
            if (c == ')' && !mults.empty()) {
                // drop a trailing ":number" that belongs to this group
                size_t colon = cur.rfind(':');
                if (colon != std::string::npos) {
                    char* end = nullptr;
                    std::string num = cur.substr(colon + 1);
                    std::strtof(num.c_str(), &end);
                    if (!num.empty() && end && *end == '\0') cur.erase(colon);
                }
                flush(mult);
                mult = mults.back();
                mults.pop_back();
                continue;
            }
            cur += c;
        }
        flush(mult);
        return out;
    }

    // Token ids (start, words..., end, padding) and one weight per position, for 77 positions.
    void encodeWeighted(const std::string& text, int* ids, float* w) {
        auto probe = tok_->encode("", kTextLen);
        const int startId = probe[0], endId = probe[1];
        int n = 0;
        ids[n] = startId; w[n++] = 1.f;
        for (auto& p : parseWeights(text)) {
            auto e = tok_->encode(p.text, kTextLen);
            for (int i = kTextLen + 1; i < 2 * kTextLen && n < kTextLen - 1; i++) {
                if (e[i] == endId) break;
                ids[n] = e[i]; w[n++] = std::max(0.f, std::min(3.f, p.weight));
            }
        }
        ids[n] = endId; w[n++] = 1.f;
        for (; n < kTextLen; n++) { ids[n] = 0; w[n] = 1.f; }
    }

    // Scale each token's embedding by its weight, keeping the overall mean (as A1111 does).
    static VARP applyWeights(VARP emb, const std::vector<float>& w) {
        auto info = emb->getInfo();
        if (!info || info->dim.size() != 3) return emb;
        const int B = info->dim[0], T = info->dim[1], D = info->dim[2];
        bool plain = true;
        for (float v : w) if (std::fabs(v - 1.f) > 1e-4f) { plain = false; break; }
        if (plain) return emb;
        const float* src = emb->readMap<float>();
        if (!src) return emb;
        VARP out = _Input({B, T, D}, NCHW, halide_type_of<float>());
        float* dst = out->writeMap<float>();
        for (int b = 0; b < B; b++) {
            double before = 0, after = 0;
            const float* sb = src + (size_t)b * T * D;
            float* db = dst + (size_t)b * T * D;
            for (int t = 0; t < T; t++) {
                const float wt = w[b * T + t];
                for (int d = 0; d < D; d++) {
                    const float v = sb[t * D + d];
                    before += v;
                    db[t * D + d] = v * wt;
                    after += v * wt;
                }
            }
            const float k = std::fabs(after) > 1e-9 ? (float)(before / after) : 1.f;
            for (size_t i = 0; i < (size_t)T * D; i++) db[i] *= k;
        }
        out.fix(VARP::CONSTANT);
        return out;
    }

    // Latent (model space, already ×0.18215) -> H×W×3 RGB bytes with the VAE decoder.
    VARP decodeRgb(VARP latent) {
        auto img = _Convert(dec()->onForward({latent * _Const(1.f / scale_)})[0], NCHW);
        if (hasBad(img)) { decodeBad_ = true; }
        img = _Relu6(img * _Const(0.5f) + _Const(0.5f), 0, 1);
        img = _Squeeze(_Transpose(img, {0, 2, 3, 1}));
        return _Cast(_Round(img * _Const(255.f)), halide_type_of<uint8_t>());
    }

    void sendPreview(VARP sample, VARP eps, int t, int mode, const std::function<void(const uint8_t*, int, int)>& cb) {
        float a = alphas_[t];
        auto x0 = (sample - _Scalar(std::sqrt(1.f - a)) * eps) * _Scalar(1.f / std::sqrt(a));
        previewLatent(x0, mode, cb);
    }

    // Preview of the model's current estimate of the clean image: fully decoded (mode 2)
    // or a cheap linear color approximation at latent resolution (mode 1).
    void previewLatent(VARP x0, int mode, const std::function<void(const uint8_t*, int, int)>& cb) {
        if (mode == 2) {
            auto rgb = decodeRgb(x0);
            const uint8_t* p = rgb->readMap<uint8_t>();
            if (p) cb(p, lw_ * 8, lh_ * 8);
            return;
        }
        const float* l = _Convert(x0, NCHW)->readMap<float>();
        if (!l) return;
        static const float F15[4][3] = {{0.3512f, 0.2297f, 0.3227f}, {0.3250f, 0.4974f, 0.2350f},
                                        {-0.2829f, 0.1762f, 0.2721f}, {-0.2120f, -0.2616f, -0.7177f}};
        static const float FXL[4][3] = {{0.3651f, 0.4232f, 0.4341f}, {-0.2533f, -0.0042f, 0.1068f},
                                        {0.1076f, 0.1111f, -0.0362f}, {-0.3165f, -0.2492f, -0.2188f}};
        static const float BXL[3] = {0.1084f, -0.0175f, -0.0011f};
        const float (*F)[3] = sdxl_ ? FXL : F15;
        const int plane = lw_ * lh_;
        std::vector<uint8_t> rgb(plane * 3);
        for (int i = 0; i < plane; i++) {
            for (int c = 0; c < 3; c++) {
                float v = 0;
                for (int k = 0; k < 4; k++) v += l[k * plane + i] * F[k][c];
                if (sdxl_) v += BXL[c];
                v = (v + 1.f) * 127.5f;
                rgb[i * 3 + c] = (uint8_t)std::max(0.f, std::min(255.f, v));
            }
        }
        cb(rgb.data(), lw_, lh_);
    }

    VARP stepPlms(VARP sample, VARP out, int index) {
        int timestep = ts_[index];
        int prev = index + 1 < (int)ts_.size() ? ts_[index + 1] : 0;
        if (index != 1) {
            if (ets_.size() >= 4) ets_[ets_.size() - 4] = nullptr;
            ets_.push_back(out);
        } else {
            timestep = ts_[0];
            prev = ts_.size() > 1 ? ts_[1] : 0;
        }
        int e = (int)ets_.size() - 1;
        if (index == 0) {
            keep_ = sample;
        } else if (index == 1) {
            out = (out + ets_[e]) * _Const(0.5f);
            sample = keep_;
        } else if (e == 1) {
            out = (_Const(3.f) * ets_[e] - ets_[e - 1]) * _Const(0.5f);
        } else if (e == 2) {
            out = (_Const(23.f) * ets_[e] - _Const(16.f) * ets_[e - 1] + _Const(5.f) * ets_[e - 2]) * _Const(1.f / 12.f);
        } else if (e >= 3) {
            out = _Const(1.f / 24.f) * (_Const(55.f) * ets_[e] - _Const(59.f) * ets_[e - 1] + _Const(37.f) * ets_[e - 2] - _Const(9.f) * ets_[e - 3]);
        }
        float at = alphas_[timestep], ap = alphas_[prev];
        float bt = 1 - at, bp = 1 - ap;
        float sc = std::sqrt(ap / at);
        float denom = at * std::sqrt(bp) + std::sqrt(at * bt * ap);
        return _Scalar(sc) * sample - _Scalar((ap - at) / denom) * out;
    }

    int mode_ = 0;
    bool dynamic_ = false;
    bool sdxl_ = false;
    bool decodeBad_ = false;
    bool lowRam_ = false;
    bool hasEncoderFile_ = false;
    float scale_ = 0.f;
    std::string dir_;
    int lw_ = 64, lh_ = 64;
    std::shared_ptr<Executor::RuntimeManager> rt_;
    std::shared_ptr<Module> te_, unet_, dec_, enc_;
    std::unique_ptr<CLIPTokenizer> tok_;
    VARP promptVar_, timestepVar_, keep_;
    std::vector<float> alphas_;
    std::vector<int> ts_;
    std::vector<VARP> ets_;
};

std::mutex gLock;
std::unique_ptr<Pipeline> gPipe;
std::string gPath;
int gBackend = -1, gMode = -1, gDynamic = -1;

std::string str(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

} // namespace

// Returns 0 ok, 2 model failed to load, 3 generation failed, 4 photo given but no encoder,
// 5 size not supported by this model, 6 cancelled.
extern "C" JNIEXPORT jint JNICALL
Java_com_nitido_app_SdNative_generate(JNIEnv* env, jclass, jstring jdir, jstring jcache, jint backend, jint mode,
                                      jboolean dynamic, jint width, jint height,
                                      jint steps, jint seed, jstring jout, jstring jprompt, jstring jnegative,
                                      jfloatArray jimage, jfloat strength, jfloat guidance, jfloat imageGuidance,
                                      jfloatArray jmaskedImage, jfloatArray jlatMask,
                                      jint previewMode, jint sampler, jobject listener) {
    std::lock_guard<std::mutex> lock(gLock);
    gCancel = false;
    std::string dir = str(env, jdir), out = str(env, jout), prompt = str(env, jprompt), negative = str(env, jnegative);
    std::string cache = str(env, jcache);
    if (!cache.empty() && chdir(cache.c_str()) != 0) LOGI("chdir failed");   // OpenCL tuning cache goes here

    int dyn = dynamic ? 1 : 0;
    if (!gPipe || dir != gPath || backend != gBackend || mode != gMode || dyn != gDynamic) {
        gPipe.reset();
        LOGI("loading model from %s backend %d mode %d dynamic %d", dir.c_str(), backend, mode, dyn);
        std::unique_ptr<Pipeline> p(new Pipeline);
        if (!p->load(dir, backend, mode, dynamic)) return LOAD_FAILED;
        gPipe = std::move(p);
        gPath = dir;
        gBackend = backend;
        gMode = mode;
        gDynamic = dyn;
    }
    if (gCancel) return CANCELLED;

    std::vector<float> image;
    if (jimage) {
        if (!gPipe->hasEncoder()) return NO_ENCODER;
        if (env->GetArrayLength(jimage) != 3 * width * height) return RUN_FAILED;
        image.resize(3 * width * height);
        env->GetFloatArrayRegion(jimage, 0, (jsize)image.size(), image.data());
    }

    std::vector<float> maskedImage, latMask;
    if (jmaskedImage) {
        if (env->GetArrayLength(jmaskedImage) != 3 * width * height) return RUN_FAILED;
        maskedImage.resize(3 * width * height);
        env->GetFloatArrayRegion(jmaskedImage, 0, (jsize)maskedImage.size(), maskedImage.data());
    }
    if (jlatMask) {
        if (env->GetArrayLength(jlatMask) != (width / 8) * (height / 8)) return RUN_FAILED;
        latMask.resize((width / 8) * (height / 8));
        env->GetFloatArrayRegion(jlatMask, 0, (jsize)latMask.size(), latMask.data());
    }

    jclass cls = env->GetObjectClass(listener);
    jmethodID onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    jmethodID onPreview = env->GetMethodID(cls, "onPreview", "([BII)V");
    if (env->ExceptionCheck()) { env->ExceptionClear(); onPreview = nullptr; }
    auto cb = [&](int pct) {
        if (onProgress) env->CallVoidMethod(listener, onProgress, (jint)pct);
        if (env->ExceptionCheck()) env->ExceptionClear();
    };
    auto pv = [&](const uint8_t* rgb, int w, int h) {
        if (!onPreview) return;
        jsize n = w * h * 3;
        jbyteArray arr = env->NewByteArray(n);
        if (!arr) { env->ExceptionClear(); return; }
        env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte*>(rgb));
        env->CallVoidMethod(listener, onPreview, arr, (jint)w, (jint)h);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(arr);
    };
    return gPipe->run(prompt, negative, out, width, height, steps, seed, image.empty() ? nullptr : image.data(),
                      strength, guidance, imageGuidance,
                      maskedImage.empty() ? nullptr : maskedImage.data(), latMask.empty() ? nullptr : latMask.data(),
                      cb, previewMode, pv, sampler);
}

// Ask a running generation to stop at the next step. Safe to call from any thread.
extern "C" JNIEXPORT void JNICALL
Java_com_nitido_app_SdNative_cancel(JNIEnv*, jclass) {
    gCancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nitido_app_SdNative_release(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gLock);
    gPipe.reset();
    gBackend = -1;
    gMode = -1;
    gDynamic = -1;
}

// ---------------------------------------------------------------------------------------------
// Upscalers (ESRGAN family) converted to MNN: one fixed-size tile in, one bigger tile out.
// Uses the plain Interpreter/Session API so it never touches the Stable Diffusion pipeline.
// ---------------------------------------------------------------------------------------------
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>

namespace {
struct UpNet {
    std::shared_ptr<Interpreter> net;
    Session* session = nullptr;
    Tensor* in = nullptr;
    Tensor* out = nullptr;
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nitido_app_MnnTile_nOpen(JNIEnv* env, jclass, jstring jpath, jboolean gpu, jstring jcache, jboolean precise) {
    std::string path = str(env, jpath);
    std::string cache = str(env, jcache);
    std::unique_ptr<UpNet> u(new UpNet);
    u->net.reset(Interpreter::createFromFile(path.c_str()), Interpreter::destroy);
    if (!u->net) return 0;
    if (!cache.empty()) u->net->setCacheFile((cache + "/up_" + std::to_string(std::hash<std::string>{}(path)) + ".cache").c_str());
    ScheduleConfig cfg;
    BackendConfig bc;
    // ESRGAN is fine in fp16; the restoration models (attention, FFT as matrices) keep fp32
    bc.precision = precise ? BackendConfig::Precision_High : BackendConfig::Precision_Low;
    bc.memory = BackendConfig::Memory_Normal;
    if (gpu) {
        cfg.type = MNN_FORWARD_OPENCL;
        cfg.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
        cfg.backupType = MNN_FORWARD_CPU;
    } else {
        cfg.type = MNN_FORWARD_CPU;
        cfg.numThread = 4;
    }
    cfg.backendConfig = &bc;
    u->session = u->net->createSession(cfg);
    if (!u->session) return 0;
    u->in = u->net->getSessionInput(u->session, nullptr);
    u->out = u->net->getSessionOutput(u->session, nullptr);
    if (!u->in || !u->out) return 0;
    u->net->updateCacheFile(u->session);
    return reinterpret_cast<jlong>(u.release());
}

// [n, c, h, w] of the input (which = 0) or output (which = 1)
extern "C" JNIEXPORT jintArray JNICALL
Java_com_nitido_app_MnnTile_nShape(JNIEnv* env, jclass, jlong h, jint which) {
    auto* u = reinterpret_cast<UpNet*>(h);
    Tensor* t = which == 0 ? u->in : u->out;
    auto s = t->shape();
    jint v[4] = {1, 3, 0, 0};
    for (int i = 0; i < 4 && i < (int)s.size(); i++) v[i] = s[i];
    jintArray arr = env->NewIntArray(4);
    env->SetIntArrayRegion(arr, 0, 4, v);
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nitido_app_MnnTile_nRun(JNIEnv* env, jclass, jlong h, jfloatArray jin, jfloatArray jout) {
    auto* u = reinterpret_cast<UpNet*>(h);
    std::unique_ptr<Tensor> hostIn(new Tensor(u->in, Tensor::CAFFE));
    if (env->GetArrayLength(jin) != (jsize)hostIn->elementSize()) return JNI_FALSE;
    env->GetFloatArrayRegion(jin, 0, (jsize)hostIn->elementSize(), hostIn->host<float>());
    u->in->copyFromHostTensor(hostIn.get());
    if (u->net->runSession(u->session) != NO_ERROR) return JNI_FALSE;
    std::unique_ptr<Tensor> hostOut(new Tensor(u->out, Tensor::CAFFE));
    u->out->copyToHostTensor(hostOut.get());
    if (env->GetArrayLength(jout) != (jsize)hostOut->elementSize()) return JNI_FALSE;
    env->SetFloatArrayRegion(jout, 0, (jsize)hostOut->elementSize(), hostOut->host<float>());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nitido_app_MnnTile_nClose(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<UpNet*>(h);
}
