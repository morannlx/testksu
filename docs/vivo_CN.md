# vivo/iQOO 运行时兼容

当前 vivo/iQOO 兼容逻辑已经改为全自动运行时处理。正常使用 Manager 修补
`init_boot` 或 `boot`，选择标准 KMI 即可，不需要修补 `vendor_boot`，也不需要
`_vivo` 专用 LKM。

## 机制

1. `ksuinit` 先按标准方式加载内置 `kernelsu.ko`。
2. 如果内核仅因为 version magic 不匹配而拒绝加载，`ksuinit` 会读取本次加载产
   生的内核日志，提取内核要求的 vermagic，改写内存中的模块 `.modinfo`，然后重
   试 `init_module`。
3. 内核模块会直接 hook arm64 的 `init_module` 和 `finit_module`，当待加载模
   块 `.modinfo` 里的 `name=` 精确等于 `vr` 时，返回成功并阻止真正加载。

解析失败和非 vermagic 错误不会盲目重试；`vr.ko` 识别失败也会放行原始系统调
用。Manager 侧没有 vivo 开关，也不会再走 `vendor_boot` 删除模块流程。

## 使用流程

```text
Manager -> 安装 -> 选择文件 -> 选择 init_boot.img 或 boot.img
        -> 选择任意标准 KMI
        -> 刷入修补后的镜像
        -> 启动时自动执行 vermagic fallback 和 vr.ko 拦截
```

## 验证

启动后内核日志中应能看到类似内容：

```text
init_module_filter: hooked init_module + finit_module
init_module_filter: blocked vr (init_module)
Replaced module vermagic with kernel-required value: "..."
```

只有设备实际尝试加载 `vr.ko` 时才会出现 `blocked vr`；只有首次模块加载确实需
要运行时修正 vermagic 时才会出现 vermagic replacement 日志。
