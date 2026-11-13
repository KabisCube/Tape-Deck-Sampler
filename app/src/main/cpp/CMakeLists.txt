cmake_minimum_required(VERSION 3.22.1)
project(retro-speech VERSION 1.52.0 LANGUAGES C)

set(THIRD_PARTY "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/espeak-ng")
set(ESPEAK_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/espeak-ng-1.52.0")
if(NOT EXISTS "${ESPEAK_SOURCE}/src/libespeak-ng/CMakeLists.txt")
    execute_process(
        COMMAND "${CMAKE_COMMAND}" -E tar xzf "${THIRD_PARTY}/espeak-ng-1.52.0.tar.gz" --
            espeak-ng-1.52.0/src/libespeak-ng
            espeak-ng-1.52.0/src/ucd-tools
            espeak-ng-1.52.0/src/include/espeak-ng
        WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
        COMMAND_ERROR_IS_FATAL ANY
    )
endif()

set(HAVE_MKSTEMP ON)
set(USE_KLATT ON)
set(USE_ASYNC OFF)
set(USE_MBROLA OFF)
set(USE_LIBSONIC OFF)
set(USE_LIBPCAUDIO OFF)
set(USE_SPEECHPLAYER OFF)
set(BUILD_SHARED_LIBS OFF)

add_library(espeak-include INTERFACE)
target_include_directories(espeak-include INTERFACE "${ESPEAK_SOURCE}/src/include")
add_subdirectory("${ESPEAK_SOURCE}/src/ucd-tools" ucd)
add_subdirectory("${ESPEAK_SOURCE}/src/libespeak-ng" espeak)

add_library(retro-speech SHARED RetroSpeech.c)
target_link_libraries(retro-speech PRIVATE espeak-ng)
