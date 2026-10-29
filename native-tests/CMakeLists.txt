# Prueba del núcleo nativo en PC (Linux/macOS). No participa en el APK.
#   cmake -S native-tests -B build-tests && cmake --build build-tests -j && ctest --test-dir build-tests --output-on-failure
cmake_minimum_required(VERSION 3.16)
project(rawpocket_host_tests LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
set(LIBRAW_DIR ${CPP}/third_party/LibRaw)

file(GLOB LIBRAW_SOURCES
    ${LIBRAW_DIR}/src/*.cpp ${LIBRAW_DIR}/src/decoders/*.cpp ${LIBRAW_DIR}/src/decompressors/*.cpp
    ${LIBRAW_DIR}/src/demosaic/*.cpp ${LIBRAW_DIR}/src/metadata/*.cpp ${LIBRAW_DIR}/src/postprocessing/*.cpp
    ${LIBRAW_DIR}/src/preprocessing/*.cpp ${LIBRAW_DIR}/src/tables/*.cpp ${LIBRAW_DIR}/src/utils/*.cpp
    ${LIBRAW_DIR}/src/write/*.cpp ${LIBRAW_DIR}/src/x3f/*.cpp)

find_package(ZLIB REQUIRED)
find_package(Threads REQUIRED)

add_library(raw_static STATIC ${LIBRAW_SOURCES})
target_include_directories(raw_static PUBLIC ${LIBRAW_DIR})
target_compile_definitions(raw_static PUBLIC LIBRAW_NODLL USE_ZLIB)
target_compile_options(raw_static PRIVATE -w)
target_link_libraries(raw_static ZLIB::ZLIB Threads::Threads)

add_executable(test_host
    test_host.cpp ${CPP}/develop.cpp ${CPP}/raw_session.cpp ${CPP}/image_input.cpp)
target_include_directories(test_host PRIVATE ${CPP} ${CPP}/third_party/stb)
target_link_libraries(test_host raw_static Threads::Threads)

add_executable(test_image
    test_image.cpp ${CPP}/develop.cpp ${CPP}/raw_session.cpp ${CPP}/image_input.cpp)
target_include_directories(test_image PRIVATE ${CPP} ${CPP}/third_party/stb)
target_link_libraries(test_image raw_static Threads::Threads)

# Volcador del motor CPU para la prueba de paridad CPU/GPU (native-tests/gpu/parity.js).
add_executable(dump_cpu dump_cpu.cpp ${CPP}/develop.cpp)
target_include_directories(dump_cpu PRIVATE ${CPP})
target_link_libraries(dump_cpu Threads::Threads)

enable_testing()
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(TEST_DIR ${CMAKE_BINARY_DIR}/test-data)
file(MAKE_DIRECTORY ${TEST_DIR})
add_test(NAME make_dng_normal
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/make_test_dng.py ${TEST_DIR}/test.dng 1)
add_test(NAME make_dng_o6
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/make_test_dng.py ${TEST_DIR}/test_o6.dng 6)
add_test(NAME pipeline
    COMMAND test_host ${TEST_DIR}/test.dng ${TEST_DIR}/test_o6.dng ${TEST_DIR})
set_tests_properties(pipeline PROPERTIES DEPENDS "make_dng_normal;make_dng_o6")

add_test(NAME imagenes_jpg_png COMMAND test_image ${CMAKE_BINARY_DIR})
