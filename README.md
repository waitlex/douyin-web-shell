# 抖音网页版 · Android 桌面模式壳

把 `www.douyin.com` 当电脑站用，不安装抖音 App。一个 Activity、一个 WebView、一层原生控制条、一份注入脚本，APK 约 180KB。零第三方依赖，离线可构建。

> **免责声明**：本项目为独立的非官方技术实践，与抖音、字节跳动及其关联公司无隶属、授权或背书关系。「抖音」等名称仅用于描述所访问的公开网页对象，商标归原权利人所有。详见[合规与免责声明](#八合规与免责声明)。

## 一、架构

```
MainActivity (Java，零第三方依赖)
  ├─ 桌面 UA（沿用内核真实 Chrome 版本，避免版本号虚高）
  ├─ 桌面视口（沉浸 900 / 网页 1280 CSS px）
  ├─ 系统栏：底部让出手势条，顶部 WebView 从 y=0 起
  ├─ 拦截非 http(s) scheme（snssdk1128:// aweme:// intent:// bytedance://）
  ├─ ShellLog → Download/DouyinShell/douyin-shell.txt
  ├─ 开屏页（最少 2 秒）+ 冷启动黑幕
  ├─ FeedChrome / LoginGate：原生控制层
  └─ 注入 assets/douyin.user.js
        ├─ 环境伪装  navigator.platform / maxTouchPoints / ontouchstart / userAgentData
        ├─ 视口校准  viewport meta 重写 + head 变更后重校正
        ├─ 上下翻页  点页面自身的 data-e2e="video-switch-*-arrow"
        ├─ 双击点赞  判手势 → 原生合成触摸点右侧的心
        ├─ scheme 链接拦截 + window.open 收敛到同一窗口
        ├─ 遮挡层清理  登录弹窗 / 新手引导 / 弹幕 / 「下载 App」浮层
        ├─ video 元素 playsinline / preload 补齐
        ├─ 推荐流入口  /?recommend=1&from_nav=1
        ├─ 精简模式  按几何特征隐藏桌面导航与 header
        └─ 播放设置  音量 / 进度 / 倍速 / 清晰度 / 弹幕 / 合集
```

界面四层，各管各的：

```
FrameLayout
 ├─ LinearLayout（纵向）
 │   ├─ WebView   页面本身（播放器、评论、登录浮层都在这里），weight=1
 │   └─ Dock      FeedChrome 底部条：进度条 + 互动行 + 工具行，wrap_content
 ├─ Overlay       FeedChrome 浮层：只有顶栏，clickable=false 全透传
 ├─ LoginGate     未登录时的全屏卡片
 └─ Splash        开屏页（最少 2 秒），最后加入压在最上层
```

Dock 在纵向布局里真实占位，WebView 高度 = 屏幕 − Dock，按钮条不压画面，也不遮挡作者信息、话题标签和右侧按钮列。

控制层落在原生而非注入 DOM：注入的 DOM 会被抖音自身样式和 SSR 冲掉，原生层不受影响；原生触摸不被页面监听器截走，滑动合成与按钮点击互不干扰；零依赖离线构建用不了 AndroidX / Material / Compose，图标手写成 vector，APK 保持在约 180KB。

## 二、核心机制

### 翻页

注入层识别上滑 / 下滑后，点击页面自身的 `[data-e2e="video-switch-next-arrow"]` / `-prev-arrow`，一次滑动翻一页，620ms 冷却。网页切换组件校验 `event.isTrusted`，合成的 `WheelEvent`、手指拖动、键盘方向键均无效；页面自己的按钮走组件 `onClick`，可直接调用。swiper 同时挂载三组 prev/next 按钮（上一屏、当前屏、下一屏），用 `getBoundingClientRect()` 过滤掉不在视口内的组，否则等于点了屏幕外的视频。

### 原生控制条

顶栏（浮层，全透传）：左侧登录态圆点 + 文字，右侧胶囊 `推荐`（回推荐流）、`完整` / `精简`（切换周边显隐）、`网页` / `沉浸`（切换视口宽度）。4 秒无操作淡出；全屏时多一个 `退出全屏`，且全屏状态下不自动淡出。

底部 Dock（占位，不浮在画面上，底色 `#0B0B0F`，常驻不淡出），从上到下三块：

- **进度条（26dp）**：2dp 轨道画在 View 顶边；页面每 250ms 回报真值，原生按 `playbackRate / duration` 逐帧外推，1x / 1.5x / 2x 不跳格；拖动时浮出 `01:24 / 04:03`，松手消失。页面自带的 xgplayer 控件条整排 `display:none`，这一条是唯一进度入口。
- **互动行（42dp）**：作者头像 + `关注` 胶囊，右侧 `♥ 点赞` / `💬 评论` / `★ 收藏` / `↗ 转发` / `⛶ 全屏`。
- **工具行（32dp）**：`🔊 静音开关` + 音量条（吃满剩余宽度）+ `弹幕` / 清晰度 / 倍速 / `合集` 四枚等宽胶囊。

各按钮的实现：

| 按钮 / 手势 | 实现 |
| --- | --- |
| 上滑 / 下滑 | 判手势 → 点页面 `video-switch-*-arrow`，620ms 冷却 |
| 单击画面 | 页面自身 click，播放 / 暂停，不另设按钮 |
| 双击画面 | 两次抬手 < 300ms、位移 < 24px → `doubleTapLike()`，走原生合成触摸点 `video-player-digg` |
| 点赞 / 收藏 / 转发 / 关注 / 合集 | 页面报坐标（`__DY.point(...)`），原生 `dispatchTouchEvent` 合成真实触摸 |
| 评论 | 同上，点 `feed-comment-icon`；面板由页面自己开合，再套一层抽屉 |
| 作者头像 | Dock 左侧 30dp 圆图，从页面 `span + background-image` 里捞 URL 后直连 CDN |
| 音量 | 改 `video.volume` / `muted`，元素级，不动系统媒体音量；启动默认静音 |
| 全屏 | Dock `GONE` + `FLAG_FULLSCREEN` + immersive sticky，WebView 长高后 `refreshLayout()` |
| 进度条 | 拖动 → `__DY.seekToFraction()`；进度由页面每 250ms 推给原生，原生按倍速外推 |
| 推荐 | 导航到 `/?recommend=1&from_nav=1`；已在推荐流则提示 |
| 完整 / 精简 | 恢复 / 隐藏左侧导航与 header（`__DY.setFocus`），纯 CSS，可逆 |
| 弹幕 / 清晰度 / 倍速 | `__DY.toggleDanmu()` / `cycleQuality()` / `cycleRate()`，状态每 250ms 随进度回传 |
| 看相关 / `…` | 按文案匹配整格 `display:none`，不提供对应功能 |

点赞、评论等按钮挂在 xgplayer 上，只认 `isTrusted` 的真实输入，`el.click()` 无效。做法是页面算出按钮中心的 CSS 坐标，`MainActivity.performTap()` 用 `MotionEvent` 合成 `ACTION_DOWN` + `ACTION_UP`（间隔 70ms），`scale = WebView 实宽 / innerWidth` 把 CSS 像素换算成 View 坐标。翻页箭头是纯 `onClick`，可以脚本直接点。

点赞 / 收藏的状态从页面按钮的 `data-e2e-state` 读取（`video-player-is-digged` / `video-player-is-collected`），每 250ms 随进度回报，签名变了才过桥，取消点赞、收藏被删、翻页都能同步。「关注」没有权威状态（那颗 `+` 是 hover 才渲染的徽标，高度恒为 0），只做交互反馈：点一下变「已关注」，翻到下一个视频重置。

### 评论区

网页版评论是右侧抽屉，展开后把视频压成半宽。这里把评论壳（`LookModalFrameFast` 外层）钉成满宽底部抽屉：视频区压到 `42vh`、抽屉占 `58vh`（由 `SHEET_VH` 一处控制），视频完整留在上半屏，两者不重叠。抽屉 reparent 到 `[data-e2e="slideList"]`（`position:absolute`、`y=0`，不吃 swiper 位移），拆壳时归位；`syncActiveSlide()` 每帧 + 250ms 轮询跟随 active 条，避免翻页套壳瞬间标记打在正滑出屏的旧条上。

开合动画由抽屉自身的 `transform: translateY(100%) ↔ 0` 驱动（时长 `SHEET_MS`），开合期间取消 item / slideList / `.dy-pbox` 的高度过渡让高度瞬时到位；`pinVideo()` 从点评论那一帧起每帧读 active video 的视觉 y，在它自己身上叠反向 `translateY`，抵消 swiper 位移造成的画面抽动，拆壳时归零。三条关闭路径（抽屉右上角 ×、Dock 按钮、下半屏右滑 `dx > 60dp 且 dx > |dy| × 1.8`）统一走 `beginClose()`：先撤高度类让视频区长回、抽屉顺着下沿滑出，滑出屏幕后再关页面并 `dropSheet()` 摘壳。

点开的一百毫秒内页面会先把视频压窄，壳还没套上，会露出下一条视频。`comments()` 当场把当前条以外的所有 `feed-item` 设成 `visibility:hidden`，`dy-sheet-geo` 在场期间用 CSS 配 `.dy-active-slide` 标记只显示当前条，覆盖整个风险窗口。

评论按手机版排版：头像 84px、正文 34px / 行高 1.52、昵称 27px / 65% 白、时间 25px、操作图标 32px、「展开 N 条回复」28px 蓝色、输入框 92px 外框 + 68px 胶囊；时间 / 地区在左、回复·分享·点赞在右并成一行；顶部 AI 图标 / TA 的作品 / 相关推荐和「大家都在搜」摘掉。右上角 × 所在的 46px tab 行保持原样，压高度或改定位会把 × 顶出抽屉。

评论列表是 `[data-e2e="comment-list"]`（`overflow-y:scroll`，实测 `scrollHeight 2293 / clientHeight 692`）。`touchstart` 记录起点是否落在 `.dy-sheet` 内，`touchend` 用 DOM 链 + 起点矩形（`pointInSheet`）判定手势归属，命中抽屉就放行给浏览器原生滚动，不触发翻页。

### 画面铺满与视口

桌面版播放器固定 16:9，竖屏视口高度远超画面高度，上下留白。两件事分开做：

- **视口宽度**（顶栏 `网页 / 沉浸`）：只改 `designWidth`，不动 DOM。沉浸 900（412dp 屏上缩放 0.458，画面放大约 1.42 倍），网页 1280（完整桌面版，字小）。
- **播放器高度**：把视频所在容器改成 `100vh`、`object-fit:contain`。竖视频按宽度铺满后占满屏幕高度，横视频保持 16:9 居中，不裁切。只处理宽过半屏、高过两成屏的 video，改完立刻量高度，没变高就撤回再试上一层，防止父级 `overflow` 吃掉改动。不做 `transform` 裁切。

视频右侧曾有一条 60px 深色带，来自 `[data-e2e="slideList"]` 的 `padding-right:60px`（给右侧按钮列留位），按钮全搬进 Dock 后 `padding-right:0 !important` 归零。顶部 56px 是三层叠加：贴顶 fixed 外壳（高 56、底色 `#161823`）、header 父级盒子、更外层的 `padding-top:56px`。`flattenHeader()` 从 header 父级往上最多找 4 层，命中「贴顶 + 高 24~96 + 宽过半 + fixed」的外壳一并隐藏（关闭精简模式可还原）。

页面右侧整列按钮头像、关注、赞、评论、收藏、转发在 Dock 里都有对应，用 `opacity:0` 隐藏：`display:none` 会让整列塌陷、坐标全变，`visibility:hidden` 退出命中测试、合成触摸会穿到 `<video>` 变成暂停；`opacity:0` 同时满足不可见、保留布局、接受指针事件。真实的旧按钮位置仍会被手指点到，注入层在 `document` 捕获阶段只拦 `isTrusted=true` 且目标在右侧竖列或切视频箭头上的事件；原生 Dock 点击先写 `window.__DY_NATIVE_TAP__` 标记放行自己人。

### 加载与登录

冷启动直接导航到 `https://www.douyin.com/?recommend=1&from_nav=1`。两个参数都必需：只有 `recommend=1` 时服务端 302 回 `/jingxuan` 网格页。启动顺序是开屏页（深色图标 + 「抖音 · 网页版」+ 加载线，最少 2 秒）、纯黑 `cover`（挡住先闪网格页）、WebView 三层；`revealPage()` 在「2 秒到了且内容已露出」时收黑幕和开屏页，另有推荐流回报 / 首屏 8 秒 / 启动 18 秒三道兜底。不用 `setVisibility(INVISIBLE)` 挡 WebView——不可见的 WebView 会暂停渲染，页面首屏反而出不来。

应用本身没有账号体系，不采集手机号 / 验证码 / 密码。点「在页面内登录」弹出抖音自己的登录框，扫码或短信都在官方页面完成；登录成功后原生轮询 `CookieManager` 判断，命中即提示「登录成功」。判定依据是 `sessionid` / `sessionid_ss` / `sid_tt` / `sid_guard` / `uid_tt` 任一存在，再用页面的 `__DY.loginState()` 兜底；DOM 判断以 `data-e2e="user-avatar"` 为准，拿不准返回 `unknown`。登录入口常驻左上角状态条，`先随便看看` 始终可用。

未登录时抖音会在沉浸页叠登录弹窗、新手引导、弹幕层，注入层按稳定 id 和几何特征找到并清理；用户点「去登录」后暂停清理 240 秒，避免登录框刚出来就被关掉。有触摸时给遮挡层 12 秒豁免。

### 缓存与存储

`MediaCache.java` 接管 `shouldInterceptRequest`，缓存目录 `Android/data/com.example.douyinweb/cache/media-cache/`，视频和资源共用一个上限 `min(192MB, 可用空间/16)`、按最久未用淘汰。抖音用 fetch + MSE 按约 200KB 分片拉流，缓存单位是「URL + Range」；回源时必须原样吐回 CORS 响应头，否则播放器判跨域失败、1 秒内多次从头重下；只有整段读完才落盘。静态资源（JS / CSS / 字体 / 图片）缓存 3 天，主文档 HTML 和 `/aweme/`、`/api/` 接口不缓存；chunked 传输、长度未知的资源流式读到 6MB 上限。

存储分三层治理：

| 位置 | 内容 | 上限 | 回收 |
| --- | --- | --- | --- |
| `cache/media-cache/`（外部） | 视频分片 + 静态资源 | `min(192MB, 可用/16)`，<1GB 时降到 32MB | 写入后 20s 触发 LRU；`onTrimMemory` 切后台再收 |
| `cache/WebView/.../Cache_Data` | WebView HTTP 响应体 | 120MB | 启动时（WebView 创建前）整块删 |
| `cache/WebView/.../Code Cache` | V8 编译产物 | 40MB | 同上 |
| `app_webview/` | Cookie / IndexedDB / LocalStorage | 不设限 | 永不清理，删了要重新登录 |

缓存只放 `getCacheDir()` / `getExternalCacheDir()`，系统设置里的『清除缓存』和低存储驱逐才会真正释放；升级首启把旧的 `files/media-cache` 整棵删掉。WebView 目录只在启动窗口删，运行时删会踩到 Chromium 的索引和文件句柄。写入前统一走 `ensureDir()`，防止系统低存储连目录一起删掉。实测日常稳定在 80MB 上下，天花板约 350MB。

## 三、构建

需要 Android SDK（build-tools 36.0.0 / android-36，通过 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 指定）、JDK 17+、python3。

```bash
./build.sh                       # 依次调 aapt2 → javac → d8 → zipalign → apksigner
adb install -r douyin-web-shell.apk
```

不需要 Gradle、Android Studio 或网络。工程可用 Android Studio 打开（AGP 8.10.1 + compileSdk 36），但没有第三方依赖，该路径可选。`build.sh` 只打印安装命令，不自动安装。

## 四、日志与调试

启动后在手机「下载」目录生成 `Download/DouyinShell/douyin-shell.txt`。Android 10+ 走 MediaStore 不需要权限，8/9 直接写公共目录并申请 `WRITE_EXTERNAL_STORAGE`，失败则退到应用私有目录。`ENABLE_FILE_LOG` 是调试设施，代码无依赖，改成 `false` 可关掉文件日志。

| 前缀 | 内容 |
| --- | --- |
| `TITLE` / `APP` / `LOG` | 分节标题 / 版本与环境与生命周期 / 日志位置与权限结果 |
| `UA` / `REQ` / `PAGE` | UA 改写、请求头采样、加载完成与状态码 |
| `JS` / `CONSOLE/E` / `CONSOLE/W` | 注入脚本回传、页面 error / warn（每页封顶 150 条） |
| `SCHEME` / `VIDEO` / `FILE` / `DOWNLOAD` / `PERM` / `WEBVIEW` | scheme 拦截、全屏、文件选择、下载、权限、渲染进程崩溃 |
| `UI` / `LOGIN` / `MODE` | 控制条点按、登录判定、显示模式 |

日志只留尾部 256KB（环形缓冲），跨会话累积，延迟 1.5s 批量刷盘，`onPause` / `onDestroy` / 渲染进程崩溃时立即落盘。

```bash
adb logcat -s DyShell
adb pull /sdcard/DouyinShell/douyin-shell.txt
adb shell am start -n com.example.douyinweb/.MainActivity   # chrome://inspect 连 WebView
```

控制台可用的调试入口：`__DY.state()` / `__DY.log()` / `__DY.next()` / `__DY.prev()` / `__DY.comments()` / `__DY.loginState()` / `__DY.setMode('immersive'|'web')` / `__DY.setVolume(0.6)` / `__DY.toggleMute()` / `__DY.refreshLayout()` / `__DY.config`。

## 五、调参

改 `app/src/main/assets/douyin.user.js` 顶部的 `CFG`，再 `./build.sh`：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `designWidth` | 900 / 1280 | 桌面布局宽度，由 `WIDTH_IMMERSIVE` / `WIDTH_WEB` 决定，顶栏可实时切换 |
| `viewportMode` | `meta` | `meta` / `meta-scale` / `css` 三档视口实现 |
| `spoofTouch` | `true` | 关掉保留原生触摸，用于隔离伪装引起的问题 |
| `wheelCooldown` / `wheelInvert` | 620 / `false` | 冷却毫秒；上滑翻反了就置 `true` |
| `swipeMinPx` / `swipeMinSpeed` | 34 / 0.25 | 滑动判定：距离阈值与速度阈值（px/ms） |
| `synthDoubleTap` | `true` | 双击点赞由注入层判定后交给原生 |
| `autoFeed` / `recommendUrl` | `true` / `/?recommend=1&from_nav=1` | 冷启动进推荐流与落地页 |
| `startMuted` | `true` | 启动静音（`video.muted = true`） |
| `focusMode` | `true` | 精简模式：隐藏左侧导航 + header |
| `fillVideo` | `true` | 播放器容器拉到 `100vh` |
| `hideOwnRail` / `hideExtras` | `true` / `true` | 隐藏页面右侧按钮列 / 看相关与 `…` |
| `fullBleed` | `true` | 归零顶部 56px 与右侧 60px 留白 |
| `sheetComments` / `sheetPushVideo` | `true` / `true` | 评论底部抽屉 / 展开时视频压到 `42vh` |
| `simpleCommentTabs` / `blockGhostRail` | `true` / `true` | 评论只留一个 tab / 拦旧按钮的幽灵触摸 |

另外两个常用常量在脚本顶部：`SHEET_MS`（默认 300，抽屉动画时长，与「动画结束才关页面 / 拆壳」的定时器共用）、`SHEET_VH`（默认 58，抽屉占视口高度百分比，视频区取 `100 - SHEET_VH`；所有高度计算都引用它，不要另写死 `50vh`）。

Java 侧存储参数：`MediaCache` 的 `MAX_BYTES`（192MB）、`MIN_BYTES`（16MB）、`TIGHT_AVAIL` / `TIGHT_BYTES`（1GB / 32MB）、`MAX_UNIT_BYTES`（24MB）、`ASSET_TTL_MS`（3 天）；`WebViewCache` 的 `DATA_LIMIT`（120MB）、`CODE_LIMIT`（40MB）；`ShellLog` 的 `KEEP_LOG_COPIES`（2）。

## 六、已知限制

1. **Client Hints 未处理**。`setUserAgentString` 只改 UA，`Sec-CH-UA-Platform` 仍是 `Android`；若抖音改用该字段判定，需要本地反代改写请求头。
2. **注入不是真正的 document-start**。零依赖构建用不了 `WebViewCompat.addDocumentStartJavaScript`，页面首个脚本可能先执行；要抢在前面需引入 `androidx.webkit` 并改走 Gradle。
3. **视觉密度**。默认沉浸模式 900 设计宽度等效 0.46 倍缩放，仍不是手机原生布局。
4. **清晰度**。WebView 软解，默认 1080p 会掉帧发热，建议手动切 720p。
5. **登录态**。扫码登录可用，`onPause` 已 `flush()`，但后台被杀仍可能掉登录；判定是启发式的，抖音改版可能失效，表现为状态条误显示「未登录」，点状态条重走一遍即可。
6. **混合内容放开了**（`MIXED_CONTENT_ALWAYS_ALLOW`），用于兜住 http 视频 CDN。
7. **`ENABLE_WEBVIEW_DEBUG` 默认 `true`**，USB 连上即可用 `chrome://inspect` 读到页面数据（含登录态），长期使用改 `false` 重新出包。
8. **日志含页面 URL 和接口路径**（Cookie 只记字节数），外贴前先检查。
9. **推荐流入口依赖一条 URL**。`?recommend=1&from_nav=1` 失效会退化成「同一作者连着播」，日志里先出现 `加载完成 .../jingxuan` 再出现退回 modal 的提示，换入口地址即可。
10. **翻页与评论按钮依赖 `data-e2e` 选择器**。改名则手势 / 按钮失效，按第二节的表格重找入口。
11. **首次安装冷启动约十几秒**，二次启动 3~6 秒。
12. **系统手势返回走 `OnBackInvokedCallback`**，最后一档 `moveTaskToBack(true)`，不 `finish`；返回键经 `handleBack()` 依次处理全屏 / 登录 / 评论 / 网页历史 / 回桌面。

## 七、验证清单

以下已在小米 24129PN74G（Android 16 / 1200×2670 / WebView 143）逐条验过：

1. 冷启动先看到开屏页，2 秒后淡出；打开即为推荐流单个视频，不是四列网格。
2. 每滑一次换一个作者；顶部沉浸、右侧铺满、周边（导航 / header / 右侧按钮列 / 看相关 / 弹幕 / 引导 / 登录弹窗）全部收干净。
3. 上滑切下一个、下滑回上一个、单击暂停；双击点赞在未登录时先提示再拉起登录框。
4. 音量默认静音，音量条与喇叭开关生效；全屏后系统栏隐藏、顶栏可退出、返回键先退全屏。
5. 登录引导可弹扫码框且不被清理关掉；登录态不误报；日志文件同一安装内复用一份。
6. Dock 与播放区不重叠，进度条融合在交界处且连续无回退；工具行四枚胶囊状态同步。
7. 点赞 / 收藏状态会自己亮起并在翻页后恢复；收藏 / 转发 / 关注 / 头像正常。
8. 评论区从 Dock 上方升起、把视频顶上去，排版接近手机版；列表可上下滚动，滚到顶 / 底继续滑不翻页，视频区上下滑仍能翻页。
9. 评论开合稳定：连点开关、右滑收起、点 × 关闭都能干净收起，下半屏不留空抽屉或下一条视频；开合过程不露下一条、不抖、不闪。
10. 旧按钮位置点不动了；静态资源二次冷启动命中；缓存位于 `cache/` 下，系统『清除缓存』可释放，WebView 缓存有天花板。
11. 侧滑回桌面不杀后台：`pid` 不变，再点图标从原进度继续。

## 八、合规与免责声明

### 项目立场

项目只做「用浏览器访问公开网页」这一件事：不伪造签名、不改接口、不绕过付费、不批量抓取。不是抖音的客户端替代品，只是一个带原生控制条的 WebView 壳——登录、推荐流、互动全部走官方网页自身，账号与内容归平台所有。抖音页面若调整检测策略，改动落在 `douyin.user.js` 的适配层，不向逆向方向演化。

### 非官方声明

本项目由个人独立开发，与抖音、字节跳动（ByteDance）及其任何关联公司不存在隶属、合作、授权或背书关系。「抖音」「Douyin」等名称及页面内容仅用于说明本项目所访问的公开网页对象，相关商标、标识、界面与内容的一切权利归原权利人所有。本项目不使用上述商标作为自有品牌，也不分发任何官方应用或其组件。

### 使用责任

- 本项目仅供个人学习与技术研究使用，请遵守抖音网站的服务条款与当地法律法规。
- 使用者自行判断使用方式是否合规，作者不对由此产生的账号风险、内容风险或法律责任负责。
- 项目不包含绕过付费、批量抓取、账号批量操作或规避风控的能力，也不接受此类功能贡献。

### 许可证

本项目采用 [MIT License](LICENSE) 开源，可自由使用、复制、修改、合并、发布、分发、再许可及出售副本，条件是保留版权声明与许可声明。软件按「原样」提供，不作任何明示或暗示的担保。各源文件头部以 `SPDX-License-Identifier: MIT` 标注同一许可证。

### 第三方致谢

本项目零第三方代码依赖（见 `app/build.gradle.kts` 的 `dependencies {}`），全部原生代码与注入脚本为本项目原创。下列项目仅作技术思路引用，代码未被复制或引入本仓库，版权与许可证归各自作者所有：

| 项目 | 说明 |
| --- | --- |
| `GangJust/FreedomPlus`、`NukeApp-OSS`、`Douyin Enhancer` | Xposed 类方案 |
| `chen-zeong/dtv_mobile` | 直播接口方案 |
| `zyronon/douyin`（douyin-vue, GPL-3.0） | 仿站演示 |
| `TikTokDownloader`、`f2`、`Douyin_TikTok_Download_API` | 解析 / 下载器 |
| ReVanced / Morphe | 国际版 TikTok 补丁方案 |

构建工具链（aapt2 / javac / d8 / zipalign / apksigner）来自 Android SDK，版权归 Google 所有。
