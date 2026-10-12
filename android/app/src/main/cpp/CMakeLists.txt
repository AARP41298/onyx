cmake_minimum_required(VERSION 3.22.1)
project(edat C)

add_library(edat SHARED
    edat_jni.c
    npdata/aes.c
    npdata/ec.c
    npdata/lz.c
    npdata/sha1.c
    npdata/utils.c
    npdata/make_npdata.c
)

target_include_directories(edat PRIVATE npdata)
target_compile_definitions(edat PRIVATE _FILE_OFFSET_BITS=64)
