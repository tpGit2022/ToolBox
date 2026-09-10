# 文件加密：使用及 v1 格式规范

## 使用

在「加密」页选择加密或解密，通过系统文件选择器选择任意文件。

- 加密：选择算法、设置公开文件头长度、输入并确认密码，再点击「开始加密」。默认 AES-256-GCM、公开前 16 字节；密码至少 8 个字符，建议使用独立的长随机密码。
- 解密：输入原密码，点击「开始解密」。从文件尾自动解析算法、规则和原文件名，不依赖当前算法或文件头输入框。
- 完成后点击「另存为新文件」。加密文件名为 `原名.encrypted.原扩展名`；解密恢复尾部记录的文件名和 MIME 类型。不原地修改或删除源文件。
- 一次处理一个文件；支持取消、保存失败重试、丢弃临时结果。切页和配置变化由 Activity 级 ViewModel 保留任务，密码不存入 saved state 或磁盘。不是后台服务，进程终止不续传。

**文件头保留是明确的保密性让步，不是完整的格式兼容。** 保留区逐字节复制源文件的前 N 字节，公开可读。N 可取 0～4096，实际长度为 `min(N, 原文件长度)`。小文件可能因此全部公开；隐私优先时设为 0。只检查魔数的服务可能认出原类型，但真实解析器可能要求索引、校验和、结束标识或完整媒体数据，不能保证上传成功、预览或播放。服务修改、转码或截去尾部后将无法通过完整性校验。

文件尾的原文件名、MIME 类型、大小和加密参数同样公开，不存储密码。忘记密码无法恢复。请保留原文件直至确认解密结果。

## 算法与安全边界

| 编号 | 算法 | 加密密钥 | 每帧 IV/nonce | 每帧密文长度 |
| --- | --- | --- | --- | --- |
| 1 | AES-256-GCM | 32 字节 | 12 字节 | 明文长度 + 16 字节 tag |
| 2 | AES-128-GCM | 16 字节 | 12 字节 | 明文长度 + 16 字节 tag |
| 3 | AES-256-CBC/PKCS5Padding + HMAC-SHA256 | 32 字节 | 16 字节 | `(明文长度 / 16 + 1) * 16`，整数除法 |
| 4 | ChaCha20-Poly1305 | 32 字节 | 12 字节 | 明文长度 + 16 字节 tag |

AES 使用 Android/JCA provider；ChaCha20-Poly1305 按运行时实际能力开放，一般需 Android 9/API 28 及以上。不可用的选项禁用，不静默降级算法。支持主流对称文件加密方案，不提供 DES、3DES、RC4、ECB 或无认证 CBC；RSA/ECC 不是大文件的直接对称加密替代品。本次不引入 SM4 等额外 provider。

所有算法都额外采用**全文件 HMAC-SHA256，先认证再解密**。CBC 的 HMAC 是必需的 Encrypt-then-MAC，不是普通摘要；GCM/Poly1305 的每帧 tag 之外仍有全局 HMAC，防止帧被重排、截断、替换或文件头／尾被篡改。解密不使用 MD5 作为安全校验。

每个文件生成独立的 16 字节随机 salt。密钥派生：

```text
master = PBKDF2-HMAC-SHA256(password UTF-8, salt, 600000, 32 bytes)
encryptionKey = HMAC-SHA256(master, ASCII("ToolBox/v1/encryption"))[0:keyBytes]
authenticationKey = HMAC-SHA256(master, ASCII("ToolBox/v1/authentication"))
```

密码区分大小写，不 trim 或 Unicode 归一化；不使用默认密码、MD5 派生或固定 IV。优先使用平台 PBKDF2WithHmacSHA256；旧设备缺少该工厂时，以平台 HmacSHA256 实现等价的单输出块 PBKDF2。密钥分用途派生，不复用 AES 密钥作为 HMAC 密钥。

GCM/ChaCha 每帧 nonce 为 `salt[0:8] || uint32_be(frameIndex)`，frameIndex 从 0 开始。在每文件独立派生的密钥下不会重复；帧数最多 `2^32 - 1`。CBC 每帧生成独立的随机 16 字节 IV。每帧独立初始化 cipher，AEAD 使用 128 位 tag，不设置 AAD；帧序号和全部结构由外层 HMAC 绑定。

这是百宝匣的自定义容器，不是第三方审计过的通用加密文件标准。为兼容已有文件，v1 的内部密钥派生标签仍固定使用 `ToolBox/v1`。安全依赖密码强度、平台 provider、设备安全以及原文件头的公开策略，不保证隐藏类型、大小或文件名。

## 文件布局

整数均为大端序。没有 Java 序列化、JSON 或 `writeUTF`。

```text
[公开的源文件头副本 P]
[帧 0][帧 1]...[帧 n-1]
[元数据 M]
[全文件 HMAC-SHA256 T：32 字节]
[元数据长度 L：4 字节]
[定位魔数：8 字节 ASCII "TBXCRYPT"]
```

**完整原文件（含文件头）都会加密进帧**；P 是额外的公开副本，解密时跳过 P，不会将文件头重复拼入还原结果。空文件没有帧，但仍有元数据和 HMAC。

每帧：

```text
[明文长度：4 字节]
[密文长度：4 字节]
[IV/nonce：算法规定长度]
[密文，包含 AEAD tag（如适用）]
```

明文块固定为 1048576 字节，最后一块可较短，禁止零长度帧。使用 Long 计数，内存开销仅与块大小有关，不一次性加载整个文件或依赖 InputStream.available()/URI 文件大小。

M 的字段按以下顺序拼接，偏移相对 M 起始位置：

| 偏移 | 长度 | 字段 | v1 取值／含义 |
| --- | --- | --- | --- |
| 0 | 4 | formatVersion | 1 |
| 4 | 4 | algorithmId | 上表 1～4 |
| 8 | 4 | flags | 1：公开头为副本，完整源文件加密；不接受其他位 |
| 12 | 4 | kdfId | 1：PBKDF2-HMAC-SHA256 + 上述用途派生 |
| 16 | 4 | iterations | 600000 |
| 20 | 4 | chunkBytes | 1048576 |
| 24 | 4 | preservedHeaderBytes | P 的实际字节数，0～4096 且不大于原文件大小 |
| 28 | 8 | originalSize | 原文件字节数，非负 |
| 36 | 8 | frameCount | 帧数，0～4294967295；必须与原文件大小吻合 |
| 44 | 16 | salt | 安全随机数 |
| 60 | 4 | originalNameLength | UTF-8 字节长度，1～1024 |
| 64 | 可变 | originalName | 原文件显示名 |
| 可变 | 4 | mimeTypeLength | UTF-8 字节长度，1～255 |
| 可变 | 可变 | mimeType | 原始 MIME 类型 |

M 总长度最大 4096 字节。T 的计算顺序为：

```text
T = HMAC-SHA256(authenticationKey, P || 全部帧 || M || L || ASCII("TBXCRYPT"))
```

T 自身不参与 HMAC；L 和魔数参与。解析时先从末尾 12 字节定位 M，严格校验版本、算法、固定规则、字段范围、UTF-8、帧数与文件几何长度，再派生密钥并流式验证 T，最后开始解密帧。元数据在 T 校验前均不可信，不能据此创建外部文件。不得使用尾部任意 iterations 或 chunkBytes 分配资源，以避免恶意 KDF 耗时和内存耗尽。

## 兼容与后续扩展

- 保持定位魔数和末尾 12 字节结构稳定。旧版本遇到未知 version、algorithmId、flags、kdfId 或不支持的规则应明确失败，不能猜测、降级、忽略未知字段或继续导出。
- 调整 KDF 参数、帧格式、密钥派生标签或规则时新增格式版本，保留旧版本读路径，补充历史文件回归样本，不直接修改 v1 语义。
- 新增算法可分配新 algorithmId，但必须明确密钥、nonce、tag、帧长度与 provider 兼容性；不得复用已有编号。
- **不兼容 TheBook 的旧容器**。参考了其大文件分块处理思路，但没有复制固定口令、固定 IV 或 MD5 派生。迁移时先用 TheBook 解密，再由百宝匣重新加密。

## I/O、取消与临时文件

只通过系统文件选择器读取源文件、创建独立输出，不申请全盘存储权限。加密直接流式读取 URI；解密先复制到应用私有 `cacheDir/file-crypto`，避免不可 seek 的云端 URI 和校验后重读源 URI 的竞态。解密核心的 File 参数必须指向任务独占的私有快照，不能传可被第三方并发修改的共享路径。

解密完成前不写入用户选择的外部目标；发生认证失败时不会输出任何明文。所有结果先放私有缓存，成功后由用户另存为。保存取消保留待保存结果，保存失败尝试删除刚创建的部分目标；provider 不支持删除时明确提醒手工处理。解密最多同时保存一份密文和一份明文，需约两倍文件大小的缓存空间。若系统清理缓存导致结果丢失，需要重新处理。

取消在分块边界检查，原生 PBKDF2 调用期间不能立即中断，必须等待该次派生返回。失败和取消清理任务目录；丢弃、成功保存、ViewModel 销毁清理临时结果，下次 Activity 初始化清理异常退出遗留缓存。缓存不承诺闪存安全擦除；内存中的 CharArray 和主要密钥缓冲区尽力清零，但 Compose String、JCA 内部复制及系统输入法不在完全控制范围内。

## 验证

新增 `FileCryptoTest`：四算法跨块往返、文件头保留／禁用、空和微小文件、CBC 对齐边界、整块长度、随机化、错误密码、不同区域篡改、截断／追加、未知版本／算法／参数、畸形长度、短读／零读、取消、尾部布局、PBKDF2 已知答案及 Unicode 一致性。

新增 `FileCryptoInstrumentedTest`：Android provider 四算法跨块往返、Unicode PBKDF2 兼容。建议覆盖 API 24/25（KDF 回退）、API 28（ChaCha20）、当前目标设备，并进行 JPEG/PNG/MP3/PDF/ZIP、大于 4 GiB 文件、云端文档、空间不足、取消、旋转、切页、保存失败及强制退出场景验证。

本项目禁止在 WSL Codex 中执行构建或测试。请在 Windows PowerShell（pwsh）中进入项目目录，启动 Windows 环境下的 Codex，再执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

最后一条需要连接设备／模拟器。当前 WSL 实施阶段仅进行源码审查和非构建静态检查，不代表以上测试已通过。
