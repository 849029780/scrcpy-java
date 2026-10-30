#include <jni.h>
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
struct Renderer {
    Display* display{};
    Drawable drawable{};
    VADisplay vaDisplay{};
    std::mutex mutex;
    ~Renderer() {
        if (vaDisplay) vaTerminate(vaDisplay);
        // JAWT owns the X11 Display; do not XCloseDisplay().
    }
};
std::mutex gMutex;
std::unordered_map<jlong,std::shared_ptr<Renderer>> renderers;
std::atomic<jlong> nextHandle{1};

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
    if(!getSurface(env,canvas,&display,&drawable)){ fail(env,"JAWT X11 surface unavailable"); return 0; }
    auto r=std::make_shared<Renderer>(); r->display=display; r->drawable=drawable;
    r->vaDisplay=vaGetDisplay(display);
    if(!r->vaDisplay){ fail(env,"vaGetDisplay failed"); return 0; }
    int major=0,minor=0; if(vaInitialize(r->vaDisplay,&major,&minor)!=VA_STATUS_SUCCESS){ fail(env,"vaInitialize failed"); return 0; }
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
    return s==VA_STATUS_SUCCESS?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_com_jian_scrcpy_swing_NativeVaapiCanvas_destroy0(JNIEnv*,jclass,jlong h){
    std::lock_guard<std::mutex> g(gMutex); renderers.erase(h);
}
