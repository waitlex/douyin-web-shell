# 贡献指南

感谢你愿意改进这个项目。开始前请先读 [README.md](README.md)，尤其是「方案」和「合规与免责声明」两节。

## 项目边界（先确认再动手）

本项目只做一件事：用 Android WebView 以桌面模式访问 `www.douyin.com` 的公开网页，并补一层原生控制条与本地适配。**以下改动不在接受范围内**：

- 伪造签名参数（`a_bogus` / `msToken` / `X-Bogus` 等）、重放或劫持接口请求；
- 绕过登录、付费、风控，批量抓取或批量账号操作；
- 把适配脚本改造成逆向工具。

页面若调整检测策略，改动应落在 `douyin.user.js` 的适配层，而不是向上述方向演化。

## 环境准备

- Android SDK（build-tools 36.0.0 / platform android-36），设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`；
- JDK 17+（提供 `javac` 与 `keytool`）、`python3`；
- 一台开启 USB 调试的 Android 10+ 设备（WebView 需能持续更新）。

无需 Gradle、Android Studio 或网络依赖，工程为零第三方依赖的离线构建。

## 构建与验证

```bash
./build.sh                       # 产出 douyin-web-shell.apk
adb install -r douyin-web-shell.apk
```

提交前至少完成：

1. `node --check app/src/main/assets/douyin.user.js` —— 注入脚本语法检查通过；
2. `./build.sh` —— 能正常出包、签名校验通过；
3. 真机按 [README「上机后按这个顺序验证」](README.md#八上机后按这个顺序验证) 走一遍，重点看翻页、评论开合与滑动、状态栏、侧滑保活。

这个项目踩过很多「静态看没问题、真机一跑就抖/闪/切错视频」的坑，所以**没有真机验证的改动请在 PR 里明确标注**。

## 改动规范

- **参数集中**：视口宽度、动画时长、缓存阈值等常量放在 `douyin.user.js` 顶部 `window.__DY_SHELL_CONFIG__` 或 Java 侧常量处，不要散落魔数；
- **两侧同步**：JS 桥的参数个数与 Java 侧方法签名必须一致，改一侧要同步另一侧，否则页面会抛错并被 `try/catch` 吞掉；
- **保留诊断**：新增的适配逻辑尽量留一行 `window.__DY_NATIVE__.log(...)`，便于在 `Download/DouyinShell/` 日志里回溯；
- **提交信息**：用简短祈使句描述改动，例如「修复评论内上滑误触发翻页」。

## 提交 PR

1. Fork 仓库并基于 `main` 建分支；
2. 一个 PR 只做一件事，说明「改了什么 / 为什么 / 如何验证」；
3. 涉及 UI 或手势的改动请附真机截图或逐帧数据；
4. 不要提交 `*.apk`、`*.keystore`、`*.apk.idsig`、`.DS_Store` 或个人路径——它们已在 `.gitignore` 中排除。

## 报告问题

用 Issue 反馈时请附上：设备型号、Android 版本、WebView 版本、复现步骤、相关日志（`Download/DouyinShell/douyin-shell.txt` 尾部）。安全相关问题不要公开 Issue，按 [SECURITY.md](SECURITY.md) 的方式私下报告。
