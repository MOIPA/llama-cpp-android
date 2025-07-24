cmake_minimum_required(VERSION 3.18.1)

project("llm")

include_directories(
     ${CMAKE_CURRENT_SOURCE_DIR}/include/llama # 指向你拷贝头文件的路径
)
add_library(
     llm
     SHARED
     LLMAndroid.cpp
)

set(PREBUILT_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI})

#    为 libllama.so 创建一个 IMPORTED 库目标
add_library(llama_shared SHARED IMPORTED GLOBAL) # GLOBAL 可选，但有时有帮助
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

#    链接你的 JNI 库 (woof) 到所有需要的共享库
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
)
