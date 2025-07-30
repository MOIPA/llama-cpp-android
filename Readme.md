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
+ GPU: 高通 Adreno 750
+ OS驱动: openCL
+ Layers: 28
+ Mutimodal: OFF

### 实际测速

#### 1. 量化: Q8_0

+ 模型: qwen2.5-1.5B-instruct_Q8_0
+ pp: 64
+ tg: 32
+ nr: 3

> ngl: n_gpu_layers

|ngl|pp (tps)|tg (tps)|warmup (s)|
|----|----|----|----|
|0|75 |49 |2.22|
|5|79 |31 |4|
|10|71 |19 |5.6|
|15|62 |12 |9|
|20|28|9|11|
|25|24|7|13|
|28(MAX)|22|5|18|


#### 2. 量化: Q4_K_M

+ 模型: Qwen3-1.7B-Q4_K_M
+ pp: 64
+ tg: 32
+ nr: 3

|ngl|pp (tps)|tg (tps)|warmup (s)|
|----|----|----|----|
|0|89 |42 |2.6|
|5|48 |21 |5.2|
|10|41 |12 |8.5|
|15|32 |8.9 |11.8|
|20|24|6.4|16.2|
|25|24|5.6|18.6|
|28(MAX)|19|5.1|20.62|

#### 3. 量化: Q4_0

+ 模型: Qwen3-1.7B-Q4_0
+ pp: 64
+ tg: 32
+ nr: 3

|ngl|pp (tps)|tg (tps)|warmup (s)|
|----|----|----|----|
|0|240 |50 |2.1|
|5|140 |23 |4.31|
|10|93 |12 |8.1|
|15|73 |8.5 |11.79|
|20|62|6.6|15|
|25|52|5.3|20.6|
|28(MAX)|51|4.3|22.94|

# 会话管理

单轮最大生成长度 nlen

上下文最大长度的限制比较简单，超出就直接清空kv历史缓存，应该清除最老的kv缓存

# 注意

> Vulkan usually slower than CPU.
>
> OpenCl only work with Snapdragon 8 Gen 3 and Snapdragon 8 Elite .


1. OpenCL 目前只支持 f32、f16、q6_K 和 q4_0，特别对于Qwen系列模型，它的 q4_K 和 q5_K 张量需要在 CPU 上运行，也不支持 MoE 模型，所有张量仍会存储在CPU中。

2. 即使是q4_0，实际在openCL后端下测试的性能随着offload到GPU的层数变多，性能更差且手机更容易发烫。

