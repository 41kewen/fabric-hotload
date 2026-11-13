# Third-party notices

本项目自身采用 Apache License 2.0，完整条款见 LICENSE。下列依赖保留其各自许可证。

## ASM 9.10.1 — BSD-3-Clause

Agent 在 Fabric 运行环境中使用 `org.ow2.asm:asm` 与 `org.ow2.asm:asm-tree`，不重复打包它们。
来源：https://asm.ow2.io/ ，许可证：https://asm.ow2.io/license.html 。

Copyright (c) 2000-2011 INRIA, France Telecom
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holders nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
THE POSSIBILITY OF SUCH DAMAGE.

## Gradle Wrapper 9.8.2 — Apache-2.0

Wrapper 脚本及 JAR 从 Fabric 官方 26.2 示例取得；保留脚本中的上游版权头。
Wrapper JAR 已与 Gradle 官方 SHA256 核对：
`3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`。

## 构建/运行依赖（不复制其源码）

- Fabric Loader 0.20.1、Fabric API 0.161.0+26.2、Fabric Loom 1.19.4：Apache-2.0。
- Gson 2.13.2：Apache-2.0，由 Minecraft/Fabric 运行环境提供，未嵌入 Agent。
- JUnit Jupiter 5.13.4：EPL-2.0，仅用于测试。
- Mixin：MIT，由 Fabric 运行环境提供。
- Minecraft：专有软件，受 Minecraft EULA 约束，不包含在本项目交付包中。

下载的服务器、JDK、Gradle 完整分发包和依赖缓存不提交到源码仓库。

## 旧版 Minecraft 官方映射

1.x 构建通过 Loom 使用 Mojang 官方映射；映射中的版权及使用条款由上游保留。映射仅存在于开发依赖缓存中，不打包或提交到本项目，不分发 Minecraft 类与游戏 JAR。
