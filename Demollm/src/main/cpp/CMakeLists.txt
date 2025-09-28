# CMake 最低版本要求
cmake_minimum_required(VERSION 3.18)

# 定义项目名称
project("demollm-native-lib" C CXX)

# --------------------------------------------------------------------
# 核心步骤 1: 强制子项目构建静态库
# 在 add_subdirectory 之前设置 BUILD_SHARED_LIBS 为 OFF,
# 这会告诉 llama.cpp 和 whisper.cpp 将它们的目标编译为静态库 (.a)。
# --------------------------------------------------------------------
set(BUILD_SHARED_LIBS OFF)

# 确保 llama.cpp 构建其 common 库
set(LLAMA_BUILD_COMMON ON)
# 禁用 llama.cpp 的 CURL 支持，因为 NDK 环境中通常不提供
set(LLAMA_CURL OFF)

# --------------------------------------------------------------------
# 核心步骤 2: 添加子项目
# 这会使 CMake 进入这两个目录并根据它们自己的 CMakeLists.txt 来配置编译。
# --------------------------------------------------------------------
add_subdirectory(llama.cpp)
add_subdirectory(llama.cpp/tools/mtmd)

# Force whisper.cpp to use the ggml from llama.cpp to avoid conflicts
set(GGML_HOME ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml)
add_subdirectory(whisper.cpp)

# --------------------------------------------------------------------
# 核心步骤 3: 创建你自己的、唯一的共享库
# 我们将这个库命名为 "demollm-native-lib", 它会由你的 JNI 文件 (LLMAndroid.cpp) 编译而来。
# 最终生成的文件将是 libdemollm-native-lib.so。
# --------------------------------------------------------------------
add_library(
    demollm-native-lib
    SHARED
    LLMAndroid.cpp
)

# 查找并包含 Android 日志库，方便在 C++ 中打印 logcat
find_library(log-lib log)

# --------------------------------------------------------------------
# 核心步骤 4: 链接所有库
# 将 whisper 和 llama 的静态库链接到你的共享库中。
# 显式链接 llama.cpp 的 ggml 组件和 common 库，并注意链接顺序。
# ${log-lib} 是为了让你可以在 C++ 代码中使用 __android_log_print。
# 目标名 "whisper" 和 "llama" 是由它们各自项目中的 CMakeLists.txt 定义的。
# --------------------------------------------------------------------
target_link_libraries(
    demollm-native-lib
    PRIVATE
    # 确保 ggml 组件在 llama 之前链接
    ggml-base
    ggml-cpu
    ggml
    common # 链接 llama 的 common 库
    mtmd
    whisper
    llama
    ${log-lib}
)