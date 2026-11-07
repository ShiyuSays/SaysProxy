# SaysProxyV3

SaysProxyV3 是一个用 Java 编写的 TCP 加密代理工具，包含：

- **服务端**：命令行程序，负责加密握手、账户登录、连接限制、访问日志和代理转发。
- **桌面客户端**：Java Swing 默认组件（不依赖第三方 UI 库），提供本机 SOCKS5 / HTTP 代理。
- **Android 客户端**：平台默认控件，提供同一套加密隧道和本机代理，APK 可直接安装。

> 本项目只应部署在你自己的服务器上，用于代理你有权访问的网络资源。
> 不要用它访问、扫描或攻击未经授权的系统。请遵守所在地区的法律法规和网络服务条款。

---

## 1. 交付内容

| 路径 | 说明 |
|---|---|
| `dist/SaysProxyV3-Server/SaysProxyV3-Server.jar` | 服务端，含启动脚本 |
| `dist/SaysProxyV3-Client/SaysProxyV3-Client.jar` | 桌面客户端，含启动脚本 |
| `dist/SaysProxyV3-Android/SaysProxyV3.apk` | Android 客户端（已签名） |
| `dist/SaysProxyV3-Source.zip` | 源码包（不含构建缓存和 Android SDK） |
| `dist/SaysProxyV3-Release.zip` | 三个客户端/服务端目录的发布包 |
| `src/` | 服务端、桌面端、公共协议源码 |
| `android/` | Android 源码、资源、签名调试密钥 |
| `test/IntegrationTest.java` | 端到端集成测试 |
| `scripts/build.ps1` | 重新构建服务端和桌面 JAR |
| `scripts/build-android.ps1` | 重新构建 Android APK |
| `README.md` | 本文件 |

服务端和桌面端只需要 **Java 8（8u161 或更高，支持 AES-256）或更高版本**，没有第三方运行时依赖。

---

## 2. 快速开始

### 2.1 启动服务端

Windows：

```bat
dist\SaysProxyV3-Server\start-server.bat
```

或者：

```bat
java -jar dist\SaysProxyV3-Server\SaysProxyV3-Server.jar
```

Linux / macOS：

```bash
java -jar SaysProxyV3-Server.jar
```

首次启动会在当前目录创建：

```text
config/
  server.properties          监听地址、端口、目标超时
  max-connections.properties          每用户会话数、每用户 IP 数、总连接数
  connection-password.txt    自动生成的服务器连接密码
  users.properties           账户数据库（服务端管理）
logs/
  access-YYYY-MM-DD.log      登录、目标、客户端 IP、流量统计
  audit-YYYY-MM-DD.log       启动、管理员操作、登录失败
```

启动后打开 `config/connection-password.txt`，把里面的密码填入客户端的
“服务器连接密码”。这个密码是随机生成的，客户端和服务器必须一致。

### 2.2 在服务端注册账户（只能服务端操作）

服务端控制台：

```text
/add 用户名 密码
/list
/status
/stop
```

示例：

```text
/add alice MyPassw0rd
```

规则：

- 用户名：2–32 位字母、数字、点、下划线、短横线，以字母或数字开头。
- 密码：至少 8 位，不能含空格。
- 客户端 **没有注册接口**；删除账户、修改密码等也只能在服务端控制台完成。
- `/stop` 会关闭监听、断开现有客户端并退出服务端。

更多命令：

```text
/del 用户名
/passwd 用户名 新密码
/kick 用户名
/help
```

### 2.3 启动桌面客户端

双击 `dist/SaysProxyV3-Client/start-client.bat`，或：

```bat
java -jar dist\SaysProxyV3-Client\SaysProxyV3-Client.jar
```

填写：

- 服务器地址、端口（默认 `8388`）
- 服务器连接密码
- 账户名称、账户密码
- 本地代理端口（默认 `1080`）

点击“连接”。登录成功后，客户端只监听本机回环地址：

```text
SOCKS5 / HTTP: 127.0.0.1:1080
```

客户端在点击连接时会把以下信息自动保存到本机：

```text
Windows: %APPDATA%\SaysProxyV3\client.properties
Linux/macOS: ~/.saysproxyv3/client.properties
```

保存内容：服务器地址、服务器端口、服务器连接密码、账户名称、本地代理端口。
**账户密码不保存**。该文件包含明文的服务器连接密码，请保护你的系统账户和该文件；
下一次启动客户端会自动填入这些信息，只需重新输入账户密码。删除该文件即可清除保存的信息。

桌面客户端关闭窗口时会**隐藏到系统托盘后继续运行**，正在使用的代理不会断开。
左键双击托盘图标或右键菜单“显示主界面”可以恢复窗口；右键菜单“退出 SaysProxyV3”
才会断开代理并退出程序。如果系统不支持托盘，关闭窗口仍会退出。
窗口和托盘使用 Java/系统默认图标，不使用项目自定义图标。

浏览器或其他软件需要设置代理：

```text
类型: SOCKS5 或 HTTP
主机: 127.0.0.1
端口: 1080
```

HTTPS 网站通过 HTTP `CONNECT` 支持；也可以直接使用 SOCKS5。

Windows 系统代理设置也可以填写 `127.0.0.1:1080`（Windows 使用 HTTP 代理），
但只对支持系统代理的程序有效。Firefox、Chrome、Telegram 等支持独立代理设置的程序
可以直接配置 SOCKS5。

> 桌面端是“本地代理”形态，不是虚拟网卡/TUN 全局 VPN。不支持代理设置的程序不会经过服务器。

本版本只转发 **TCP**：支持 SOCKS5 `CONNECT` 和 HTTP `CONNECT`/普通 HTTP。
不支持 SOCKS5 UDP ASSOCIATE、ICMP、UDP 游戏联机或全局游戏加速。

### 2.4 安装 Android APK

把 `dist/SaysProxyV3-Android/SaysProxyV3.apk` 传到手机安装，允许“安装未知来源应用”。

填写同样的服务器地址、端口、服务器连接密码、账户名、账户密码，点击连接。
连接后手机本机 `127.0.0.1:1080` 提供 SOCKS5 / HTTP 代理。

Android 也会自动保存服务器地址、端口、服务器连接密码和账户名（SharedPreferences，
`MODE_PRIVATE`），但不会保存账户密码。

#### Android 版的重要限制

**当前 Android APK 是本地代理模式，不是接管全部 App 流量的 TUN VPN。**

原因是：Android 上真正的全局 VPN 需要实现 `VpnService`，并在其 TUN 接口和
本地 SOCKS 代理之间加入 tun2socks 原生库/用户态 TCP/IP 栈。本版本没有打包这部分原生组件，
所以 Android 端采用与应用内一致的本机代理：

1. 支持独立代理设置的 App，可填写 `127.0.0.1:1080`。
2. 部分系统可在 Wi-Fi 高级设置中填写 HTTP 代理 `127.0.0.1:1080`；不同厂商 ROM
   可能限制回环地址，且只对遵循系统代理的 App 生效。
3. 没有配置代理的 App **不会**经过加密隧道。

如果你要做“全设备流量 VPN”，需要下一步增加：
`VpnService` + tun2socks（含 DNS/UDP 处理）和相应的原生库。这部分不能仅靠 Java 标准库
可靠实现，因此本次没有用假的“VPN 开关”冒充全局模式。

---

## 3. 服务端配置文件

### config/server.properties

```properties
# 监听所有网卡；只允许本机可改为 127.0.0.1
bindAddress=0.0.0.0
port=8388
# 默认禁止访问服务器内网/回环/链路本地/保留地址
allowPrivateTargets=false
targetConnectTimeoutSeconds=15
# 客户端 30 秒一次心跳；无帧超过 180 秒断开
idleTimeoutSeconds=180
```

如果把 `allowPrivateTargets` 改成 `true`，客户端就可以让服务器连接
服务器所在内网或本机服务（SSRF 风险）。除非你明确需要，否则不要开启。

### config/max-connections.properties

```properties
maxSessionsPerUser=3       # 同一账户最多同时登录几个客户端
maxIpsPerUser=2            # 同一账户最多同时使用几个客户端 IP
maxSessionsPerIp=10        # 同一客户端 IP 最多同时登录几个客户端
maxSessionsTotal=200       # 全服务器最多同时登录数
maxStreamsPerSession=64    # 单个客户端会话内最多并发多少条 TCP 流
maxLoginFailures=5         # 同一 IP 连续认证失败多少次后临时封禁
loginBanMinutes=10         # 封禁分钟数
targetReadTimeoutSeconds=0 # 0 表示不设置目标读超时
```

说明：

- “最大连接 IP”通过 `maxIpsPerUser` 和 `maxSessionsPerIp` 两个维度同时限制。
- 服务端还有全局 1024 条并发转发流的内部硬上限，避免资源耗尽。
- 修改配置后重启服务端生效。

### 连接密码

`config/connection-password.txt` 只保存一行密码。首次启动自动生成 24 位
URL-safe 随机字符（144 bit 熵）。文件是明文，因为管理员需要读取它。
在共享主机上请限制该文件的读取权限。

> 不要把它改成短密码。自定义密码至少 12 个字符；客户端和服务器必须一致。

### 账户数据库

`config/users.properties` 由服务端维护：

```text
用户名=pbkdf2-sha256$迭代次数$saltBase64$hashBase64$创建时间
```

密码使用 PBKDF2-HMAC-SHA256（默认 120,000 次迭代，16 字节随机盐）。
文件中没有明文密码。服务端运行时不要手工修改该文件。

---

## 4. 日志

`logs/access-日期.log` 记录使用情况和客户端 IP：

```text
时间	LOGIN	user=alice	ip=203.0.113.5	session=s-1
时间	CONNECT	user=alice	ip=203.0.113.5	target=example.com:443	session=s-1 stream=1 result=OK
时间	CLOSE	user=alice	ip=203.0.113.5	target=example.com:443	session=s-1 stream=1 upBytes=123 downBytes=456 durationMs=1020 reason=正常结束
时间	LOGOUT	user=alice	ip=203.0.113.5	session=s-1 reason=空闲超时 durationMs=...
```

`logs/audit-日期.log` 记录服务启动/关闭、`/add` 等管理员操作和认证失败。
密码、哈希、盐都不会写入日志。

日志按天滚动，UTF-8 编码，以 TAB 分隔，方便用脚本分析。

---

## 5. 安全设计

- 传输：TCP 上的自定义加密帧，AES-256-GCM，每条方向独立密钥；
  每帧使用“随机会话前缀 + 单调序号”作为 GCM nonce。
- 密钥交换：NIST P-256 ECDH 临时密钥，具备前向保密。
- 身份认证：服务器连接密码经 PBKDF2-HMAC-SHA256 后参与会话密钥派生；
  服务器在客户端发送账户凭据之前，用 HMAC 证明自己知道该密码。
  密码错误或数据被篡改时 GCM 校验失败并断开。
- 账户密码：服务端 PBKDF2-HMAC-SHA256 + 随机盐；比较使用常量时间。
- 登录限速：同一 IP 连续失败触发临时封禁；账户/并发限制在锁内原子检查。
- 目标限制：默认拒绝回环、私网、链路本地、多播、CGNAT、保留/文档网段，
  并先解析成具体 IP 再连接，降低 SSRF 和 DNS 重绑定风险。
- 本地代理只监听 `127.0.0.1`，不向局域网暴露。
- 服务端和客户端都不在日志里输出密码。
- 桌面客户端会把服务器连接密码保存到本机 `client.properties`（明文，位于用户配置目录），
  不会保存账户密码；请限制该文件的访问权限，不要把自己的 Windows/macOS/Linux 账户借给他人。

注意：

- 这是自行设计的加密协议，**没有经过独立安全审计**。不要把国家安全级别
  的机密流量交给它，也不要把它当 TLS 证书体系使用。
- 服务端连接密码是预共享秘密。请用首次启动生成的强密码，并通过安全渠道交给用户。
- 服务端是公开代理入口，请配合防火墙、最小端口暴露、监控和合法使用政策。

---

## 6. 从源码构建

### 6.1 构建两个 JAR

要求：JDK 8+，`javac`、`jar`、`java` 在 `PATH` 中。

```bat
build.bat
```

或：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

产物会写入 `dist/SaysProxyV3-Server/` 和 `dist/SaysProxyV3-Client/`。

### 6.2 构建 Android APK

要求：

- JDK 8（用于 `javac -bootclasspath android.jar`）和 JDK 17（用于 `d8` / `apksigner`）
- Android SDK：`platforms;android-35` 和 `build-tools;35.0.0`
- 设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`，或把 SDK 放在项目下的 `.android-sdk`
- 脚本是 PowerShell 5.1 兼容的，不含第三方依赖

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-android.ps1
```

脚本会：

1. 用 `aapt2` 编译/链接资源；
2. 用 Android `android.jar` 作为 bootclasspath 编译公共加密代码和 Android 界面；
3. 用 `d8` 生成 `classes.dex`；
4. 加入 APK、`zipalign`、用调试密钥签名；
5. 把 APK 输出到 `dist/SaysProxyV3-Android/SaysProxyV3.apk`。

签名密钥位于 `android/keystore/saysproxy-debug.keystore`，密码均为
`saysproxyv3`，别名 `saysproxy`（如果该文件不存在，构建脚本会重新生成）。
**这是调试/侧载用密钥，不是发布密钥**。正式分发前请生成自己的密钥并妥善保管；
公开调试密钥意味着别人也可以签发同包名 APK，因此不要在公开渠道分发这个密钥。

---

## 7. 测试

已验证：

- 服务端/客户端在 JDK 8 下编译运行；
- 自动生成配置、用户库、密码文件；
- 服务端控制台 `/add`、`/list`、`/status`、`/stop`；
- 正确服务器密码 + 正确账户密码登录；
- 错误服务器密码和错误账户密码被拒绝；
- HTTP 代理（普通 HTTP 与 HTTPS CONNECT 所需的 CONNECT 路径）；
- SOCKS5 CONNECT；
- 512 KB 大响应完整性、多帧传输；
- 服务端访问日志包含用户名、客户端 IP、目标地址和字节统计；
- 日志中不含密码；
- 桌面客户端配置保存/读取（含特殊字符服务器密码），账户密码不写入文件；
- APK 通过 `apksigner verify` 的 v2/v3 签名校验（minSdk 26，targetSdk 33）。

运行集成测试：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-tests.ps1
```

测试会启动真实的服务端、TunnelClient、LocalProxyServer 和本地 HTTP 目标服务器，
并随机选择端口，不访问外网。测试通过后打印 `ALL TESTS PASSED`。

Android APK 已成功构建并通过签名/清单校验；本次构建环境没有 Android 真机或模拟器，
因此未做设备端 UI 和安装运行测试。

---

## 8. 常见问题

**Q: 客户端提示“服务器连接密码错误或服务器身份校验失败”？**
A: 检查 `config/connection-password.txt` 与客户端输入是否完全一致，不要带换行或空格。

**Q: 客户端提示“账户名或密码错误”？**
A: 账户只能在服务端 `/add` 创建；检查大小写和密码。连续失败会触发 IP 临时封禁。

**Q: 连上了但浏览器打不开网页？**
A: 浏览器/系统没有设置代理，或设置的是错误的类型/端口。桌面端使用
`127.0.0.1:1080` 的 SOCKS5 或 HTTP；Android 见第 2.4 节的限制。

**Q: 服务器在云上，客户端连不上？**
A: 检查云安全组/防火墙是否放行 TCP 监听端口，检查 `bindAddress`，以及服务端是否报端口占用。

**Q: 为什么不能代理服务器内网地址？**
A: 默认 `allowPrivateTargets=false`，防止把服务器当作进入内网的跳板。
确有需要时再修改，并承担相应风险。

**Q: 多个用户共用一个公网 IP，会互相影响吗？**
A: `maxIpsPerUser` 按账户统计不同 IP，`maxSessionsPerIp` 按 IP 统计会话数。
NAT 后的用户会共享客户端 IP；必要时调大 `maxSessionsPerIp`。

---

## 9. 项目结构

```text
src/common/     加密、握手、帧、隧道、多路复用、本地代理
src/server/     服务端、账户库、配置、限制、日志、控制台
src/desktop/    Swing 桌面客户端
android/        Android 源码、Manifest、资源、调试签名
test/           集成测试
scripts/        构建和测试脚本
packaging/      打包用启动脚本
dist/           已构建产物
```

---

## 10. 版本

SaysProxyV3 3.1 — 客户端自动保存服务器连接信息（账户密码不保存）。

仅用于合法授权的网络访问。使用本项目造成的任何后果由使用者自行承担。
