#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <jawt.h>
#include <jawt_md.h>

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
    IDXGISwapChain* swapChain{};
    ID3D11RenderTargetView* renderTarget{};
    UINT width{};
    UINT height{};

    ~Renderer() {
        if (renderTarget) renderTarget->Release();
        if (swapChain) swapChain->Release();
        if (context) context->Release();
        if (device) device->Release();
    }

    bool create(HWND target) {
        hwnd = target;
        RECT rect{};
        if (!GetClientRect(hwnd, &rect)) return false;
        width = static_cast<UINT>(rect.right - rect.left);
        height = static_cast<UINT>(rect.bottom - rect.top);
        if (width == 0 || height == 0) return false;

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

        const D3D_FEATURE_LEVEL levels[] = {
            D3D_FEATURE_LEVEL_11_1,
            D3D_FEATURE_LEVEL_11_0,
            D3D_FEATURE_LEVEL_10_1,
            D3D_FEATURE_LEVEL_10_0
        };
        D3D_FEATURE_LEVEL selected{};
        HRESULT hr = D3D11CreateDeviceAndSwapChain(
            nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            levels, ARRAYSIZE(levels), D3D11_SDK_VERSION,
            &desc, &swapChain, &device, &selected, &context);
        if (FAILED(hr)) {
            // Some older runtimes do not accept the 11.1 feature-level token.
            const D3D_FEATURE_LEVEL fallback[] = {
                D3D_FEATURE_LEVEL_11_0,
                D3D_FEATURE_LEVEL_10_1,
                D3D_FEATURE_LEVEL_10_0
            };
            hr = D3D11CreateDeviceAndSwapChain(
                nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                fallback, ARRAYSIZE(fallback), D3D11_SDK_VERSION,
                &desc, &swapChain, &device, &selected, &context);
        }
        return SUCCEEDED(hr) && createRenderTarget();
    }

    bool createRenderTarget() {
        ID3D11Texture2D* backBuffer = nullptr;
        HRESULT hr = swapChain->GetBuffer(
            0, __uuidof(ID3D11Texture2D),
            reinterpret_cast<void**>(&backBuffer));
        if (FAILED(hr)) return false;
        hr = device->CreateRenderTargetView(backBuffer, nullptr, &renderTarget);
        backBuffer->Release();
        return SUCCEEDED(hr);
    }

    void render() {
        if (!hwnd || !IsWindow(hwnd) || !swapChain) return;
        RECT rect{};
        if (!GetClientRect(hwnd, &rect)) return;
        const UINT newWidth = static_cast<UINT>(rect.right - rect.left);
        const UINT newHeight = static_cast<UINT>(rect.bottom - rect.top);
        if (newWidth == 0 || newHeight == 0) return;

        if (newWidth != width || newHeight != height) {
            if (renderTarget) {
                renderTarget->Release();
                renderTarget = nullptr;
            }
            if (SUCCEEDED(swapChain->ResizeBuffers(
                    0, newWidth, newHeight, DXGI_FORMAT_UNKNOWN, 0))) {
                width = newWidth;
                height = newHeight;
                if (!createRenderTarget()) return;
            } else {
                return;
            }
        }

        const float testColor[] = {0.035f, 0.12f, 0.22f, 1.0f};
        context->OMSetRenderTargets(1, &renderTarget, nullptr);
        context->ClearRenderTargetView(renderTarget, testColor);
        swapChain->Present(1, 0);
    }
};

std::mutex g_mutex;
std::unordered_map<jlong, std::unique_ptr<Renderer>> g_renderers;
std::atomic<jlong> g_nextHandle{1};

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

    auto renderer = std::make_unique<Renderer>();
    if (!renderer->create(hwnd)) {
        throwJava(env, "D3D11 device or swap-chain initialization failed");
        return 0;
    }

    const jlong handle = g_nextHandle.fetch_add(1);
    std::lock_guard<std::mutex> guard(g_mutex);
    g_renderers.emplace(handle, std::move(renderer));
    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_render0(
    JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_mutex);
    auto it = g_renderers.find(handle);
    if (it != g_renderers.end()) it->second->render();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jian_scrcpy_swing_NativeD3D11Canvas_detach0(
    JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_mutex);
    g_renderers.erase(handle);
}
