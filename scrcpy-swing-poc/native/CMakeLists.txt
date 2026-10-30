cmake_minimum_required(VERSION 3.24)
project(scrcpy_swing_d3d11 LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "The Swing/JAWT/D3D11 PoC currently targets Windows only.")
endif()

find_package(JNI REQUIRED)

find_library(JAWT_LIBRARY jawt
    HINTS "$ENV{JAVA_HOME}/lib"
    REQUIRED
)

add_library(scrcpy_swing_d3d11 SHARED
    src/scrcpy_swing_d3d11.cpp
)

target_compile_features(scrcpy_swing_d3d11 PRIVATE cxx_std_17)
target_include_directories(scrcpy_swing_d3d11 PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(scrcpy_swing_d3d11 PRIVATE
    ${JAWT_LIBRARY}
    d3d11
    dxgi
)
set_target_properties(scrcpy_swing_d3d11 PROPERTIES
    OUTPUT_NAME "scrcpy_swing_d3d11"
    WINDOWS_EXPORT_ALL_SYMBOLS OFF
)
