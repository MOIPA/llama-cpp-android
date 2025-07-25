cmake_minimum_required(VERSION 3.18.1)

project("llm")

# 找到OpenCL
#find_package(OpenCL REQUIRED)
#find_package(c++_shared REQUIRED)
include_directories(
     ${CMAKE_CURRENT_SOURCE_DIR}/include/llama
)
add_library(
     llm
     SHARED
     LLMAndroid.cpp
)

set(PREBUILT_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI})

add_library(llama_shared SHARED IMPORTED GLOBAL)
set_target_properties(llama_shared PROPERTIES
     IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libllama.so
)
add_library(ggml_shared SHARED IMPORTED GLOBAL)
set_target_properties(ggml_shared PROPERTIES
     IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libggml.so
)
add_library(ggml_base_shared SHARED IMPORTED GLOBAL)
set_target_properties(ggml_base_shared PROPERTIES
     IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libggml-base.so
)
add_library(ggml_cpu_shared SHARED IMPORTED GLOBAL)
set_target_properties(ggml_cpu_shared PROPERTIES
     IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libggml-cpu.so
)
add_library(common_shared SHARED IMPORTED GLOBAL)
set_target_properties(common_shared PROPERTIES
     IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libcommon.a
)
add_library(mtmd_shared SHARED IMPORTED GLOBAL)
set_target_properties(mtmd_shared PROPERTIES
     IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libmtmd.so
)
add_library(opencl_shared SHARED IMPORTED GLOBAL)
set_target_properties(opencl_shared PROPERTIES
        IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libggml-opencl.so
)


target_link_libraries(
     llm
     PRIVATE
     llama_shared
     ggml_shared
     ggml_base_shared
     ggml_cpu_shared
     mtmd_shared
     log
     common_shared
     opencl_shared
    # openCL的依赖
#        OpenCL::OpenCL
#        c++_shared
)
