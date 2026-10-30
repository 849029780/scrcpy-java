#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <jawt.h>
#include <jawt_md.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")

namespace {

struct Renderer {
    HWND hwnd{};
    ID3D11Device* device{};
    ID3D11DeviceContext* context{};
    ID3D11VideoDevice* videoDevice{};
    ID3D11VideoContext* videoContext{};
    IDXGISwapChain* swapChain{};
    ID3D11RenderTargetView* renderTarget{};
    ID3D11VideoProcessorEnumerator* processorEnumerator{};
    ID3D11VideoProcessor* processor{};
    ID3D11Query* completionQuery{};
    std::mutex mutex;
    UINT width{};
    UINT height{};
    UINT processorWidth{};
    UINT processorHeight{};
    UINT processorOutputWidth{};
    UINT processorOutputHeight{};
    DXGI_FORMAT processorFormat{DXGI_FORMAT_UNKNOWN};

    ~Renderer() {
        releaseProcessor();
        detachSurface();
        if (completionQuery) completionQuery->Release();
        if (videoContext) videoContext->Release();
        if (videoDevice) videoDevice->Release();
        if (context) context->Release();
        if (device) device->Release();
    }

    void releaseProcessor() {
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
        const D3D_FEATURE_LEVEL levels[] = {
            D3D_FEATURE_LEVEL_11_1,
            D3D_FEATURE_LEVEL_11_0,
            D3D_FEATURE_LEVEL_10_1,
            D3D_FEATURE_LEVEL_10_0
        };
        D3D_FEATURE_LEVEL selected{};
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
        if (FAILED(hr)) return false;

        if (FAILED(device->QueryInterface(__uuidof(ID3D11VideoDevice),
                reinterpret_cast<void**>(&videoDevice)))) return false;
        if (FAILED(context->QueryInterface(__uuidof(ID3D11VideoContext),
                reinterpret_cast<void**>(&videoContext)))) return false;
        D3D11_QUERY_DESC queryDesc{};
        queryDesc.Query = D3D11_QUERY_EVENT;
        if (FAILED(device->CreateQuery(&queryDesc, &completionQuery))) return false;

        // FFmpeg's D3D11VA decoder and the Swing renderer share this immediate
        // context. Enable D3D11's built-in context serialization for calls
        // arriving from the decoder and presentation threads.
        ID3D11Multithread* multithread = nullptr;
        if (SUCCEEDED(context->QueryInterface(__uuidof(ID3D11Multithread),
                reinterpret_cast<void**>(&multithread)))) {
            multithread->SetMultithreadProtected(TRUE);
            multithread->Release();
        }
        return true;
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
            if (SUCCEEDED(videoDevice->CheckVideoProcessorFormat(inputFormat, &support))
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
        if (FAILED(videoDevice->CheckVideoProcessorFormat(inputFormat, &inputSupport))
                || !(inputSupport & D3D11_VIDEO_PROCESSOR_FORMAT_SUPPORT_INPUT)
                || FAILED(videoDevice->CheckVideoProcessorFormat(
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

    bool renderHardware(uintptr_t textureAddress, uintptr_t arrayIndex,
                        UINT frameWidth, UINT frameHeight,
                        int colorSpace, int colorRange) {
        if (!resizeIfNeeded() || textureAddress == 0 || frameWidth == 0 || frameHeight == 0) {
            return false;
        }

        auto* texture = reinterpret_cast<ID3D11Texture2D*>(textureAddress);
        D3D11_TEXTURE2D_DESC textureDesc{};
        texture->GetDesc(&textureDesc);
        if (!ensureVideoProcessor(frameWidth, frameHeight, textureDesc.Format)) {
            return false;
        }

        ID3D11Texture2D* backBuffer = nullptr;
        ID3D11VideoProcessorInputView* inputView = nullptr;
        ID3D11VideoProcessorOutputView* outputView = nullptr;
        HRESULT hr = swapChain->GetBuffer(
            0, __uuidof(ID3D11Texture2D),
            reinterpret_cast<void**>(&backBuffer));
        if (FAILED(hr)) return false;

        D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC inputDesc{};
        inputDesc.FourCC = 0;
        inputDesc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
        inputDesc.Texture2D.MipSlice = 0;
        inputDesc.Texture2D.ArraySlice = static_cast<UINT>(arrayIndex);
        hr = videoDevice->CreateVideoProcessorInputView(
            texture, processorEnumerator, &inputDesc, &inputView);

        if (SUCCEEDED(hr)) {
            D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outputDesc{};
            outputDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
            outputDesc.Texture2D.MipSlice = 0;
            hr = videoDevice->CreateVideoProcessorOutputView(
                backBuffer, processorEnumerator, &outputDesc, &outputView);
        }

        if (SUCCEEDED(hr)) {
            const RECT sourceRect{0, 0, static_cast<LONG>(frameWidth), static_cast<LONG>(frameHeight)};
            const double scale = std::min(
                width / static_cast<double>(frameWidth),
                height / static_cast<double>(frameHeight));
            const LONG destWidth = static_cast<LONG>(frameWidth * scale + 0.5);
            const LONG destHeight = static_cast<LONG>(frameHeight * scale + 0.5);
            const LONG left = (static_cast<LONG>(width) - destWidth) / 2;
            const LONG top = (static_cast<LONG>(height) - destHeight) / 2;
            const RECT destRect{left, top, left + destWidth, top + destHeight};

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
            context->OMSetRenderTargets(1, &renderTarget, nullptr);
            context->ClearRenderTargetView(renderTarget, black);
            context->OMSetRenderTargets(0, nullptr, nullptr);

            D3D11_VIDEO_PROCESSOR_STREAM stream{};
            stream.Enable = TRUE;
            stream.OutputIndex = 0;
            stream.InputFrameOrField = 0;
            stream.PastFrames = 0;
            stream.FutureFrames = 0;
            stream.pInputSurface = inputView;
            hr = videoContext->VideoProcessorBlt(
                processor, outputView, 0, 1, &stream);
        }

        if (outputView) outputView->Release();
        if (inputView) inputView->Release();
        backBuffer->Release();

        if (FAILED(hr)) return false;

        // AVFrame owns the decoder's texture-array slice. Do not return from
        // this call (and let FFmpeg recycle that slice) until the video
        // processor has finished reading it.
        context->End(completionQuery);
        context->Flush();
        HRESULT completion = S_FALSE;
        while (completion == S_FALSE) {
            completion = context->GetData(completionQuery, nullptr, 0, 0);
            if (completion == S_FALSE) Sleep(0);
        }
        if (FAILED(completion)) return false;
        return SUCCEEDED(swapChain->Present(0, 0));
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_renderHardware0(
    JNIEnv*, jclass, jlong handle, jlong texture, jlong arrayIndex,
    jint width, jint height, jint colorSpace, jint colorRange) {
    auto renderer = findRenderer(handle);
    if (!renderer) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(renderer->mutex);
    return renderer->renderHardware(
        static_cast<uintptr_t>(texture), static_cast<uintptr_t>(arrayIndex),
        static_cast<UINT>(width), static_cast<UINT>(height),
        colorSpace, colorRange) ? JNI_TRUE : JNI_FALSE;
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
