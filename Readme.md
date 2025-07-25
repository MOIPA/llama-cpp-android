# BG

示例按照官方的llama.android，官方示例内使用的很多common函数已经废弃，且编译好后的install dir内缺失common静态库，加之官方示例的android项目每次运行都得去根项目编译

基于最新版本的llama.cpp预编译了动态库和静态库（ndk的工具链），方便快捷启用的普通JNI项目，模型文件放置在assets内

大多数设备支持openCL，已开启openCL支持，具体offload层数需要根据模型实际确定

# GPU

jniLibs/arm64-v8a/cpu 该目录下的是纯cpu版本的链接库，上级目录的是支持openCL的链接库，支持GPU推理

## 推理速度

warmup time 超过5秒的无实际意义了，直接标注为infinity

### 环境

+ 型号: 努比亚Z60 Ultra
+ SOC: 骁龙8Gen3 3.3GHZ 
+ GPU: 高通750
+ OS驱动: openCL
+ 模型: qwen2.5-1.5b-instruct
+ 量化: Q8_0
+ Layers: 28
+ Size: 1.8G
+ Params: 1.8B
+ Mutimodal: OFF

### 实际测速


|OffLoad Layers|pp (tps)|tg (tps)|warmup (s)|
|----|----|----|----|
|0|75 |23 |x|
|5|81 |5.9 |9|
|10|81 |5.9 |0.795|
|15|83 |4 |1.17|
|20|75|2.4|4.74|
|25|infinity|infinity|11|
|28(MAX)|75|2.2|2.2|


# 注意点

目前还不支持vulkan

