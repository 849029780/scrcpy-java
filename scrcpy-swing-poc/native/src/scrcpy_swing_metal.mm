#include <jni.h>
#include "scrcpy_native_log.h"
#include <jawt.h>
#include <jawt_md.h>
#import <AppKit/AppKit.h>
#import <QuartzCore/CAMetalLayer.h>
#import <Metal/Metal.h>
#import <CoreImage/CoreImage.h>
#import <CoreVideo/CoreVideo.h>
#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
struct Renderer {
    id<JAWT_SurfaceLayers> surfaceLayers;
    CAMetalLayer* layer{};
    id<MTLDevice> device{};
    id<MTLCommandQueue> queue{};
    CIContext* ciContext{};
    std::mutex mutex;
};
std::mutex gMutex;
std::unordered_map<jlong,std::shared_ptr<Renderer>> renderers;
std::atomic<jlong> nextHandle{1};

// Metal devices are process-wide objects. Reuse one device across all
// mirrored windows; command queues and presentation layers remain per-window.
std::mutex gMetalDeviceMutex;
id<MTLDevice> gSharedMetalDevice = nil;

id<MTLDevice> acquireMetalDevice() {
    std::lock_guard<std::mutex> guard(gMetalDeviceMutex);
    if (!gSharedMetalDevice) gSharedMetalDevice = MTLCreateSystemDefaultDevice();
    return gSharedMetalDevice;
}
void fail(JNIEnv* env,const char* msg){ jclass c=env->FindClass("java/lang/IllegalStateException"); if(c) env->ThrowNew(c,msg); }
std::shared_ptr<Renderer> find(jlong h){ std::lock_guard<std::mutex> g(gMutex); auto i=renderers.find(h); return i==renderers.end()?nullptr:i->second; }

id<JAWT_SurfaceLayers> getSurfaceLayers(JNIEnv* env,jobject canvas){
    JAWT awt{}; awt.version=JAWT_VERSION_9;
    if(JAWT_GetAWT(env,&awt)==JNI_FALSE) return nil;
    JAWT_DrawingSurface* ds=awt.GetDrawingSurface(env,canvas);
    if(!ds) return nil;
    id<JAWT_SurfaceLayers> layers=nil;
    jint lock=ds->Lock(ds);
    if((lock & JAWT_LOCK_ERROR)==0){
        JAWT_DrawingSurfaceInfo* info=ds->GetDrawingSurfaceInfo(ds);
        if(info && info->platformInfo){
            layers=(__bridge id<JAWT_SurfaceLayers>)info->platformInfo;
        }
        if(info) ds->FreeDrawingSurfaceInfo(info);
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds);
    return layers;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_jian_scrcpy_swing_NativeMetalCanvas_attach0(JNIEnv* env,jclass,jobject canvas){
    @autoreleasepool {
        id<JAWT_SurfaceLayers> layers=getSurfaceLayers(env,canvas);
        if(!layers){ SCRCPY_NATIVE_ERROR("metal", "JAWT Cocoa surface layers unavailable"); fail(env,"JAWT Cocoa surface layers unavailable"); return 0; }
        auto r=std::make_shared<Renderer>(); r->surfaceLayers=layers; r->device=acquireMetalDevice();
        if(!r->device){ SCRCPY_NATIVE_ERROR("metal", "Metal device unavailable"); fail(env,"Metal device unavailable"); return 0; }
        r->queue=[r->device newCommandQueue];
        r->ciContext=[CIContext contextWithMTLDevice:r->device];
        r->layer=[CAMetalLayer layer]; r->layer.device=r->device; r->layer.pixelFormat=MTLPixelFormatBGRA8Unorm;
        r->layer.framebufferOnly=NO; r->layer.contentsScale=NSScreen.mainScreen.backingScaleFactor;
        jint canvasWidth=env->CallIntMethod(canvas,env->GetMethodID(env->GetObjectClass(canvas),"getWidth","()I"));
        jint canvasHeight=env->CallIntMethod(canvas,env->GetMethodID(env->GetObjectClass(canvas),"getHeight","()I"));
        dispatch_sync(dispatch_get_main_queue(), ^{
            r->layer.frame=CGRectMake(0,0,std::max(1,canvasWidth),std::max(1,canvasHeight));
            r->surfaceLayers.layer=r->layer;
        });
        jlong h=nextHandle.fetch_add(1); std::lock_guard<std::mutex> g(gMutex); renderers.emplace(h,r); return h;
    }
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_jian_scrcpy_swing_NativeMetalCanvas_render0(JNIEnv*,jclass,jlong h,jlong pb,jint width,jint height){
    @autoreleasepool {
        auto r=find(h); if(!r || !pb || width<=0 || height<=0) return JNI_FALSE;
        std::lock_guard<std::mutex> g(r->mutex);
        CVPixelBufferRef pixel=(CVPixelBufferRef)(uintptr_t)pb;
        CGFloat scale=r->layer.contentsScale ?: 1.0;
        CGSize size=r->layer.frame.size;
        r->layer.drawableSize=CGSizeMake(std::max<CGFloat>(1,size.width*scale),std::max<CGFloat>(1,size.height*scale));
        id<CAMetalDrawable> drawable=[r->layer nextDrawable]; if(!drawable) { SCRCPY_NATIVE_WARN("metal", "CAMetalLayer nextDrawable returned nil"); return JNI_FALSE; }
        CIImage* image=[CIImage imageWithCVPixelBuffer:pixel];
        CGRect extent=image.extent;
        CGFloat sx=drawable.texture.width/extent.size.width, sy=drawable.texture.height/extent.size.height;
        CGFloat s=std::min(sx,sy);
        CGFloat dw=extent.size.width*s, dh=extent.size.height*s;
        CGFloat dx=(drawable.texture.width-dw)/2.0, dy=(drawable.texture.height-dh)/2.0;
        CGAffineTransform t=CGAffineTransformMakeTranslation(dx,dy);
        t=CGAffineTransformScale(t,s,s);
        CIImage* fitted=[image imageByApplyingTransform:t];
        id<MTLCommandBuffer> command=[r->queue commandBuffer];
        CGColorSpaceRef cs=CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
        [r->ciContext render:fitted toMTLTexture:drawable.texture commandBuffer:command bounds:CGRectMake(0,0,drawable.texture.width,drawable.texture.height) colorSpace:cs];
        CGColorSpaceRelease(cs);
        [command presentDrawable:drawable]; [command commit]; [command waitUntilCompleted];
        if(command.status!=MTLCommandBufferStatusCompleted) SCRCPY_NATIVE_ERROR("metal", "command buffer failed status=%ld error=%s", (long)command.status, command.error.localizedDescription.UTF8String ?: "unknown");
        return command.status==MTLCommandBufferStatusCompleted?JNI_TRUE:JNI_FALSE;
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_jian_scrcpy_swing_NativeMetalCanvas_destroy0(JNIEnv*,jclass,jlong h){
    std::lock_guard<std::mutex> g(gMutex); renderers.erase(h);
}
