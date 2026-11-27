# 第三方依赖清单（Vendors）

本分支（Tomcat 加固版）**没有登记任何外部 git submodule**，源码树内不包含第三方独立仓库检出。

## 说明

- 仓库根目录不存在 `.gitmodules`，也没有指向外部 git 仓库的锁定指针（gitlink）。
- `output/` 目录下的内置 JDK（`output/tooling/jdk-21`）、构建类库（`output/build-libs`）属于**构建/工具产物**，已在 `.gitignore` 中排除，不属于源码 vendor；需要时按标准 JDK 21 与对应构建工具自行准备。
- 自研加固代码（JSON 化、HPack Huffman、PEM 密钥、连接池加固等）均在源码树内，见 `FOX-FEATURES.md`。

## 风险提示

- 本仓库不依赖远程 submodule 即可完成源码查阅；编译所需工具链不在仓库内。
