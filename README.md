# Fit900NB 轻量控制台

一个只做一件事的 Android App：通过经典蓝牙 **SPP** 连接 **EDIFIER Fit900NB**，显示电量、切换降噪模式、切换音乐/游戏/空间音效三种模式，并保留完整的原始命令调试台。

官方 Edifier Connect APK 约 275 MB；这个 App 的 APK 约 **36 KB**，没有账号、没有广告、没有网络权限。

> 非官方项目，与漫步者无关联。协议来自官方 APK 逆向和社区抓包，并已在 Fit900NB（固件 V1.0.1，Android 16）真机上验证连接、电量、降噪、音乐/游戏/空间音效模式、版本和名称。

## 功能

- 自动查找系统已配对的 `EDIFIER` / `Fit900` 设备，不再展示整页设备列表
- 单实例 Activity：回到桌面再点图标会复用原界面，不会多开 MainActivity，SPP 连接状态保持
- SPP 连接：`EDF00000-EDFE-DFED-FEDF-EDFEDFEDFEDF`，失败自动回退 RFCOMM channel 1
- 电量：同时查询 `0xD0` 和 `0xF2`，兼容整机 / 左右耳 / 电池仓三种返回
- 降噪：查询 `0xCC`，设置 `0xC1`，自动使用耳机返回的 ANC index；按钮带约 2.2 秒防连点间隔和旋转等待动画，关机档切出时还会自动补发一次命令
- 内置 `0x10`、`0x1B`、`0x1F`、`0x2E`、`0x2F` 的官方模式表；未知 index 显示 `模式 1..6`
- 模式：查询 `0x08`，设置 `0x09`；`0=音乐`、`1=游戏`、`2=空间音效`
- 关机：`0xCE`，在已连接模块里；第一次点击变成“再次点击确认关机”，3 秒内再点一次才发送
- 设备信息：固件 `0xC6`、名称 `0xC9`
- 隐藏调试台：长按右上角 `SPP` 徽标显示；支持原始命令、TX/RX 日志和复制日志

## 安装

已经构建好的 APK：

- GitHub Releases：https://github.com/OldWooood/edifier-fit900nb/releases/latest
- 本地构建产物：`dist/Fit900NB.apk`

用 adb 安装：

```bash
adb install -r Fit900NB.apk
```

或者把 APK 传到手机，允许“安装未知来源应用”后手动安装。

首次打开会请求 **附近的设备 / 蓝牙连接** 权限。Fit900NB 需要先在系统蓝牙里配对；App 不负责首次配对，只会自动查找已配对的 EDIFIER/Fit900 设备。

Android 16（API 36）上如果没弹权限框，去 **设置 → 应用 → Fit900NB → 权限 → 附近的设备** 手动允许。

> SPP 同一时间通常只允许一个客户端。连接前请先断开/关闭官方 Edifier Connect，否则可能连不上或收到 busy。

## 使用

1. 打开系统蓝牙，确认 `EDIFIER Fit900NB` 已配对。
2. 打开 App，它会自动找到已配对的耳机；点「连接耳机」。
3. 连接成功后会自动查询能力、降噪、电量、状态、版本、名称、当前模式。
4. 点「降噪模式」里的一排按钮切换；切换时右上角会出现旋转动画，约 2.2 秒后才允许下一次操作。
5. 在「模式」卡片里点「音乐 / 游戏 / 空间音效」切换；切换期间按钮会短暂禁用。
6. 如果模式名称不对，点降噪卡片底部的「索引 0xXX」手动覆盖 ANC index。
7. 需要看原始协议时，长按右上角「SPP」徽标打开隐藏调试台；点「复制日志」反馈。
8. 「关机」在已连接模块里，第一次点击只进入确认状态，3 秒内再点一次才真正发送；关机后需要按耳机电源键重新开机。

## 构建

不依赖 Gradle / Android Studio，直接使用 Android SDK 的 `aapt2`、`d8`、`apksigner`：

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./build.sh
```

默认使用：

```text
build-tools/36.1.0
platforms/android-36/android.jar
```

可以通过 `BT_DIR` / `PLATFORM` 覆盖。产物在 `dist/Fit900NB.apk`。构建脚本会在本地 `keystore/` 生成并使用项目签名（该目录不提交到仓库）；GitHub Release 里的 APK 使用同一签名，方便覆盖安装。

项目结构：

```text
app/src/main/AndroidManifest.xml
app/src/main/java/com/deatrg/fit900nb/MainActivity.java
app/src/main/java/com/deatrg/fit900nb/SppClient.java
app/src/main/java/com/deatrg/fit900nb/Protocol.java
app/src/main/res/...
docs/fit900nb-protocol.md
build.sh
```

## 协议要点

完整笔记见 [docs/fit900nb-protocol.md](docs/fit900nb-protocol.md)。

```text
TX: AA EC CMD LEN_H LEN_L PAYLOAD... CRC
RX: BB EC CMD LEN_H LEN_L PAYLOAD... CRC
CRC = sum(前面所有字节) & 0xFF
```

Fit900NB 的产品配置是 `protocol-version=2`、`encryption-method=0`，也就是**明文**。同样使用 BES/Edifier SPP 帧格式的 W860NB Pro 等机型默认会 XOR `0xA5`；Fit900NB 不需要。

关键命令：

| 功能 | CMD |
|---|---|
| 电量 | `0xD0` |
| 设备状态 / 电量 | `0xF2` |
| 降噪查询 | `0xCC` |
| 降噪设置 | `0xC1` |
| 能力位图 | `0xD8` |
| 固件版本 | `0xC6` |
| 设备名 | `0xC9` |
| 模式查询 / 设置 | `0x08` / `0x09` |

## 已知限制

- 不做首次蓝牙配对；请先在系统设置中配对。
- 不做 BLE GATT 连接；Fit900NB 的官方配置提供 SPP UUID，本 App 走 SPP。
- 核心功能已在 Fit900NB 真机上验证（固件 V1.0.1，Android 16）：SPP、明文帧、CRC、`0xD0` 电量、`0xCC`/`0xC1` 降噪、`0x08`/`0x09` 音乐/游戏/空间音效模式、`0xC6` 版本、`0xC9` 名称。
- 设备名、固件版本等只做展示，不提供修改。
- 没有 OTA、EQ 细调、双设备连接、提示音量、定时关机等官方 App 的完整功能。

## 隐私

App 只申请 `BLUETOOTH_CONNECT`（Android 12+）和旧版 `BLUETOOTH` / `BLUETOOTH_ADMIN`，没有网络权限，不会上传任何数据。日志只保存在当前页面内存中。
