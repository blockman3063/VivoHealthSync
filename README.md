# VHSync

把 **vivo 健康**（`com.vivo.health`，国行版）的数据同步进 Android 原生
**健康数据共享（Health Connect）**。

一个 APK，两个角色：LSPosed 注入端跑在 vivo 健康进程里**只读**取数，
模块主进程持有 Health Connect 权限负责写入，两者通过 ContentProvider + token 通信。

## 来源与许可

架构与部分实现参考 [OHSync](https://github.com/bqj6666/OHSync)（MIT，作者 bqj6666），
本项目是它的 vivo 适配版。原始 MIT 版权声明见 [LICENSE](LICENSE)。

与 vivo 无任何关联。仅用于读取你自己设备上、你自己的健康数据。

## 为什么需要它

vivo 健康国行版没有 Health Connect 集成，数据只存在它自己的本地库里
（`cc_health_<hash>.db`，SQLCipher 加密）。

本项目**不复制、不解密、不处理口令**，而是在 vivo 健康进程内截获它自己打开的
`net.sqlcipher.database.SQLiteDatabase` 实例复用，只做 `SELECT`。

## 环境要求

- Android 14+（本机实测 Android 17 / API 37）
- 可用的 Health Connect
- Root（KernelSU / Magisk）+ LSPosed
- 构建：JDK 21，`./gradlew :app:assembleDebug`

## 安装

1. 安装 APK，**先打开一次主 App**（系统不允许启动从未打开过的应用的数据接口）
2. 点「去授权」，勾选要授予的健康数据类型
3. 在 LSPosed 里启用本模块，作用域**只勾 vivo 健康**
4. 打开一次 vivo 健康，让它把数据库准备好
5. 回到主 App，点「立即同步」

> **每次覆盖安装后，Health Connect 会撤销本 App 的全部健康权限**，需要重新点一次
> 「去授权」。这是平台行为，不是 bug。

## 同步哪些数据

| vivo 健康 | Health Connect |
|---|---|
| 步数 / 距离 / 活动卡路里 | `StepsRecord` / `DistanceRecord` / `ActiveCaloriesBurnedRecord` |
| 心率 | `HeartRateRecord` |
| 睡眠（含深睡/浅睡/REM/清醒分段） | 带 `stages` 的 `SleepSessionRecord` |
| 血氧 | `OxygenSaturationRecord` |
| 体重 | `WeightRecord` |
| 运动记录 | `ExerciseSessionRecord` |

Health Connect 没有对应类型的（压力、鼾声、呼吸暂停、HRV、睡眠评分）**按设计丢弃**，
App 内会完整列出，不会静默丢弃。血压：设备不支持，不实现。

## 隐私

没有网络代码。数据只在同一台手机的两个进程之间流动。
日志不打印健康数据正文，也不打印任何口令。

本仓库是公开版本：探库产物（表结构清单）、设备截图、数据库副本、探针原始日志
都保留在本机 `.probe/`，已加入 `.gitignore`，**不会上传**。
`docs/` 里用于界面/库对账的个人健康测量值已脱敏，只保留相对描述与结论。

## 状态

| 里程碑 | 状态 | 记录 |
|---|---|---|
| M0 探库 | ✅ 完成（vivo 健康 7.1.3.13，主库 154 表） | [探库结论](docs/00-探库结论.md) |
| 映射设计 | ✅ 草案已定稿（步数/心率/睡眠/运动口径均已对账） | [映射草案](docs/01-映射设计草案.md) |
| M1 脚手架 | ✅ 完成（注入、取库、读表均已验证） | [M1 记录](docs/02-M1-脚手架.md) |
| M2 读取端 | ✅ 完成（步数/心率经 IPC 到调试页，含真实数据对账） | [M2 记录](docs/03-M2-读取端.md) |
| M3 写入端 | ⏳ 待做 | 步数、心率写入 HC + 幂等验证 |
| M4 补全类型 | ⏳ 待做 | 睡眠/距离/卡路里/血氧/体重/运动 |
| M5 自动同步 | ⏳ 待做 | 增量 + 全窗口回填 + 后台保活 |

> 当前是 **M2**：只读取、不写入 Health Connect。上表「同步哪些数据」是最终目标清单，
> 不是现阶段已实现的功能。界面上请用「读取端预览」卡片验证读取链路，
> 「立即同步」暂不产生写入。
