# SMS Telegram Forwarder

[![Android CI](https://github.com/Gitawake/sms-telegram-forwarder/actions/workflows/android.yml/badge.svg)](https://github.com/Gitawake/sms-telegram-forwarder/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Gitawake/sms-telegram-forwarder)](https://github.com/Gitawake/sms-telegram-forwarder/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

短信转发到 Telegram。

原生 Android App，支持 Android 8.0 及以上版本。收到新短信后，通过 Telegram Bot API 发送发件人、短信时间和全文。采用 Material 3 界面，支持系统深色模式与 Android 12+ 动态配色。底部提供待发送、已转发和设置三个页面。包含配置、权限授权、转发开关、测试消息、长短信合并、分段发送和断网自动重试。

## 下载

在 [GitHub Releases](https://github.com/Gitawake/sms-telegram-forwarder/releases/latest) 的 Assets 中下载正式签名 APK；`SHA256SUMS` 用于文件校验。

正式版使用固定专用签名，后续正式版可以覆盖升级。本机开发调试版使用不同密钥，首次切换到正式版不能直接覆盖安装；卸载会删除本地配置、待发消息和历史记录，请先自行妥善保存需要的信息。Release APK 不是 Google Play 商店版。

## 配置

1. 在 Telegram 中联系 [@BotFather](https://t.me/BotFather)，用 `/newbot` 创建机器人并取得 Bot Token。
2. 打开机器人私聊，点击 Start 并发送一条消息。
3. 推荐直接在 App 点击“编辑配置”，填写 Bot Token 后点击“自动获取 Chat ID”，先给机器人发送 `/start` 或消息，再从列表选择自己的会话，最后保存。没有更新、Webhook 冲突或其他程序已消费更新时，界面会给出提示，也可手动填写。自动获取只进行一次短轮询，不推进更新 offset、不改 Webhook 或更新订阅。

   手动方法：在本机使用 Telegram 的 [getUpdates](https://core.telegram.org/bots/api#getupdates) 方法，读取返回的 `result[].message.chat.id`。例如在自己的浏览器打开 `https://api.telegram.org/bot<TOKEN>/getUpdates`，将 `<TOKEN>` 替换成 Token；不要将包含 Token 的 URL 发给别人。若机器人已配置 webhook，getUpdates 无法同时使用。
4. 在设置页点击“编辑配置”，填写 Token、Chat ID 并保存。配置默认只读，可取消编辑恢复保存值；发送测试消息与转发开关只使用已经保存的配置。
5. 确认 Telegram 收到测试后，开启转发并按三步引导授权。也可随时从设置页点击“开始权限引导”：接收新短信（必要）、电池优化豁免（可跳过）、厂商后台运行/自启动（手动检查）。不申请历史短信读取权限；使用系统广播即可接收新短信。
6. 在系统设置中允许后台运行及自启动，电池策略选择“不限制”。手机需能直接访问 `api.telegram.org`（按需自行配置网络）。

仅接收安装授权后新到的普通 SMS，不读取历史短信，不包含彩信、RCS 或其他 App 通知。Android 和厂商电池策略可能延迟后台任务；强行停止应用后需手动重新打开。部分系统可能限制短信权限。

## 构建

使用 OpenJDK 21、Gradle 8.11.1、Android SDK Platform 35。项目使用 AGP 8.9.2。若使用 Homebrew 的较新 Gradle，请先通过 wrapper 切换至 8.11.1，不要直接用 Gradle 9 构建此项目。

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；日常 CI 的 `sms-forwarder-debug` artifact 仅用于开发测试。正式发布流程如下。

## 发布到 Releases

仓库使用两条 GitHub Actions 流程：

- `Build Android APK`：main 分支更新和 Pull Request 运行测试、Lint、构建调试 APK，不需要签名 Secrets。
- `Publish signed APK`：推送 `vX.Y.Z` 标签后，验证标签与 `app/build.gradle` 的 `versionName` 一致，运行测试和 Release Lint，构建正式签名 APK，验证签名并计算 SHA-256，最后发布到 GitHub Releases。

发布前在仓库 Settings → Secrets and variables → Actions 配置：

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 专用签名 keystore 文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

先更新 `versionName` 和递增 `versionCode`，推送提交后再推送对应标签，例如 `v1.4.0`。不要把签名文件、密码、实际 Telegram Token 或手机数据提交到仓库。Fork 此仓库后需要配置自己的签名 Secrets；不同签名的 APK 无法相互覆盖安装。

本机正式构建可设置 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 后运行 `./gradlew :app:assembleRelease`。未提供签名配置时生成的是未签名 APK，不应用于公开分发。

## 数据与重试

- Token 使用 Android Keystore AES-GCM 加密保存，备份关闭。Token 不写入日志或后台任务参数。
- 待发短信和已转发记录保存在应用私有 SQLite 数据库。成功后消息移入已转发列表，并保留发送时间和目标会话。卸载会移除本地配置和全部记录。
- 待发送页面显示等待发送、正在发送、等待重试、失败与暂停状态；点击消息可查看全文、目标和失败原因，失败或暂停消息可重试，待发消息可移除。列表按数据变化刷新，分批加载。
- 已转发列表按发送时间排序。详情支持单条删除；长按卡片或菜单“选择消息”进入多选，“全选”涵盖全部分页，再次点击可取消全选，删除前确认。全选采用点击时的历史快照，不会删掉随后到达的记录或待发送消息。清空历史只删除本机记录，不删除 Telegram 中的消息。v1 升级会保留原有待发送内容及分段进度，旧版已发送后删除的记录无法恢复。
- 关闭转发后暂停普通短信的后续发送，再次开启后继续队列；已经发起的网络请求可能完成。测试消息不依赖转发开关。
- 网络错误、HTTP 429 和服务端错误自动退避重试。配置错误会停止该任务，修正后可在消息详情中重试。待发消息绑定入队时的 Chat ID，修改设置不会把旧队列转发给新目标。
- Telegram 未提供发送消息幂等键：若服务端已收到消息而响应丢失，重试可能重复发送。
- 短信全文可能包含验证码，开启开关即表示将其转发至所配置的 Telegram 会话。

接口依据：[Android SMS_RECEIVED](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents#SMS_RECEIVED_ACTION)、[WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)、[Telegram sendMessage](https://core.telegram.org/bots/api#sendmessage)。

后台引导会检测短信授权、电池优化豁免及系统后台限制。厂商自启动/最近任务锁定没有通用检测 API，因此仅记录用户已手动检查，不把它显示为已授权。Android 仍可能延迟后台任务；引导不承诺永久保活。

## 功耗

1.4 移除了前台两秒轮询，改用进程内变更通知和生命周期观察；后台不为刷新界面定时运行。首次发送与持续指数退避重试策略不变，优先保证可靠转发。评估结果和后续实测方法见 [POWER_REVIEW.md](POWER_REVIEW.md)。

## 许可证

本项目以 [MIT License](LICENSE) 开源。Gradle Wrapper 和所用依赖保留其各自的许可证。
