#pragma once
#include <cstdarg>
#include <cstdint>
#include <algorithm>
#include <atomic>
#include <string>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <functional>
#include <mutex>
#include <thread>
#include <chrono>
#include <ctime>
#include <vector>
#include <iterator>
#include <system_error>

namespace scrcpy_native_log {
enum class Level { DEBUG, INFO, WARN, ERR };
inline const char* levelName(Level v) {
    switch (v) { case Level::DEBUG: return "DEBUG"; case Level::INFO: return "INFO"; case Level::WARN: return "WARN"; default: return "ERROR"; }
}
inline std::filesystem::path path() {
#if defined(_WIN32)
    const char* home = std::getenv("USERPROFILE");
#else
    const char* home = std::getenv("HOME");
#endif
    return std::filesystem::path(home && *home ? home : ".") / ".scrcpy-java" / "logs" / "native" / "native.log";
}
inline std::mutex& lock() { static std::mutex m; return m; }
inline uint32_t crc32(const uint8_t* data, size_t size) {
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < size; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
    }
    return ~crc;
}
inline void put32(std::ofstream& out, uint32_t value) {
    for (int i = 0; i < 4; ++i) out.put(static_cast<char>((value >> (i * 8)) & 0xff));
}
// Write a standards-compliant gzip stream using DEFLATE stored blocks. This avoids
// adding a platform-specific compression-library dependency to the native renderer.
inline bool gzipStored(const std::filesystem::path& source, const std::filesystem::path& target) {
    std::ifstream in(source, std::ios::binary);
    if (!in) return false;
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)), {});
    std::ofstream out(target, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    const unsigned char header[] = {0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 255};
    out.write(reinterpret_cast<const char*>(header), sizeof(header));
    size_t offset = 0;
    do {
        const size_t count = std::min<size_t>(65535, bytes.size() - offset);
        const bool finalBlock = offset + count >= bytes.size();
        out.put(static_cast<char>(finalBlock ? 1 : 0));
        const uint16_t len = static_cast<uint16_t>(count);
        const uint16_t nlen = static_cast<uint16_t>(~len);
        out.put(static_cast<char>(len & 0xff)); out.put(static_cast<char>(len >> 8));
        out.put(static_cast<char>(nlen & 0xff)); out.put(static_cast<char>(nlen >> 8));
        if (count) out.write(reinterpret_cast<const char*>(bytes.data() + offset), static_cast<std::streamsize>(count));
        offset += count;
    } while (offset < bytes.size());
    put32(out, crc32(bytes.data(), bytes.size()));
    put32(out, static_cast<uint32_t>(bytes.size()));
    out.close();
    return static_cast<bool>(out);
}
inline void cleanup(const std::filesystem::path& dir) {
    const auto cutoff = std::filesystem::file_time_type::clock::now() - std::chrono::hours(24 * 5);
    std::error_code ec;
    for (const auto& entry : std::filesystem::directory_iterator(dir, ec)) {
        if (ec) break;
        const auto name = entry.path().filename().string();
        if (name.rfind("native-", 0) != 0 || entry.path().extension() != ".gz") continue;
        const auto modified = entry.last_write_time(ec);
        if (!ec && modified < cutoff) std::filesystem::remove(entry.path(), ec);
        ec.clear();
    }
}
inline void rotate(const std::filesystem::path& file, std::size_t incomingBytes) {
    constexpr std::uintmax_t limit = 10u * 1024u * 1024u;
    std::error_code ec;
    if (!std::filesystem::exists(file, ec)) return;
    const auto currentSize = std::filesystem::file_size(file, ec);
    if (ec || currentSize == 0 || currentSize + incomingBytes <= limit) return;
    const auto now = std::chrono::system_clock::now();
    const auto tt = std::chrono::system_clock::to_time_t(now);
    std::tm tm{};
#if defined(_WIN32)
    localtime_s(&tm, &tt);
#else
    localtime_r(&tt, &tm);
#endif
    const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(
            now.time_since_epoch()).count() % 1000;
    static std::atomic<unsigned long long> sequence{0};
    char suffix[64]{};
    std::strftime(suffix, sizeof(suffix), "%Y%m%d-%H%M%S", &tm);
    const std::string uniqueSuffix = std::string(suffix) + "-" +
            std::to_string(millis) + "-" + std::to_string(sequence.fetch_add(1));
    auto raw = file.parent_path() / (std::string("native-") + uniqueSuffix + ".log");
    auto archive = file.parent_path() / (std::string("native-") + uniqueSuffix + ".log.gz");
    std::filesystem::rename(file, raw, ec);
    if (!ec && gzipStored(raw, archive)) std::filesystem::remove(raw, ec);
    cleanup(file.parent_path());
}
inline void write(Level level, const char* component, const char* fmt, ...) {
    char msg[4096]{};
    va_list ap; va_start(ap, fmt); std::vsnprintf(msg, sizeof(msg), fmt, ap); va_end(ap);
    std::lock_guard<std::mutex> guard(lock());
    try {
        const auto filePath = path();
        std::filesystem::create_directories(filePath.parent_path());
        const auto now = std::chrono::system_clock::now();
        const auto tt = std::chrono::system_clock::to_time_t(now);
        std::tm tm{};
#if defined(_WIN32)
        localtime_s(&tm, &tt);
#else
        localtime_r(&tt, &tm);
#endif
        char stamp[32]{};
        std::strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &tm);
        char lineBuffer[4608]{};
        const int lineLength = std::snprintf(lineBuffer, sizeof(lineBuffer),
                "%s [%s] [tid=%zu] [%s] %s\\n", stamp, levelName(level),
                std::hash<std::thread::id>{}(std::this_thread::get_id()),
                component ? component : "native", msg);
        if (lineLength > 0) {
            const auto lineSize = std::min<std::size_t>(
                    static_cast<std::size_t>(lineLength), sizeof(lineBuffer) - 1);
            rotate(filePath, lineSize);
            if (auto* f = std::fopen(filePath.string().c_str(), "ab")) {
                std::fwrite(lineBuffer, 1, lineSize, f);
                std::fflush(f); std::fclose(f);
            }
        }
        cleanup(filePath.parent_path());
    } catch (...) {}
}
}
#define SCRCPY_NATIVE_DEBUG(c,...) scrcpy_native_log::write(scrcpy_native_log::Level::DEBUG,c,__VA_ARGS__)
#define SCRCPY_NATIVE_INFO(c,...) scrcpy_native_log::write(scrcpy_native_log::Level::INFO,c,__VA_ARGS__)
#define SCRCPY_NATIVE_WARN(c,...) scrcpy_native_log::write(scrcpy_native_log::Level::WARN,c,__VA_ARGS__)
#define SCRCPY_NATIVE_ERROR(c,...) scrcpy_native_log::write(scrcpy_native_log::Level::ERR,c,__VA_ARGS__)
