#include <jni.h>
#include "scrcpy_native_log.h"
#include <jawt.h>
#include <jawt_md.h>
#include <va/va.h>
#include <va/va_x11.h>
#include <X11/Xlib.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
void releaseVaDisplay(Display* xDisplay);

struct Renderer {
    Display* display{};
    Drawable drawable{};
    VADisplay vaDisplay{};
    std::mutex mutex;
    ~Renderer() {
        if (vaDisplay) releaseVaDisplay(display);
        // JAWT owns the X11 Display; do not XCloseDisplay().
    }
};
std::mutex gMutex;
std::unordered_map<jlong,std::shared_ptr<Renderer>> renderers;
std::atomic<jlong> nextHandle{1};

struct SharedVaDisplay { VADisplay display{}; size_t references{}; };
std::mutex gVaPoolMutex;
std::unordered_map<Display*, SharedVaDisplay> gVaDisplays;

VADisplay acquireVaDisplay(Display* xDisplay) {
    std::lock_guard<std::mutex> guard(gVaPoolMutex);
    auto& entry = gVaDisplays[xDisplay];
    if (!entry.display) {
        VADisplay display = vaGetDisplay(xDisplay);
        if (!display) { SCRCPY_NATIVE_ERROR("vaapi", "vaGetDisplay failed"); gVaDisplays.erase(xDisplay); return nullptr; }
        int major = 0, minor = 0;
        if (vaInitialize(display, &major, &minor) != VA_STATUS_SUCCESS) {
            gVaDisplays.erase(xDisplay);
            return nullptr;
        }
        entry.display = display;
        SCRCPY_NATIVE_INFO("vaapi", "VAAPI display initialized version=%d.%d", major, minor);
    }
    ++entry.references;
    return entry.display;
}

void releaseVaDisplay(Display* xDisplay) {
    std::lock_guard<std::mutex> guard(gVaPoolMutex);
    auto it = gVaDisplays.find(xDisplay);
    if (it == gVaDisplays.end()) return;
    if (it->second.references > 0) --it->second.references;
    if (it->second.references == 0) {
        if (it->second.display) vaTerminate(it->second.display);
        gVaDisplays.erase(it);
    }
}

void fail(JNIEnv* env,const char* msg){ jclass c=env->FindClass("java/lang/IllegalStateException"); if(c) env->ThrowNew(c,msg); }
std::shared_ptr<Renderer> find(jlong h){ std::lock_guard<std::mutex> g(gMutex); auto i=renderers.find(h); return i==renderers.end()?nullptr:i->second; }

bool getSurface(JNIEnv* env,jobject canvas,Display** display,Drawable* drawable){
    JAWT awt{}; awt.version=JAWT_VERSION_9;
    if(JAWT_GetAWT(env,&awt)==JNI_FALSE) return false;
    JAWT_DrawingSurface* ds=awt.GetDrawingSurface(env,canvas); if(!ds) return false;
    bool ok=false;
    jint lock=ds->Lock(ds);
    if((lock & JAWT_LOCK_ERROR)==0){
        JAWT_DrawingSurfaceInfo* info=ds->GetDrawingSurfaceInfo(ds);
        if(info && info->platformInfo){
            auto* x=static_cast<JAWT_X11DrawingSurfaceInfo*>(info->platformInfo);
            *display=x->display; *drawable=x->drawable; ok=*display && *drawable;
        }
        if(info) ds->FreeDrawingSurfaceInfo(info);
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds);
    return ok;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_jian_scrcpy_swing_NativeVaapiCanvas_attach0(JNIEnv* env,jclass,jobject canvas){
    Display* display=nullptr; Drawable drawable=0;
    if(!getSurface(env,canvas,&display,&drawable)){ SCRCPY_NATIVE_ERROR("vaapi", "JAWT X11 surface unavailable"); fail(env,"JAWT X11 surface unavailable"); return 0; }
    auto r=std::make_shared<Renderer>(); r->display=display; r->drawable=drawable;
    r->vaDisplay=acquireVaDisplay(display);
    if(!r->vaDisplay){ SCRCPY_NATIVE_ERROR("vaapi", "shared display initialization failed"); fail(env,"VAAPI shared display initialization failed"); return 0; }
    SCRCPY_NATIVE_INFO("vaapi", "renderer attached");
    jlong h=nextHandle.fetch_add(1); std::lock_guard<std::mutex> g(gMutex); renderers.emplace(h,r); return h;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_jian_scrcpy_swing_NativeVaapiCanvas_display0(JNIEnv*,jclass,jlong h){
    auto r=find(h); return r?reinterpret_cast<jlong>(r->vaDisplay):0;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_jian_scrcpy_swing_NativeVaapiCanvas_render0(JNIEnv*,jclass,jlong h,jlong surface,jint width,jint height){
    auto r=find(h); if(!r || !surface || width<=0 || height<=0) return JNI_FALSE;
    std::lock_guard<std::mutex> g(r->mutex);
    XWindowAttributes attr{}; if(!XGetWindowAttributes(r->display,r->drawable,&attr) || attr.width<=0 || attr.height<=0) return JNI_FALSE;
    double scale=std::min(attr.width/(double)width,attr.height/(double)height);
    int dw=std::max(1,(int)(width*scale+0.5)), dh=std::max(1,(int)(height*scale+0.5));
    int dx=(attr.width-dw)/2, dy=(attr.height-dh)/2;
    VAStatus s=vaPutSurface(r->vaDisplay,(VASurfaceID)(uintptr_t)surface,r->drawable,
        0,0,width,height,dx,dy,dw,dh,nullptr,0,VA_FRAME_PICTURE);
    if(s==VA_STATUS_SUCCESS) { vaSyncSurface(r->vaDisplay,(VASurfaceID)(uintptr_t)surface); XFlush(r->display); }
    if(s!=VA_STATUS_SUCCESS) SCRCPY_NATIVE_WARN("vaapi", "vaPutSurface failed status=%d", (int)s);
    return s==VA_STATUS_SUCCESS?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_com_jian_scrcpy_swing_NativeVaapiCanvas_destroy0(JNIEnv*,jclass,jlong h){
    std::lock_guard<std::mutex> g(gMutex); renderers.erase(h);
}
