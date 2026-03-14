# Her / AIBox for Termux

一个基于 `termux-app` 深度改造的 Android 项目：它保留了 Termux 的终端与会话能力，同时把 AI 对话执行器、动态插件、依赖商店、网页渲染、设备能力调用和分层记忆系统整合进了同一个 App。

这不是官方 Termux 仓库，而是一个面向“手机端 Agent / AI 助手”场景的 Fork。

## 项目定位

这个项目试图把手机上的 Termux 从“终端模拟器”扩展成“可执行任务的 AI 容器”：

- 保留原始 Termux 的终端会话、多 session、bootstrap 安装与命令执行能力。
- 新增聊天式入口 `SimpleExecutorActivity`，让模型通过结构化动作调用本地工具。
- 把 `termux-api` 的大量能力直接内嵌到 App 进程里，减少外部依赖。
- 支持动态生成并加载 Android 插件 Activity，让 AI 产出的 Java 代码可以被编译并运行。
- 为长任务加入任务状态、检查点、记忆检索、历史压缩等 Agent 能力。

## 核心能力

### 1. AI 对话执行器

主入口是启动页 `LaunchActivity`，随后进入聊天执行器 `SimpleExecutorActivity`（定义在 [`app/src/main/java/com/termux/app/IDE.kt`](/Users/xzs/Downloads/termux-app-0.118/app/src/main/java/com/termux/app/IDE.kt)）。

执行链路大致是：

1. 用户输入自然语言任务。
2. `EnhancedAgentHelper` 结合会话历史、记忆、失败记录构建 prompt。
3. `callAIAgent()` 调用外部大模型。
4. 模型只允许返回形如 `["步骤描述", 动作表达式]` 的结构化动作。
5. `executeParsedStep()` 执行动作，例如：
   - `exec(...)`：在 Termux bash 中执行命令
   - `termux-*`：调用设备能力
   - `runJava(...)`：编译并运行 Java 插件
   - `message(...)`：向用户输出最终结果

这套机制本质上是一个移动端 ReAct Agent。

### 2. 持久命令执行环境

[`IDE.kt`](/Users/xzs/Downloads/termux-app-0.118/app/src/main/java/com/termux/app/IDE.kt) 中实现了 `HerExecSession`：

- 基于持久 bash 进程而不是一次性 shell。
- 自动给 `apt/pkg/pip/npm/conda` 等命令补充非交互参数。
- 用 sentinel 标记抓取退出码和当前目录。
- 支持多会话池，减少复杂任务中的上下文丢失。

这比“每步新开 shell”的 Agent 更适合连续构建、调试和依赖安装。

### 3. 产品级记忆系统

自定义 Agent 能力主要集中在以下文件：

设计特点：

- 工作记忆 / 短期记忆 / 长期记忆分层存储。
- 检索时综合考虑语义、时间、访问频率、重要性、成功率。
- 复杂任务支持状态持久化、步骤依赖、检查点与恢复。
- 对长期对话做压缩，避免 prompt 无限膨胀。

### 4. 内嵌 Termux:API 能力

直接复用了 `termux-api` 源码中的 API 实现，并在 App 进程里桥接：

- 电池、剪贴板、短信、联系人、位置、Wi‑Fi、亮度、手电筒等
- 不在官方 `termux-api` 范围内的部分，还补了直接 Android API 调用
- 配合动作解析，让模型可以通过统一动作格式调用设备能力

### 5. 动态插件构建与运行

插件相关能力主要在：

- [`PluginBuildPipeline.kt`](/Users/xzs/Downloads/termux-app-0.118/app/src/main/java/com/termux/app/PluginBuildPipeline.kt)
- [`DynamicPlugin.kt`](/Users/xzs/Downloads/termux-app-0.118/app/src/main/java/com/termux/app/DynamicPlugin.kt)
- [`PluginBuildService.kt`](/Users/xzs/Downloads/termux-app-0.118/app/src/main/java/com/termux/app/PluginBuildService.kt)

流程是：

1. 让模型只输出一个可编译的 `MainActivity.java`。
2. 将源码写入临时目录。
3. 使用 Termux 内的 `javac` / `jar` / `d8` 编译出 dex。
4. 通过 `DexClassLoader` 动态加载 Activity。
5. 下次启动时若存在插件，则优先进入插件 Activity。

这使它不仅是“会执行命令的聊天框”，还是一个“AI 可生成并运行前端壳层”的宿主。

### 6. 依赖商店与网页渲染

- [`DepStoreActivity.kt`](/Users/xzs/Downloads/termux-app-0.118/app/src/main/java/com/termux/app/DepStoreActivity.kt)：根据任务需要展示推荐依赖、必装依赖和手动补装入口。
- [`FullScreenWebActivity.kt`](/Users/xzs/Downloads/termux-app-0.118/app/src/main/java/com/termux/app/FullScreenWebActivity.kt)：把 AI 生成或附件中的 HTML 以内嵌 WebView 全屏展示。

## 模块结构

仓库目前是一个标准 Android 多模块工程：

- `app`: 主应用，包含 Termux UI 与所有 AI/Agent 扩展
- `termux-shared`: Termux 共享工具类、设置、日志、shell 封装
- `terminal-emulator`: 终端模拟核心
- `terminal-view`: 终端渲染与交互视图

## 与上游 Termux 的主要差异

和上游 `termux-app` 相比，这个 Fork 不是局部打补丁，而是产品定位已经发生变化：

- 默认 launcher 从纯终端入口变成了自定义启动页。
- 新增 AI 配置、对话 UI、任务循环和执行日志。
- Manifest 中声明了更广泛的系统权限和设备能力。
- 内置了动态插件与网页展示链路。
- 增加了大量和移动端 Agent 相关的状态持久化逻辑。

换句话说，它更接近“以 Termux 为底座的 Android Agent 宿主”。

## 构建说明

### 环境

- Android Gradle Plugin: `7.4.2`
- Kotlin: `1.9.22`
- `compileSdkVersion=34`
- `minSdkVersion=24`
- `targetSdkVersion=28`
- NDK: `23.2.8568313`

### 构建命令

```bash
./gradlew assembleDebug
```

首次编译时会下载 Termux bootstrap 包。`app/build.gradle` 还依赖本地 Termux 环境中的一些工具与仓库内置资产。

### 本仓库已做的发布友好修正

原先 [`gradle.properties`](/Users/xzs/Downloads/termux-app-0.118/gradle.properties) 里硬编码了开发机本地 JDK 路径；这会导致其他机器直接构建失败。现在已改为注释说明，要求使用者在本地 Gradle 配置里自行设置。

## 发布前需要注意

这是一个功能很强、权限也很多的 App，公开发布前建议先做一轮安全与产品边界审查：

- Manifest 里声明了大量高权限能力，包含短信、联系人、通话、定位、录音、相机、悬浮窗、安装包、写系统设置等。
- 包名仍为 `com.termux`，与官方生态容易混淆。
- 仓库里包含 `app/dev_keystore.jks`，它只能用于开发调试，不应用于正式发布签名。
- AI 供应商调用、设备能力调用、动态代码加载都属于高风险区域，建议补充权限说明和安全策略。
- 当前工作区存在很多未提交改动，且尚无首个 commit；发布前建议先整理一次提交历史。

## 建议的开源描述

如果准备公开到 GitHub，可以把这个项目描述为：

> An experimental Android AI agent shell built on top of Termux, combining terminal sessions, on-device task execution, dynamic plugin loading, embedded Termux:API capabilities, and a layered memory system.

## 当前状态

从当前代码看，这个仓库已经不是 demo，而是一个相对完整的实验性产品原型：

- 有入口页、对话页、抽屉、多会话、日志面板、依赖流转
- 有模型接入、动作协议、执行器、失败重试
- 有任务状态、记忆层、对话压缩
- 有插件构建和运行时加载
- 有 Web 展示与设备 API 桥接

但它仍明显处在“快速演化中的私有 fork”阶段，离对外稳定开源还差：

- 更清晰的命名与品牌统一
- 更干净的提交历史
- 更严格的权限与安全说明
- 更完整的构建验证

## 相关文档

- [`QUICK_START.md`](/Users/xzs/Downloads/termux-app-0.118/QUICK_START.md)
- [`MEMORY_SYSTEM_SUMMARY.md`](/Users/xzs/Downloads/termux-app-0.118/MEMORY_SYSTEM_SUMMARY.md)
- [`MEMORY_SYSTEM_INTEGRATION.md`](/Users/xzs/Downloads/termux-app-0.118/MEMORY_SYSTEM_INTEGRATION.md)
- [`PLUGIN_BUILD_SUPPORT.md`](/Users/xzs/Downloads/termux-app-0.118/PLUGIN_BUILD_SUPPORT.md)

## License

继承上游仓库的开源协议；请同时核对本仓库新增代码、资源和第三方依赖的许可证兼容性。
