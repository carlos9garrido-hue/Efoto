cmake_minimum_required(VERSION 3.22.1)
project(rawpocket LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/LibRaw)

# LibRaw (LGPL 2.1 / CDDL 1.0). Se compila entero desde el código fuente incluido.
file(GLOB LIBRAW_SOURCES
    ${LIBRAW_DIR}/src/*.cpp
    ${LIBRAW_DIR}/src/decoders/*.cpp
    ${LIBRAW_DIR}/src/decompressors/*.cpp
    ${LIBRAW_DIR}/src/demosaic/*.cpp
    ${LIBRAW_DIR}/src/metadata/*.cpp
    ${LIBRAW_DIR}/src/postprocessing/*.cpp
    ${LIBRAW_DIR}/src/preprocessing/*.cpp
    ${LIBRAW_DIR}/src/tables/*.cpp
    ${LIBRAW_DIR}/src/utils/*.cpp
    ${LIBRAW_DIR}/src/write/*.cpp
    ${LIBRAW_DIR}/src/x3f/*.cpp
)

add_library(raw_static STATIC ${LIBRAW_SOURCES})
target_include_directories(raw_static PUBLIC ${LIBRAW_DIR})
# USE_ZLIB: DNG con compresión deflate / float. (Sin libjpeg: no se leen DNG con JPEG con pérdida.)
target_compile_definitions(raw_static PUBLIC LIBRAW_NODLL USE_ZLIB)
target_compile_options(raw_static PRIVATE -w)

add_library(rawpocket SHARED
    rawpocket_jni.cpp
    raw_session.cpp
    image_input.cpp
    develop.cpp
)
target_include_directories(rawpocket PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/stb
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(z-lib z)
target_link_libraries(rawpocket raw_static ${log-lib} ${jnigraphics-lib} ${z-lib})
target_link_libraries(raw_static ${z-lib})
