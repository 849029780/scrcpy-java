#pragma once
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <mutex>
#include <thread>
#include <chrono>
#include <ctime>
namespace scrcpy_native_log {
enum class Level { DEBUG, INFO, WARN, ERROR };
inline const char* levelName(Level v) { switch(v){case Level::DEBUG:return "DEBUG";case Level::INFO:return "INFO";case Level::WARN:return "WARN";default:return "ERROR";} }
inline std::filesystem::path path() {
#if defined(_WIN32)
 const char* home=std::getenv("USERPROFILE");
#else
 const char* home=std::getenv("HOME");
#endif
 return std::filesystem::path(home&&*home?home:".")/".scrcpy-java"/"logs"/"native"/"native.log";
}
inline std::mutex& lock(){static std::mutex m;return m;}
inline void write(Level level,const char* component,const char* fmt,...) {
 char msg[4096]{}; va_list ap; va_start(ap,fmt); std::vsnprintf(msg,sizeof(msg),fmt,ap); va_end(ap);
 std::lock_guard<std::mutex> guard(lock());
 try {
  auto filePath=path(); std::filesystem::create_directories(filePath.parent_path());
  constexpr std::uintmax_t limit=10u*1024u*1024u;
  if(std::filesystem::exists(filePath)&&std::filesystem::file_size(filePath)>=limit){
   auto backup=filePath.parent_path()/"native.log.1"; std::error_code ec; std::filesystem::remove(backup,ec); std::filesystem::rename(filePath,backup,ec);
  }
  auto now=std::chrono::system_clock::now(); auto tt=std::chrono::system_clock::to_time_t(now); std::tm tm{};
#if defined(_WIN32)
  localtime_s(&tm,&tt);
#else
  localtime_r(&tt,&tm);
#endif
  char stamp[32]{}; std::strftime(stamp,sizeof(stamp),"%Y-%m-%d %H:%M:%S",&tm);
  if(auto* f=std::fopen(filePath.string().c_str(),"ab")){
   std::fprintf(f,"%s [%s] [tid=%zu] [%s] %s\n",stamp,levelName(level),std::hash<std::thread::id>{}(std::this_thread::get_id()),component?component:"native",msg);
   std::fflush(f); std::fclose(f);
  }
 } catch(...) {}
}
}
#define SCRCPY_NATIVE_DEBUG(c,...) scrcpy_native_log::write(scrcpy_native_log::Level::DEBUG,c,__VA_ARGS__)
#define SCRCPY_NATIVE_INFO(c,...) scrcpy_native_log::write(scrcpy_native_log::Level::INFO,c,__VA_ARGS__)
#define SCRCPY_NATIVE_WARN(c,...) scrcpy_native_log::write(scrcpy_native_log::Level::WARN,c,__VA_ARGS__)
#define SCRCPY_NATIVE_ERROR(c,...) scrcpy_native_log::write(scrcpy_native_log::Level::ERROR,c,__VA_ARGS__)
