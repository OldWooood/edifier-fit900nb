# EDIFIER Fit900NB 蓝牙协议笔记

> 目标设备：EDIFIER Fit900NB（型号 EDF200174，蓝牙名 `EDIFIER Fit900NB`）
> 协议来源：Edifier Connect v8.5.5 官方 APK 逆向 + HyperEars 社区文档 + **2026-11-08 真机抓包验证**。
> 状态：SPP 连接、明文帧、CRC、电量、ANC 查询/设置、音乐/游戏/空间音效场景模式、版本、名称、D8 能力查询**已在真机确认**。EQ、LDAC 等未展开。

## 1. 设备与配对信息

从官方 App 的产品配置中整理出的设备信息（本项目不分发官方 APK、配置或密钥）：

| 项目 | 值 |
|---|---|
| ProductName | `Fit900NB` |
| ProductModel | `Fit900NB` |
| 型号 | `EDF200174` |
| 芯片 | 杰理 `JL7018F8`（拆解报告） |
| ProductSearchUuid | `0000e900-0000-1000-8000-00805f9b34fb` |
| ProductServiceUuid | `4809e901-1a48-11e9-ab14-d663bd873d93` |
| ProductSPPUuid | `EDF00000-EDFE-DFED-FEDF-EDFEDFEDFEDF` |
| HeadsetType | `2`（头戴式耳机） |
| IsTWS | `false`（单机，不是左右耳独立 TWS） |
| protocol-version | `2` |
| encryption-method | `0`（明文，不做 XOR） |

同目录的 `Fit900NB Turbo` 是另一款产品（`00007303-...`），不要混用。

## 2. 传输层

Fit900NB 同时暴露经典蓝牙 SPP 和 BLE GATT，官方 App 对这款产品选择 **SPP**（`ProductSPPUuid` 非空）。

- SPP Service UUID：`EDF00000-EDFE-DFED-FEDF-EDFEDFEDFEDF`
- 连接失败时回退到 **RFCOMM channel 1**
- 连接前先 `cancelDiscovery()`
- 不需要 BLE 配对后的 GATT 读写；SPP 建立在经典蓝牙链路上

## 3. 帧格式（protocol-version = 2）

### App → 耳机

```text
[0xAA][0xEC][CMD][LEN_H][LEN_L][PAYLOAD...][CRC]
```

### 耳机 → App

```text
[0xBB][0xEC][CMD][LEN_H][LEN_L][PAYLOAD...][CRC]
```

旧固件可能用 `0xCC` 作为响应头；本 App 同时接受 `0xBB` 和 `0xCC`。

| 字段 | 长度 | 说明 |
|---|---:|---|
| Header | 1 | 发送 `0xAA`；接收 `0xBB`，兼容 `0xCC` |
| AppCode | 1 | `0xEC` |
| CMD | 1 | 命令字 |
| Length | 2 | 大端，payload 长度，不含头/CRC |
| Payload | N | Fit900NB 为 **明文**（encryption-method = 0） |
| CRC | 1 | 前面所有字节求和后 `& 0xFF` |

官方代码里的 CRC 实现（`CommandManager.addCRC`）：

```text
crc = (sum(header..payload) + 0) & 0xFF
```

BLE v1/旧协议才使用 `0x2019` 偏移和 2 字节 CRC；Fit900NB 不走这条路径。

### 例子

查询降噪：

```text
AA EC CC 00 00 62
```

`0xAA(170) + 0xEC(236) + 0xCC(204) = 610 = 0x262`，`& 0xFF = 0x62`。  
注意：不要套用旧版 BLE v1 的 `+0x2019` 算法；Fit900NB 的 v2 帧就是简单的 `sum & 0xFF`。

## 4. 常用命令

命令字来自官方 `CommandIndex`，与 HyperEars 文档一致。

| 功能 | CMD | 方向 | Payload |
|---|---:|---|---|
| 电量查询（整机） | `0xD0` | 查询 | 空 |
| 设备状态 / TWS / 电量 | `0xF2` | 查询 | 空 |
| 降噪查询 | `0xCC` | 查询 | 空 |
| 降噪设置 | `0xC1` | 设置 | `[ancIndex][ancValue]` |
| 关机 | `0xCE` | 设置 | 空 |
| 能力 / D8 查询 | `0xD8` | 查询 | 空 |
| 固件版本 | `0xC6` | 查询 | 空 |
| 设备名 | `0xC9` | 查询 | 空 |
| 场景模式查询 | `0x08` | 查询 | 空或 `[dolbyFlag]` |
| 场景模式设置 | `0x09` | 设置 | `[scene]`，可选 `[scene][menu]` |
| EQ 查询 | `0xD5` | 查询 | 空 |
| EQ 设置 | `0xC4` | 设置 | `[eqIndex]` |

Fit900NB 的场景值：

| scene | 模式 |
|---:|---|
| 0 | 音乐 |
| 1 | 游戏 |
| 2 | 空间音效 |

查询响应为 `[scene][menu]`，第二个字节是子菜单/状态（Fit900NB 常见为 `0x06`）。

> `0xD8` 的响应是一个功能位图。官方 App 用它决定是否显示电量、ANC、场景模式等入口。不同固件位定义会变，本 App 只展示原始十六进制，不强行解释。

## 5. 降噪（ANC）

### 查询

发送 `AA EC CC 00 00 62`。

Fit900NB 真机响应（3 字节 payload）：

```text
BB EC CC 00 03 10 01 06 8D
              └─ index=0x10, value=1, gain=6
```

### 设置

```text
AA EC C1 00 02 [index] [value] [crc]
```

真机切到防风噪：

```text
TX  AA EC C1 00 02 10 03 6C
RX  BB EC C1 00 03 10 03 06 84   ← 设置确认
RX  BB EC CC 00 03 10 03 06 8F   ← 随后的状态查询
```

- `index`：设备返回的 ANC 槽位，不同型号不同；Fit900NB 实测为 `0x10`
- `value`：模式值
- 第三个字节 `gain`：环境声增益，普通模式为 6

官方 App 并不硬编码槽位；它从 `0xD8`/`0xF2` 或 `0xCC` 响应里拿到 `index`，再用 `CommandParserHelper.getANCBean(index, value, gain)` 查表。Fit900NB 真机返回的 `index` 就是 **`0x10`**，和产品描述的“深度降噪 / 舒适降噪 / 防风噪 / 环境声 + 降噪关”共 5 档完全吻合：

| index | value | 模式 |
|---:|---:|---|
| `0x10` | 1 | 深度降噪 |
| `0x10` | 2 | 舒适降噪 |
| `0x10` | 3 | 防风噪 |
| `0x10` | 4 | 环境声 |
| `0x10` | 5 | 降噪关 |

其他固件或同系列型号可能返回 `0x1B`、`0x2E`、`0x2F` 等槽位。App 会优先使用耳机实际返回的 index，并按内置表切换模式名称；如果返回的 index 不在表里，会显示 `模式 1..6`，仍可发送。

其他常见槽位（来自官方 `getANCBean`）：

| index | 模式值顺序 |
|---:|---|
| `0x1B` | 1 深度 / 2 舒适 / 3 宽频 / 4 防风 / 5 环境声 / 6 关 |
| `0x1F` | 1 深度 / 2 舒适 / 3 轻度 / 4 环境声 / 5 防风 / 6 关 |
| `0x2E` | 1 自适应 / 2 深度 / 3 舒适 / 4 防风 / 5 环境声 / 6 关 |
| `0x2F` | 1 深度 / 2 舒适 / 3 防风 / 4 环境声 / 5 关 |

### 关于响应解析

HyperEars 的抓包显示 v2 响应 payload 是 `[index][value]`。官方 App 的 `parseANC` 在 `bleVersion == 2` 时会跳过第一个字节，只把第二个字节当作当前值——因为它已经从别的状态里知道 index。两种解析方式本 App 都兼容：

- payload ≥ 2：优先按 `[index][value]` 解析；
- payload = 1：把唯一字节当作 value，index 使用上次值或默认 `0x10`。

## 6. 电量

### 0xD0（Fit900NB 实际返回整机电量）

真机：

```text
TX  AA EC D0 00 00 66
RX  BB EC D0 00 01 60 D8
              └─ 0x60 = 96%
```

payload 第一个字节即百分比。若最高位被用作充电标志：

```text
percent = b & 0x7F
charging = (b & 0x80) != 0
```

官方 `parseBattery` 只返回原始字节，没有处理充电位，因此这一段属于防御性解析。

### 0xF2（Fit900NB 走 type=4 分支）

真机：

```text
TX  AA EC F2 00 00 88
RX  BB EC F2 00 06 04 00 00 60 00 00 03
              └─ type=4, left=0, right=0, battery=0x60=96%, charging=0, ancFlag=0
```

官方 `parseTWSBattery` 的规则：

- payload[0] == 4：非 TWS 设备，`battery = payload[3]`，`charging = payload[4] == 1`
- 否则：`left = payload[1]`、`right = payload[2]`、`case = payload[3]`、`charging = payload[4] == 1`
- payload 长度 ≥ 6 时，`payload[5]` 还包含 ANC 可用位

Fit900NB 是单机头戴，实测走 `type == 4` 分支。App 对两种分支都做了展示。

## 6.1 真机抓包记录（2026-11-08）

设备：`EDIFIER Fit900NB`，固件 `V1.0.1`，Android 16 / API 36。

```text
# 连接后自动查询
TX  AA EC D8 00 00 6E
RX  BB EC D8 00 15 01 01 01 05 01 01 01 01 00
                   01 01 01 20 01 01 00 1F 70 00 10 00 64   ← D8 能力位图

TX  AA EC CC 00 00 62
RX  BB EC CC 00 03 10 01 06 8D                         ← ANC 0x10 / 深度降噪 / gain 6

TX  AA EC D0 00 00 66
RX  BB EC D0 00 01 60 D8                               ← 96%

TX  AA EC F2 00 00 88
RX  BB EC F2 00 06 04 00 00 60 00 00 03                ← type=4, 96%, 未充电

TX  AA EC C6 00 00 5C
RX  BB EC C6 00 03 01 00 01 72                         ← V1.0.1

TX  AA EC C9 00 00 5F
RX  BB EC C9 00 10 45 44 49 46 49 45 52 20 46 69 74
                   39 30 30 4E 42 E4                    ← "EDIFIER Fit900NB"

TX  AA EC 08 00 00 9E
RX  BB EC 08 00 02 00 06 B7                            ← 音乐 (0)

# 切到游戏
TX  AA EC 09 00 01 01 A1
RX  BB EC 09 00 01 01 B2
RX  CC EC C4 00 01 FF 7C                               ← 耳机主动上报，appCode=CC
TX  AA EC 08 00 00 9E
RX  BB EC 08 00 02 01 06 B8                            ← 游戏 (1)

# 切到空间音效
TX  AA EC 09 00 01 02 A2
RX  BB EC 09 00 01 02 B3
RX  CC EC C4 00 01 FF 7C
TX  AA EC 08 00 00 9E
RX  BB EC 08 00 02 02 06 B9                            ← 空间音效 (2)

# 切回音乐
TX  AA EC 09 00 01 00 A0
RX  BB EC 09 00 01 00 B1
RX  CC EC C4 00 01 00 7D
TX  AA EC 08 00 00 9E
RX  BB EC 08 00 02 00 06 B7
```

注意：场景模式切换时耳机会发一帧 `CC EC C4 ...`（appCode 是 `0xCC`，不是 `0xEC`），App 只记录不解析。不同的按键/状态也可能有类似主动上报。

### 关机

官方 `CommandManager.setShutDown()` 构造的是：

```text
AA EC CE 00 00 64
```

payload 为空。`0xCE` 是官方 `CommandIndex.shutdown`，社区文档也把它标为 POWER OFF。本 App 在已连接模块里提供“关闭耳机电源”按钮，带二次确认。

> 为避免测试时把耳机真的关掉，本项目**没有对真机执行关机命令**；如果你要验证，点击按钮并在耳机上重新开机即可。若某些固件不响应，抓一帧 `TX AA EC CE 00 00 64` 日志反馈。

## 7. 与 HyperEars BES 文档的差异

| 项目 | BES 默认方言（W860NB Pro 等） | Fit900NB |
|---|---|---|
| SPP UUID | 相同 | 相同 |
| 帧头/长度/CRC | 相同 | 相同 |
| Payload | XOR `0xA5` | **明文** |
| 加密字段 | `encryption-method = 16` | `encryption-method = 0` |
| ANC 槽位 | `0x10` / `0x1B` 等 | 以设备返回为准，通常 `0x10` |

因此 HyperEars 的 `edifier-bes-protocol.md` 仍然是最重要的参考，只是 Fit900NB 把 XOR 层去掉了。

## 8. 官方 APK 里的关键类

| 类 | 作用 |
|---|---|
| `com.edifier.product_connect.connect.spp.SPPManager` | SPP 连接、UUID、channel 1 回退 |
| `com.edifier.product_connect.command.CommandBean` | 组帧 / 拆帧 |
| `com.edifier.product_connect.command.CommandManager` | CRC、XOR、各命令构造 |
| `com.edifier.product_connect.command.CommandIndex` | 命令字表 |
| `com.edifier.product_connect.command.command_enum.EncryptionCode` | `Default(0)` / `Encryption10(16, 0xA5)` |
| `com.edifier.lib_base.handler.CommandParserHelper` | ANC / 电量 / 版本 / D8 解析 |
| `com.edifier.edifierconnect.util.CryptUtil` | `products_release.json` 的 AES 解密 |

> 官方 App 对产品配置做了加密/混淆。本项目**不公开**相关密钥、IV 或官方配置文件，也不分发官方 APK、固件、图片、音频等专有资源。协议信息仅用于互操作性研究。

## 9. 仍需确认 / 未实现

1. `0xD8` 功能位图每一位的确切含义（本 App 只展示原始 hex）。
2. EQ 查询/设置（`0xD5`/`0xC4`）的完整模式表；目前只记录原始响应。
3. EQ 细调、LDAC/LHDC、双设备连接、提示音量、定时关机等命令。
4. 耳机主动上报 `CC EC C4 ...` 的语义（场景模式切换时出现）。

App 的「高级 / 调试」页会把每一帧 TX/RX 原样打印出来；继续抓包即可扩展。
