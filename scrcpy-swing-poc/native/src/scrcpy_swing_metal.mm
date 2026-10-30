#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>
#import <AppKit/AppKit.h>
#import <QuartzCore/CAMetalLayer.h>
#import <Metal/Metal.h>
#import <CoreImage/CoreImage.h>
#import <CoreVideo/CoreVideo.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
struct Renderer {
    NSView* view{};
    CAMetalLayer* layer{};
    id<MTLDevice> device{};
    id<MTLCommandQueue> queue{};
    CIContext* ciContext{};
    std::mutex mutex;
};
std::mutex gMutex;
std::unordered_map<jlong,std::shared_ptr<Renderer>> renderers;
std::atomic<jlong> nextHandle{1};
void fail(JNIEnv* env,const char* msg){ jclass c=env->FindClass("java/lang/IllegalStateException"); if(c) env->ThrowNew(c,msg); }
std::shared_ptr<Renderer> find(jlong h){ std::lock_guard<std::mutex> g(gMutex); auto i=renderers.find(h); return i==renderers.end()?nullptr:i->second; }

NSView* getView(JNIEnv* env,jobject canvas){
    JAWT awt{}; awt.version=JAWT_VERSION_9; if(JAWT_GetAWT(env,&awt)==JNI_FALSE) return nil;
    JAWT_DrawingSurface* ds=awt.GetDrawingSurface(env,canvas); if(!ds) return nil;
    NSView* view=nil; jint lock=ds->Lock(ds);
    if((lock & JAWT_LOCK_ERROR)==0){
        JAWT_DrawingSurfaceInfo* info=ds->GetDrawingSurfaceInfo(ds);
        if(info && info->platformInfo){
            auto* mac=static_cast<JAWT_MacOSXDrawingSurfaceInfo*>(info->platformInfo);
            view=(__bridge NSView*)mac->cocoaViewRef;
        }
        if(info) ds->FreeDrawingSurfaceInfo(info);
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds); return view;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_jian_scrcpy_swing_NativeMetalCanvas_attach0(JNIEnv* env,jclass,jobject canvas){
    @autoreleasepool {
        NSView* view=getView(env,canvas); if(!view){ fail(env,"JAWT Cocoa view unavailable"); return 0; }
        auto r=std::make_shared<Renderer>(); r->view=view; r->device=MTLCreateSystemDefaultDevice();
        if(!r->device){ fail(env,"Metal device unavailable"); return 0; }
        r->queue=[r->device newCommandQueue];
        r->ciContext=[CIContext contextWithMTLDevice:r->device];
        r->layer=[CAMetalLayer layer]; r->layer.device=r->device; r->layer.pixelFormat=MTLPixelFormatBGRA8Unorm;
        r->layer.framebufferOnly=NO; r->layer.contentsScale=NSScreen.mainScreen.backingScaleFactor;
        dispatch_sync(dispatch_get_main_queue(), ^{ view.wantsLayer=YES; view.layer=r->layer; r->layer.frame=view.bounds; });
        jlong h=nextHandle.fetch_add(1); std::lock_guard<std::mutex> g(gMutex); renderers.emplace(h,r); return h;
    }
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_jian_scrcpy_swing_NativeMetalCanvas_render0(JNIEnv*,jclass,jlong h,jlong pb,jint width,jint height){
    @autoreleasepool {
        auto r=find(h); if(!r || !pb || width<=0 || height<=0) return JNI_FALSE;
        std::lock_guard<std::mutex> g(r->mutex);
        CVPixelBufferRef pixel=(CVPixelBufferRef)(uintptr_t)pb;
        CGSize size=r->view.bounds.size; CGFloat scale=r->view.window.backingScaleFactor ?: 1.0;
        r->layer.drawableSize=CGSizeMake(std::max<CGFloat>(1,size.width*scale),std::max<CGFloat>(1,size.height*scale));
        id<CAMetalDrawable> drawable=[r->layer nextDrawable]; if(!drawable) return JNI_FALSE;
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
        return command.status==MTLCommandBufferStatusCompleted?JNI_TRUE:JNI_FALSE;
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_jian_scrcpy_swing_NativeMetalCanvas_destroy0(JNIEnv*,jclass,jlong h){
    std::lock_guard<std::mutex> g(gMutex); renderers.erase(h);
}
