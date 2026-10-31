#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <d3d11.h>
#include <d3d11_4.h>
#include <dxgi.h>
#include <jawt.h>
#include <jawt_md.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <deque>
#include <array>
#include <intrin.h>
#include <functional>
#include <memory>
#include <mutex>
#include <unordered_map>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")

namespace {

struct InputViewKey {
    uintptr_t texture{};
    UINT arraySlice{};
    DXGI_FORMAT format{DXGI_FORMAT_UNKNOWN};

    bool operator==(const InputViewKey& other) const noexcept {
        return texture == other.texture && arraySlice == other.arraySlice
                && format == other.format;
    }
};

struct InputViewKeyHash {
    size_t operator()(const InputViewKey& key) const noexcept {
        size_t value = std::hash<uintptr_t>{}(key.texture);
        value ^= std::hash<UINT>{}(key.arraySlice) + 0x9e3779b9 + (value << 6) + (value >> 2);
        value ^= std::hash<UINT>{}(static_cast<UINT>(key.format)) + 0x9e3779b9 + (value << 6) + (value >> 2);
        return value;
    }
};

struct FrameSlot {
    ID3D11Texture2D* texture{};
    ID3D11RenderTargetView* renderTarget{};
    ID3D11VideoProcessorOutputView* outputView{};
    ID3D11Query* completionQuery{};
    jlong token{};

    void release() {
        if (completionQuery) { completionQuery->Release(); completionQuery = nullptr; }
        if (outputView) { outputView->Release(); outputView = nullptr; }
        if (renderTarget) { renderTarget->Release(); renderTarget = nullptr; }
        if (texture) { texture->Release(); texture = nullptr; }
        token = 0;
    }
};

/**
 * One process-wide D3D11 device/context shared by every per-window renderer.
 * Each renderer still owns its swap chain, video processor, frame slots and
 * input-view cache; only the expensive device/context objects are shared.
 */
struct SharedD3D11Device {
    static constexpr size_t MAX_SHARED_DEVICES = 8;

    struct State {
        ID3D11Device* device{};
        ID3D11DeviceContext* context{};
        ID3D11VideoDevice* videoDevice{};
        ID3D11VideoContext* videoContext{};
        size_t references{};
    };

    static std::mutex& poolMutex() {
        // Keep manager state alive until the DLL is unloaded. Renderer objects
        // are stored in a translation-unit global map whose destruction order
        // must not race this function-local state.
        static auto* value = new std::mutex();
        return *value;
    }

    static std::array<State, MAX_SHARED_DEVICES>& states() {
        static auto* value = new std::array<State, MAX_SHARED_DEVICES>();
        return *value;
    }

    static bool acquire(ID3D11Device** outDevice,
                        ID3D11DeviceContext** outContext,
                        ID3D11VideoDevice** outVideoDevice,
                        ID3D11VideoContext** outVideoContext,
                        size_t* outIndex) {
        std::lock_guard<std::mutex> guard(poolMutex());
        auto& pool = states();
        size_t index = 0;
        for (size_t i = 1; i < pool.size(); ++i) {
            if (pool[i].references < pool[index].references) index = i;
        }
        State& shared = pool[index];

        if (!shared.device) {
            const D3D_FEATURE_LEVEL levels[] = {
                D3D_FEATURE_LEVEL_11_1,
                D3D_FEATURE_LEVEL_11_0,
                D3D_FEATURE_LEVEL_10_1,
                D3D_FEATURE_LEVEL_10_0
            };
            D3D_FEATURE_LEVEL selected{};
            ID3D11Device* device = nullptr;
            ID3D11DeviceContext* context = nullptr;
            HRESULT hr = D3D11CreateDevice(
                nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
                levels, ARRAYSIZE(levels), D3D11_SDK_VERSION,
                &device, &selected, &context);
            if (FAILED(hr)) {
                const D3D_FEATURE_LEVEL fallback[] = {
                    D3D_FEATURE_LEVEL_11_0,
                    D3D_FEATURE_LEVEL_10_1,
                    D3D_FEATURE_LEVEL_10_0
                };
                hr = D3D11CreateDevice(
                    nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                    D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
                    fallback, ARRAYSIZE(fallback), D3D11_SDK_VERSION,
                    &device, &selected, &context);
            }
            if (FAILED(hr)) {
                if (context) context->Release();
                if (device) device->Release();
                return false;
            }

            ID3D11VideoDevice* videoDevice = nullptr;
            ID3D11VideoContext* videoContext = nullptr;
            if (FAILED(device->QueryInterface(__uuidof(ID3D11VideoDevice),
                    reinterpret_cast<void**>(&videoDevice)))
                    || FAILED(context->QueryInterface(__uuidof(ID3D11VideoContext),
                    reinterpret_cast<void**>(&videoContext)))) {
                if (videoContext) videoContext->Release();
                if (videoDevice) videoDevice->Release();
                context->Release();
                device->Release();
                return false;
            }

            // FFmpeg decoding and renderers assigned to this pool entry share
            // the immediate context. Enable D3D11's built-in serialization.
            ID3D11Multithread* multithread = nullptr;
            if (SUCCEEDED(context->QueryInterface(__uuidof(ID3D11Multithread),
                    reinterpret_cast<void**>(&multithread)))) {
                multithread->SetMultithreadProtected(TRUE);
                multithread->Release();
            }

            shared.device = device;
            shared.context = context;
            shared.videoDevice = videoDevice;
            shared.videoContext = videoContext;
        }

        shared.device->AddRef();
        shared.context->AddRef();
        shared.videoDevice->AddRef();
        shared.videoContext->AddRef();
        *outDevice = shared.device;
        *outContext = shared.context;
        *outVideoDevice = shared.videoDevice;
        *outVideoContext = shared.videoContext;
        *outIndex = index;
        ++shared.references;
        return true;
    }

    static void release(size_t index,
                        ID3D11Device*& device,
                        ID3D11DeviceContext*& context,
                        ID3D11VideoDevice*& videoDevice,
                        ID3D11VideoContext*& videoContext) {
        std::lock_guard<std::mutex> guard(poolMutex());
        auto& pool = states();
        if (index >= pool.size()) return;
        State& shared = pool[index];

        if (videoContext) { videoContext->Release(); videoContext = nullptr; }
        if (videoDevice) { videoDevice->Release(); videoDevice = nullptr; }
        if (context) { context->Release(); context = nullptr; }
        if (device) { device->Release(); device = nullptr; }

        if (shared.references > 0) --shared.references;
        if (shared.references == 0) {
            if (shared.videoContext) { shared.videoContext->Release(); shared.videoContext = nullptr; }
            if (shared.videoDevice) { shared.videoDevice->Release(); shared.videoDevice = nullptr; }
            if (shared.context) { shared.context->Release(); shared.context = nullptr; }
            if (shared.device) { shared.device->Release(); shared.device = nullptr; }
        }
    }
};

struct Renderer {
    static constexpr size_t MAX_IN_FLIGHT_FRAMES = 3;
    HWND hwnd{};
    ID3D11Device* device{};
    ID3D11DeviceContext* context{};
    ID3D11VideoDevice* videoDevice{};
    ID3D11VideoContext* videoContext{};
    size_t sharedDeviceIndex{SharedD3D11Device::MAX_SHARED_DEVICES};
    IDXGISwapChain* swapChain{};
    ID3D11RenderTargetView* renderTarget{};
    std::unordered_map<InputViewKey, ID3D11VideoProcessorInputView*, InputViewKeyHash> inputViews;
    std::array<FrameSlot, MAX_IN_FLIGHT_FRAMES> frameSlots{};
    std::deque<size_t> inFlightSlots;
    std::deque<jlong> completedTokens;
    jlong nextFrameToken{1};
    ID3D11VideoProcessorEnumerator* processorEnumerator{};
    ID3D11VideoProcessor* processor{};
    std::mutex mutex;
    UINT width{};
    UINT height{};
    UINT processorWidth{};
    UINT processorHeight{};
    UINT processorOutputWidth{};
    UINT processorOutputHeight{};
    DXGI_FORMAT processorFormat{DXGI_FORMAT_UNKNOWN};

    ~Renderer() {
        drainInFlight(false);
        releaseProcessor();
        detachSurface();
        if (sharedDeviceIndex < SharedD3D11Device::MAX_SHARED_DEVICES) {
            SharedD3D11Device::release(sharedDeviceIndex, device, context, videoDevice, videoContext);
        }
    }

    void releaseInputViews() {
        for (auto& entry : inputViews) {
            if (entry.second) entry.second->Release();
        }
        inputViews.clear();
    }

    void releaseFrameSlots() {
        for (auto& slot : frameSlots) slot.release();
        inFlightSlots.clear();
    }

    bool ensureFrameSlot(FrameSlot& slot) {
        if (slot.texture && slot.renderTarget && slot.outputView && slot.completionQuery) {
            return true;
        }
        slot.release();

        D3D11_TEXTURE2D_DESC textureDesc{};
        textureDesc.Width = width;
        textureDesc.Height = height;
        textureDesc.MipLevels = 1;
        textureDesc.ArraySize = 1;
        textureDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        textureDesc.SampleDesc.Count = 1;
        textureDesc.Usage = D3D11_USAGE_DEFAULT;
        textureDesc.BindFlags = D3D11_BIND_RENDER_TARGET;
        HRESULT hr = device->CreateTexture2D(&textureDesc, nullptr, &slot.texture);
        if (FAILED(hr)) { slot.release(); return false; }

        hr = device->CreateRenderTargetView(slot.texture, nullptr, &slot.renderTarget);
        if (FAILED(hr)) { slot.release(); return false; }

        D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outputDesc{};
        outputDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
        outputDesc.Texture2D.MipSlice = 0;
        hr = videoDevice->CreateVideoProcessorOutputView(
            slot.texture, processorEnumerator, &outputDesc, &slot.outputView);
        if (FAILED(hr)) { slot.release(); return false; }

        D3D11_QUERY_DESC queryDesc{};
        queryDesc.Query = D3D11_QUERY_EVENT;
        hr = device->CreateQuery(&queryDesc, &slot.completionQuery);
        if (FAILED(hr)) { slot.release(); return false; }
        return true;
    }

    bool hasInFlightFrames() const { return !inFlightSlots.empty(); }

    void drainInFlight(bool present) {
        while (!inFlightSlots.empty()) {
            const size_t index = inFlightSlots.front();
            FrameSlot& slot = frameSlots[index];
            HRESULT completion = S_FALSE;
            while (completion == S_FALSE) {
                completion = context->GetData(slot.completionQuery, nullptr, 0, 0);
                if (completion == S_FALSE) Sleep(1);
            }
            bool shown = false;
            if (SUCCEEDED(completion) && present && swapChain && hwnd && IsWindow(hwnd)) {
                ID3D11Texture2D* backBuffer = nullptr;
                if (SUCCEEDED(swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D),
                        reinterpret_cast<void**>(&backBuffer)))) {
                    context->CopyResource(backBuffer, slot.texture);
                    backBuffer->Release();
                    shown = SUCCEEDED(swapChain->Present(0, 0));
                }
            }
            completedTokens.push_back(shown ? slot.token : -slot.token);
            slot.release();
            inFlightSlots.pop_front();
        }
    }

    jlong pollCompletedFrame() {
        if (!completedTokens.empty()) {
            const jlong token = completedTokens.front();
            completedTokens.pop_front();
            return token;
        }
        if (inFlightSlots.empty()) return 0;

        const size_t index = inFlightSlots.front();
        FrameSlot& slot = frameSlots[index];
        const HRESULT completion = context->GetData(slot.completionQuery, nullptr, 0, 0);
        if (completion == S_FALSE) return 0;

        bool shown = false;
        if (SUCCEEDED(completion) && swapChain && hwnd && IsWindow(hwnd)) {
            ID3D11Texture2D* backBuffer = nullptr;
            if (SUCCEEDED(swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D),
                    reinterpret_cast<void**>(&backBuffer)))) {
                context->CopyResource(backBuffer, slot.texture);
                backBuffer->Release();
                shown = SUCCEEDED(swapChain->Present(0, 0));
            }
        }
        const jlong token = shown ? slot.token : -slot.token;
        slot.release();
        inFlightSlots.pop_front();
        return token;
    }

    bool ensureInputView(ID3D11Texture2D* texture, uintptr_t textureAddress,
                         UINT arraySlice, DXGI_FORMAT format,
                         ID3D11VideoProcessorInputView** result) {
        const InputViewKey key{textureAddress, arraySlice, format};
        auto found = inputViews.find(key);
        if (found != inputViews.end()) {
            *result = found->second;
            return true;
        }

        D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC desc{};
        desc.FourCC = 0;
        desc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
        desc.Texture2D.MipSlice = 0;
        desc.Texture2D.ArraySlice = arraySlice;
        ID3D11VideoProcessorInputView* view = nullptr;
        HRESULT hr = videoDevice->CreateVideoProcessorInputView(
            texture, processorEnumerator, &desc, &view);
        if (FAILED(hr)) return false;

        // A cached view retains its underlying decoder texture. Bound the cache
        // so unusual drivers/stream reconfiguration cannot grow native memory
        // without limit. The caller applies backpressure at the limit while GPU
        // submissions are in flight, so an input view is evicted only when safe.
        constexpr size_t MAX_CACHED_INPUT_VIEWS = 32;
        if (inputViews.size() >= MAX_CACHED_INPUT_VIEWS) {
            auto entry = inputViews.begin();
            if (entry->second) entry->second->Release();
            inputViews.erase(entry);
        }
        inputViews.emplace(key, view);
        *result = view;
        return true;
    }

    void releaseProcessor() {
        releaseInputViews();
        releaseFrameSlots();
        if (processor) {
            processor->Release();
            processor = nullptr;
        }
        if (processorEnumerator) {
            processorEnumerator->Release();
            processorEnumerator = nullptr;
        }
        processorWidth = processorHeight = 0;
        processorOutputWidth = processorOutputHeight = 0;
        processorFormat = DXGI_FORMAT_UNKNOWN;
    }

    bool createDevice() {
        return SharedD3D11Device::acquire(
            &device, &context, &videoDevice, &videoContext, &sharedDeviceIndex);
    }

    bool attachSurface(HWND target) {
        if (!device || !target || !IsWindow(target)) return false;
        hwnd = target;

        RECT rect{};
        if (!GetClientRect(hwnd, &rect)) return false;
        width = static_cast<UINT>(std::max<LONG>(1, rect.right - rect.left));
        height = static_cast<UINT>(std::max<LONG>(1, rect.bottom - rect.top));

        IDXGIDevice* dxgiDevice = nullptr;
        IDXGIAdapter* adapter = nullptr;
        IDXGIFactory* factory = nullptr;
        HRESULT hr = device->QueryInterface(__uuidof(IDXGIDevice),
                reinterpret_cast<void**>(&dxgiDevice));
        if (SUCCEEDED(hr)) hr = dxgiDevice->GetAdapter(&adapter);
        if (SUCCEEDED(hr)) hr = adapter->GetParent(__uuidof(IDXGIFactory),
                reinterpret_cast<void**>(&factory));

        if (SUCCEEDED(hr)) {
            DXGI_SWAP_CHAIN_DESC desc{};
            desc.BufferDesc.Width = width;
            desc.BufferDesc.Height = height;
            desc.BufferDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
            desc.SampleDesc.Count = 1;
            desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
            desc.BufferCount = 2;
            desc.OutputWindow = hwnd;
            desc.Windowed = TRUE;
            desc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
            hr = factory->CreateSwapChain(device, &desc, &swapChain);
        }

        if (factory) factory->Release();
        if (adapter) adapter->Release();
        if (dxgiDevice) dxgiDevice->Release();
        if (FAILED(hr)) {
            hwnd = nullptr;
            return false;
        }
        return createRenderTarget();
    }

    bool createRenderTarget() {
        if (!swapChain) return false;
        ID3D11Texture2D* backBuffer = nullptr;
        HRESULT hr = swapChain->GetBuffer(
            0, __uuidof(ID3D11Texture2D),
            reinterpret_cast<void**>(&backBuffer));
        if (FAILED(hr)) return false;
        hr = device->CreateRenderTargetView(backBuffer, nullptr, &renderTarget);
        backBuffer->Release();
        return SUCCEEDED(hr);
    }

    void detachSurface() {
        // Reparenting may detach the HWND while frames are still executing.
        // Wait for those queries, publish failed completion tokens for Java to
        // release its retained AVFrames, then tear down the surface resources.
        drainInFlight(false);
        releaseFrameSlots();
        if (renderTarget) {
            renderTarget->Release();
            renderTarget = nullptr;
        }
        if (swapChain) {
            swapChain->Release();
            swapChain = nullptr;
        }
        hwnd = nullptr;
        width = height = 0;
    }

    bool resizeIfNeeded() {
        if (!hwnd || !IsWindow(hwnd) || !swapChain) return false;
        RECT rect{};
        if (!GetClientRect(hwnd, &rect)) return false;
        const UINT newWidth = static_cast<UINT>(rect.right - rect.left);
        const UINT newHeight = static_cast<UINT>(rect.bottom - rect.top);
        if (newWidth == 0 || newHeight == 0) return false;
        if (newWidth == width && newHeight == height) return true;
        if (hasInFlightFrames()) return false;

        releaseFrameSlots();
        if (renderTarget) {
            renderTarget->Release();
            renderTarget = nullptr;
        }
        HRESULT hr = swapChain->ResizeBuffers(
            0, newWidth, newHeight, DXGI_FORMAT_UNKNOWN, 0);
        if (FAILED(hr)) return false;
        width = newWidth;
        height = newHeight;
        return createRenderTarget();
    }

    bool ensureVideoProcessor(UINT frameWidth, UINT frameHeight, DXGI_FORMAT inputFormat) {
        if (processor && processorWidth == frameWidth && processorHeight == frameHeight
                && processorFormat == inputFormat
                && processorOutputWidth == width && processorOutputHeight == height) {
            UINT support = 0;
            if (SUCCEEDED(processorEnumerator->CheckVideoProcessorFormat(inputFormat, &support))
                    && (support & D3D11_VIDEO_PROCESSOR_FORMAT_SUPPORT_INPUT)) {
                return true;
            }
            return false;
        }

        releaseProcessor();
        D3D11_VIDEO_PROCESSOR_CONTENT_DESC desc{};
        desc.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
        desc.InputFrameRate = {60, 1};
        desc.InputWidth = frameWidth;
        desc.InputHeight = frameHeight;
        desc.OutputFrameRate = {60, 1};
        desc.OutputWidth = width;
        desc.OutputHeight = height;
        desc.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;

        HRESULT hr = videoDevice->CreateVideoProcessorEnumerator(
            &desc, &processorEnumerator);
        if (FAILED(hr)) return false;

        UINT inputSupport = 0;
        UINT outputSupport = 0;
        if (FAILED(processorEnumerator->CheckVideoProcessorFormat(inputFormat, &inputSupport))
                || !(inputSupport & D3D11_VIDEO_PROCESSOR_FORMAT_SUPPORT_INPUT)
                || FAILED(processorEnumerator->CheckVideoProcessorFormat(
                        DXGI_FORMAT_B8G8R8A8_UNORM, &outputSupport))
                || !(outputSupport & D3D11_VIDEO_PROCESSOR_FORMAT_SUPPORT_OUTPUT)) {
            releaseProcessor();
            return false;
        }

        hr = videoDevice->CreateVideoProcessor(processorEnumerator, 0, &processor);
        if (FAILED(hr)) {
            releaseProcessor();
            return false;
        }
        processorWidth = frameWidth;
        processorHeight = frameHeight;
        processorOutputWidth = width;
        processorOutputHeight = height;
        processorFormat = inputFormat;
        return true;
    }

    jlong submitHardware(uintptr_t textureAddress, uintptr_t arrayIndex,
                         UINT frameWidth, UINT frameHeight,
                         int colorSpace, int colorRange) {
        if (!hwnd || !IsWindow(hwnd) || textureAddress == 0
                || frameWidth == 0 || frameHeight == 0) return -1;

        RECT rect{};
        if (!GetClientRect(hwnd, &rect)) return -1;
        const UINT requestedWidth = static_cast<UINT>(std::max<LONG>(1, rect.right - rect.left));
        const UINT requestedHeight = static_cast<UINT>(std::max<LONG>(1, rect.bottom - rect.top));
        if ((requestedWidth != width || requestedHeight != height) && hasInFlightFrames()) {
            return 0; // Backpressure until the old-size GPU submissions complete.
        }
        if (!resizeIfNeeded()) return hasInFlightFrames() ? 0 : -1;

        auto* texture = reinterpret_cast<ID3D11Texture2D*>(textureAddress);
        D3D11_TEXTURE2D_DESC textureDesc{};
        texture->GetDesc(&textureDesc);

        if (processor && (processorWidth != frameWidth || processorHeight != frameHeight
                || processorFormat != textureDesc.Format
                || processorOutputWidth != width || processorOutputHeight != height)
                && hasInFlightFrames()) {
            return 0;
        }
        if (!ensureVideoProcessor(frameWidth, frameHeight, textureDesc.Format)) return -1;

        size_t slotIndex = frameSlots.size();
        for (size_t i = 0; i < frameSlots.size(); ++i) {
            if (frameSlots[i].token == 0) { slotIndex = i; break; }
        }
        if (slotIndex == frameSlots.size()) return 0;

        FrameSlot& slot = frameSlots[slotIndex];
        if (!ensureFrameSlot(slot)) return -1;

        ID3D11VideoProcessorInputView* inputView = nullptr;
        if (inputViews.size() >= 32 && hasInFlightFrames()) return 0;
        if (!ensureInputView(texture, textureAddress, static_cast<UINT>(arrayIndex),
                             textureDesc.Format, &inputView)) return -1;

        // Fill the complete native surface. The previous aspect-fit path
        // centered a smaller destination rectangle and exposed black bars
        // whenever the Android frame and Swing viewport had slightly
        // different aspect ratios. Crop the source centrally instead, keeping
        // the original aspect ratio without distorting the device image.
        const double targetAspect = width / static_cast<double>(height);
        const double sourceAspect = frameWidth / static_cast<double>(frameHeight);
        RECT sourceRect{0, 0, static_cast<LONG>(frameWidth), static_cast<LONG>(frameHeight)};
        if (sourceAspect < targetAspect) {
            const LONG cropHeight = static_cast<LONG>(frameWidth / targetAspect + 0.5);
            sourceRect.top = (static_cast<LONG>(frameHeight) - cropHeight) / 2;
            sourceRect.bottom = sourceRect.top + cropHeight;
        } else if (sourceAspect > targetAspect) {
            const LONG cropWidth = static_cast<LONG>(frameHeight * targetAspect + 0.5);
            sourceRect.left = (static_cast<LONG>(frameWidth) - cropWidth) / 2;
            sourceRect.right = sourceRect.left + cropWidth;
        }
        const RECT destRect{0, 0, static_cast<LONG>(width), static_cast<LONG>(height)};

        D3D11_VIDEO_PROCESSOR_COLOR_SPACE inputColor{};
        inputColor.YCbCr_Matrix = (colorSpace == 1 || colorSpace == 9 || colorSpace == 10) ? 1 : 0;
        inputColor.Nominal_Range = colorRange == 2 ? 2 : 1;
        D3D11_VIDEO_PROCESSOR_COLOR_SPACE outputColor{};
        outputColor.RGB_Range = 0;

        videoContext->VideoProcessorSetStreamFrameFormat(
            processor, 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
        videoContext->VideoProcessorSetStreamSourceRect(processor, 0, TRUE, &sourceRect);
        const RECT outputRect{0, 0, static_cast<LONG>(width), static_cast<LONG>(height)};
        videoContext->VideoProcessorSetStreamDestRect(processor, 0, TRUE, &destRect);
        videoContext->VideoProcessorSetOutputTargetRect(processor, TRUE, &outputRect);
        videoContext->VideoProcessorSetStreamColorSpace(processor, 0, &inputColor);
        videoContext->VideoProcessorSetOutputColorSpace(processor, &outputColor);

        const float black[4] = {0.f, 0.f, 0.f, 1.f};
        context->OMSetRenderTargets(1, &slot.renderTarget, nullptr);
        context->ClearRenderTargetView(slot.renderTarget, black);
        context->OMSetRenderTargets(0, nullptr, nullptr);

        D3D11_VIDEO_PROCESSOR_STREAM stream{};
        stream.Enable = TRUE;
        stream.OutputIndex = 0;
        stream.InputFrameOrField = 0;
        stream.PastFrames = 0;
        stream.FutureFrames = 0;
        stream.pInputSurface = inputView;
        const HRESULT hr = videoContext->VideoProcessorBlt(
            processor, slot.outputView, 0, 1, &stream);
        if (FAILED(hr)) return -1;

        const jlong token = nextFrameToken++;
        slot.token = token;
        context->End(slot.completionQuery);
        // GetData(flags=0) in the shared poller flushes pending commands when
        // required. Avoid forcing a driver submission/flush for every frame.
        inFlightSlots.push_back(slotIndex);
        return token;
    }

    void renderTestColor() {
        if (!resizeIfNeeded() || !renderTarget) return;
        const float color[4] = {0.035f, 0.12f, 0.22f, 1.0f};
        context->OMSetRenderTargets(1, &renderTarget, nullptr);
        context->ClearRenderTargetView(renderTarget, color);
        swapChain->Present(0, 0);
    }
};

std::mutex g_mutex;
std::unordered_map<jlong, std::shared_ptr<Renderer>> g_renderers;
std::atomic<jlong> g_nextHandle{1};

std::shared_ptr<Renderer> findRenderer(jlong handle) {
    std::lock_guard<std::mutex> guard(g_mutex);
    auto it = g_renderers.find(handle);
    return it == g_renderers.end() ? nullptr : it->second;
}

void throwJava(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}

HWND getCanvasHwnd(JNIEnv* env, jobject canvas) {
    JAWT awt{};
    awt.version = JAWT_VERSION_9;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
        throwJava(env, "JAWT_GetAWT failed");
        return nullptr;
    }

    JAWT_DrawingSurface* surface = awt.GetDrawingSurface(env, canvas);
    if (!surface) {
        throwJava(env, "JAWT could not acquire the Canvas drawing surface");
        return nullptr;
    }

    HWND hwnd = nullptr;
    const jint lock = surface->Lock(surface);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo* info = surface->GetDrawingSurfaceInfo(surface);
        if (info && info->platformInfo) {
            auto* winInfo =
                static_cast<JAWT_Win32DrawingSurfaceInfo*>(info->platformInfo);
            hwnd = winInfo->hwnd;
            surface->FreeDrawingSurfaceInfo(info);
        }
        surface->Unlock(surface);
    }

    awt.FreeDrawingSurface(surface);
    if (!hwnd) throwJava(env, "JAWT returned an empty Canvas HWND");
    return hwnd;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_attach0(
    JNIEnv* env, jclass, jobject canvas) {
    HWND hwnd = getCanvasHwnd(env, canvas);
    if (!hwnd) return 0;

    auto renderer = std::make_shared<Renderer>();
    if (!renderer->createDevice() || !renderer->attachSurface(hwnd)) {
        throwJava(env, "D3D11 device, video processor, or swap-chain initialization failed");
        return 0;
    }

    const jlong handle = g_nextHandle.fetch_add(1);
    std::lock_guard<std::mutex> guard(g_mutex);
    g_renderers.emplace(handle, std::move(renderer));
    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_attachSurface0(
    JNIEnv* env, jclass, jlong handle, jobject canvas) {
    HWND hwnd = getCanvasHwnd(env, canvas);
    if (!hwnd) return;
    auto renderer = findRenderer(handle);
    if (!renderer) {
        throwJava(env, "D3D11 renderer handle is no longer valid");
        return;
    }
    std::lock_guard<std::mutex> guard(renderer->mutex);
    if (!renderer->attachSurface(hwnd)) {
        throwJava(env, "Could not reattach D3D11 swap chain to the Canvas HWND");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_detachSurface0(
    JNIEnv*, jclass, jlong handle) {
    auto renderer = findRenderer(handle);
    if (!renderer) return;
    std::lock_guard<std::mutex> guard(renderer->mutex);
    renderer->detachSurface();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_device0(
    JNIEnv*, jclass, jlong handle) {
    auto renderer = findRenderer(handle);
    if (!renderer) return 0;
    std::lock_guard<std::mutex> guard(renderer->mutex);
    if (!renderer->device) return 0;
    renderer->device->AddRef();
    return reinterpret_cast<jlong>(renderer->device);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_releaseDevicePointer0(
    JNIEnv*, jclass, jlong pointer) {
    auto* device = reinterpret_cast<ID3D11Device*>(pointer);
    if (device) device->Release();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_submitHardware0(
    JNIEnv*, jclass, jlong handle, jlong texture, jlong arrayIndex,
    jint width, jint height, jint colorSpace, jint colorRange) {
    auto renderer = findRenderer(handle);
    if (!renderer) return -1;
    std::lock_guard<std::mutex> guard(renderer->mutex);
    return renderer->submitHardware(
        static_cast<uintptr_t>(texture), static_cast<uintptr_t>(arrayIndex),
        static_cast<UINT>(width), static_cast<UINT>(height),
        colorSpace, colorRange);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_pollCompleted0(
    JNIEnv*, jclass, jlong handle) {
    auto renderer = findRenderer(handle);
    if (!renderer) return 0;
    std::lock_guard<std::mutex> guard(renderer->mutex);
    return renderer->pollCompletedFrame();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_render0(
    JNIEnv*, jclass, jlong handle) {
    auto renderer = findRenderer(handle);
    if (!renderer) return;
    std::lock_guard<std::mutex> guard(renderer->mutex);
    renderer->renderTestColor();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_destroy0(
    JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_mutex);
    g_renderers.erase(handle);
}
