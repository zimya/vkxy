voxy是一个Minecraft远视距模组。

但是voxy只支持高版本OpenGL，因此无法在macOS上运行。

与此同时Minecraft官方在26+版本添加了vulkan支持，这使得voxy在macOS下流畅运行成为可能。

---

你的任务是：将voxy移植到macOS，重写OpenGL后端为vulkan。

移植的目标版本：26.3, Minecraft的反编译后的源代码我放在了./MinecraftSrc/目录下，分别有26.3和26.2的源代码。

资源和普通光栅绘制使用 RenderPearl API

Voxy 的 compute 和 GPU-driven culling增加自己的后端接口。Vulkan 实现需要获得底层 Vulkan command buffer、buffer 和 pipeline 能力；不能只依赖 RenderPearl 的公开 API。

你不需要像原版一样硬依赖sodium，目前已经移除了 Iris、Nvidium、Sodium 的 Gradle/Fabric 依赖，同时移除了 Lithium。

---

运行测试世界的命令是

./gradlew runTestWorld

每次修改完都要用这个命令验证，运行20s无问题、无崩溃、无报错才算验证完毕。

---

需要注意的是，在 macOS/MoltenVK 上，不能完整复刻 Voxy 当前的 GPU 生成 count 后由 GPU 决定 draw 数的提交机制及其性能特性。但可以完整复刻算法和画面：GPU 仍生成 indirect commands，只是最终由 CPU 预先编码固定上限的普通 indirect draws，未使用命令的 instanceCount=0。这会有明显 CPU 编码开销，必须成为 macOS 的显式性能降级路径。

不要试图把 Voxy 直接建立在 26.3 的公开 RenderPearl API 上。它只提供 vertex/index/uniform/indirect buffer，UniformType 也只有 sampler、uniform buffer 和 texel buffer；没有 storage-buffer 用法、compute pipeline 或 dispatch API。GpuBuffer、UniformType。

因此应把 RenderPearl 当作资源/帧流程/原版 render target 的所有者，并实现一个受版本隔离的 VulkanBridge，通过 mixin accessor 访问 Minecraft 的 Vulkan command buffer、buffer、texture、pipeline 和同步点。所有 Mojang 私有类访问只放在这个 bridge 模块，不能散落到 LOD、world 或 meshing 逻辑里。
