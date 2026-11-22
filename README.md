# 抖音网页版 · Android 桌面模式壳

把 `www.douyin.com` 当电脑站用，不安装抖音 App。一个 Activity、一个 WebView、一层原生控制条、一份注入脚本，APK 约 180KB。

> **免责声明**：本项目是独立的非官方技术实践，与抖音、字节跳动及其关联公司无任何隶属、授权或背书关系。项目内「抖音」等名称仅用于描述所访问的公开网页对象，商标归原权利人所有。使用前请阅读文末[合规与免责声明](#十一合规与免责声明)。

## 一、先说调研结论：没有可用的第三方客户端

搜下来所有号称「抖音第三方客户端」的项目，实际分四类，没有一类能用：

| 类型 | 代表项目 | 为什么不能用 |
| --- | --- | --- |
| Xposed 模块 | `GangJust/FreedomPlus`、`NukeApp-OSS`、`Douyin Enhancer` | 不是客户端，是挂在**官方 App** 上的钩子，前提是你先装抖音 |
| 只做直播 | `chen-zeong/dtv_mobile` | 只接公开的直播列表接口，没有推荐流、没有评论、没有登录态 |
| 假数据仿站 | `zyronon/douyin`（douyin-vue，GPL-3.0） | Vue 写的 UI 演示，数据是本地 mock 的，拿来当基底等于从零开始 |
| 解析/下载器 | `TikTokDownloader`、`f2`、`Douyin_TikTok_Download_API` | 单条链接解析，没有信息流，不构成「客户端」 |

国际版 TikTok 那边有 ReVanced / Morphe 的补丁包（`com.zhiliaoapp.musically`、`com.ss.android.ugc.trill`），但**不覆盖国内版 `com.ss.android.ugc.aweme`**，而且是改官方 APK，不是第三方客户端。

真正要做「第三方客户端」，绕不开私有接口 + `a_bogus` / `msToken` / `X-Bogus` 签名逆向。这条路能走通，但风控一周一改、账号随时封、并且明确踩法律红线。**所以不做，改走「网页版 + 桌面模式」这条合法且稳定的路。**

## 二、方案

```
MainActivity (Java, 零第三方依赖)
  ├─ 桌面 UA（沿用内核真实 Chrome 版本，避免版本号虚高）
  ├─ 桌面视口（viewport 宽度可控：沉浸 900 / 网页 1280）
  ├─ 系统栏留白（WindowInsets → root padding，状态栏/手势条各让一块）
  ├─ 拦截所有非 http(s) scheme（snssdk1128:// aweme:// intent:// bytedance://）
  ├─ 日志 ShellLog → Download/DouyinShell/douyin-shell.txt
  ├─ 开屏页（最少 2 秒，内容就绪后淡出）+ 冷启动黑幕（进推荐流后才淡出）
  ├─ FeedChrome / LoginGate（原生控制层，见第三节）
  └─ 注入 assets/douyin.user.js
        ├─ 1. 环境伪装  navigator.platform / maxTouchPoints / ontouchstart / userAgentData
        ├─ 2. 视口校准  viewport meta 重写 + head 变更后重校正
        ├─ 3. 上下翻页    点页面自己的 data-e2e="video-switch-*-arrow"（核心，见下）
        ├─ 4. 双击点赞    自己判双击 → 原生合成触摸去点右侧那颗心（页面不认 dblclick）
        ├─ 5. scheme 链接点击拦截 + window.open 收敛到同一窗口
        ├─ 6. 遮挡层清理  登录弹窗 / 新手引导 / 弹幕 / 「下载 App」浮层
        ├─ 7. video 元素 playsinline / preload 补齐
        ├─ 8. 推荐流入口  直接落在 /?recommend=1（不是精选九宫格，也不是 modal）
        ├─ 9. 精简模式    几何特征匹配左侧导航 / header，隐藏播放器以外的壳
        └─ 10. 播放设置   音量（video.volume/muted）+ 进度/倍速/清晰度/弹幕/合集回传
```

### 翻页：绕了五圈才找到能用的那条路

这是整个项目最花时间的地方，把结论直接写在这儿，省得后来者重走一遍。真机上依次试过：

| 方式 | 结果 |
| --- | --- |
| `new WheelEvent()` 派发到 `document` / 目标元素 / 连发 8 次 | **无效**。抖音的切换组件校验 `event.isTrusted`，合成事件直接丢 |
| 真实手指拖动（`adb input swipe`） | **无效**。桌面版只监听 `wheel`，触摸不翻页 |
| 键盘方向键（`KeyEvent` / `PageDown`） | **无效**，尽管页面引导文案里写着「点按键盘上下键」 |
| Java 合成 `ACTION_SCROLL` + `SOURCE_MOUSE` | 分发返回 true，**页面不动** |
| **点 `[data-e2e="video-switch-next-arrow"]`** | ✅ **有效** |

`adb shell input roll 0 1` 是能翻页的，说明输入子系统产生的滚轮事件确实被接受；但从 App 内部 `dispatchGenericMotionEvent` 拼出来的那个不被认。既然页面自己就有「上下按钮」，直接点它最省事——`.click()` 走的是组件自己的 onClick，不需要可信事件（和点卡片进详情同一个道理）。

一个必须注意的细节：swiper 会同时挂 **3 组** prev/next 按钮（上一屏、当前屏、下一屏各一套），只有当前这一屏的那组在视口内。点错组等于点了看不见的视频，表现就是「没反应」。所以选之前要用 `getBoundingClientRect()` 过滤掉不在视口内的。

620ms 冷却保留：一次滑动只翻一页。

## 三、界面与控制层

四层结构，各管各的：

```
FrameLayout
 ├─ LinearLayout（纵向）
 │   ├─ WebView     页面本身（播放器、评论、登录浮层都在这里），weight=1
 │   └─ Dock        FeedChrome 的底部条：进度条 + 互动行 + 工具行，wrap_content
 ├─ Overlay         FeedChrome 的浮层：只有顶栏，clickable=false 全透传
 ├─ LoginGate       未登录时的全屏卡片
 └─ Splash          开屏页（最少 2 秒），最后加进来所以压在最上面
```

关键点：**Dock 在纵向布局里真实占位，不是浮在 WebView 上**。
WebView 的高度 = 屏幕 - Dock，播放区到 Dock 顶边就结束，
所以按钮条不会压住画面，也不会遮住页面的作者信息、话题标签和右侧按钮列。

### 为什么 UI 用原生控件，而不是往页面里塞 HTML

1. 注入的 DOM 会被抖音自己的样式和 SSR 冲掉，改一次版就崩；原生层不受影响。
2. 触摸不会被页面的监听器截走，滑动合成和按钮点击互不干扰。
3. 工程是零依赖离线构建（没有 Gradle，用不了 AndroidX / Material / Compose），只能用 framework 控件自己搭——代价是图标得手写成 vector，好处是 APK 仍然只有约 180KB。

### 控制条

- **顶部（浮层）**：左侧是登录态圆点 + 文字（未登录 / 已登录），右侧胶囊：`推荐`（回到推荐流）、`完整`/`精简`（切换周边显隐）、`网页`/`沉浸`（切换视口宽度）。4 秒无操作淡出。全屏时多一个红色的 `退出全屏`，**且全屏状态下顶栏不再自动淡出**——Dock 收了之后那是唯一出口。
- **底部 Dock（占位，不浮在画面上）**，从上到下三块：
  - 第一块（26dp）：**进度条本身**。2dp 轨道画在 View 的顶边，等于「视频底边那条进度」，和画面之间没有别的东西隔着；页面每 250ms 报一次真值，原生按 `playbackRate / duration` 逐帧外推，1x / 1.5x / 2x 都不会跳格；拖动时线条上方浮出 `01:24 / 04:03`，松手消失。
  - 第二块（42dp，互动行）：左侧 `作者头像` + `关注` 胶囊，右侧 `♥ 点赞` / `💬 评论` / `★ 收藏` / `↗ 转发` / `⛶ 全屏`；
  - 第三块（32dp，工具行）：`🔊 静音开关` + `音量条`（吃满剩余宽度）+ `弹幕开/关`、当前清晰度（`720P` / `智能`）、当前倍速（`1.0x`）、`合集` 四枚等宽小胶囊；
  - 整条 Dock 底色 `#0B0B0F`，**不再画 1px 分隔线**——进度条轨道本身就是分界；**常驻，不参与淡出**。
- **页面右侧那一整竖列都搬进来了**：头像、关注、赞、评论、收藏、转发原本在播放器右侧浮着，现在全部接管，页面那份 `opacity:0` 掉（见下）。Dock 总高约 105dp，比上一版的三行 120dp 更矮，同时多了三颗按钮。
- **评论区从 Dock 上方升起，并把视频顶上去**：网页版是右侧抽屉，一开就把视频压成半宽。现在把评论壳（`LookModalFrameFast` 的外层）钉成满宽底部抽屉，视频区压到 `42vh`、抽屉占 `58vh`（由 `SHEET_VH` 一处控制，四道 CSS/JS 拼接都引用它）—— 五五分时一屏只放得下两条半评论，改成 42/58 后能看到三条半。视频完整留在上半屏，两者不重叠，画面等比缩放不变形；打开期间左下角的作者/标题/标签淡出，免得被挤进 16:9 画面上下的黑边里字叠字。关闭时视频自动恢复 `100vh`。
- **评论区按手机版排版**：网页版按 900 CSS px 的设计宽排版，落到 1200 物理 / 369dp 的屏上 1 CSS px ≈ 0.41dp —— 它的 16px 内边距只有 6.5dp、40px 头像只有 16dp、20px 图标只有 8dp。所有尺寸先按 dp 定再乘 2.44 折回：头像 **84px（34dp）**、正文 34px（14sp）/ 行高 1.52、昵称 27px/65% 白（只说明「这是谁」，不和正文抢）、时间/地区 25px、操作区图标 32px、「展开 N 条回复」从页面给的 12px（5sp）提到 28px 蓝色、输入框 92px 外框 + 68px 胶囊。
  结构上也重排了：**时间/地区在左、回复·分享·点赞在右，并成一行**（原来各占一行，每条白吃 30px）；列表左右留白 32/24px；正文里的表情是 `<img alt="[送心]">`，按行高抬到 34px。顶部 `AI 图标 / TA的作品 / 相关推荐` 和「大家都在搜」照旧摘掉；**但右上角的 × 和那 46px 的 tab 行不能再动** —— × 就 absolute 挂在这一层，压高度 / 改定位会把它顶出抽屉。右侧竖列下面那排数字（「1.3万」「1425」…）也跟着藏掉，否则就是关不掉的悬浮标签。
- **评论开合**：点 Dock 评论按钮开、再点关（toggle）；在评论区（下半屏）向右滑也能收起，判定条件是 `dx > 60dp 且 dx > |dy| × 1.8`，纵向滚动不会误触。**侧滑检测的 `ACTION_DOWN` 必须返回 `false`**：抽屉那一块就是网页的滚动列表，一旦原生接管 DOWN，WebView 收不到后续事件，评论区会彻底滑不动（连评论点赞、输入框都点不进去），等 MOVE 里看清方向再抢才是对的。**三条关闭路径（抽屉右上角 ×、Dock 按钮、侧滑）统一走 `beginClose()`**：先撤「高度类」让视频区开始长回去、抽屉顺着视频区下沿滑出屏幕，等它滑到屏幕外才真正关页面（`pageClose`），最后 `dropSheet()` 摘壳。只清 `dy-sheet-open` 不摘 `.dy-sheet` 的后果是下半屏留一条 50vh 的空抽屉，看起来就像「评论区那块被下一条视频占住」。`__DY.sheetOpen()` 是页面侧真实状态（套壳前那几十毫秒用 `want && commentFrame()` 兜着），原生每次按下都实时问一次，不再依赖 1 秒轮询 —— 否则「开完 1 秒内再点一下」会被迟到状态判成「还没开」，把评论当场关掉。
- **开合动画只有一条驱动**：视频区的高度。抽屉的 `top` 恒等于视频区下沿（`dy-sheet-geo` 这个几何类负责，开和关的过程中都要在），所以视频区从 `100vh → 42vh` 过渡时抽屉被「顶」上来、反向时顺着下沿滑出去，两条动作同帧同曲线，不用给抽屉单独写 transform。三个坑：
  1) 打开态**不能**同时写死 `min/max-height`，它们不参与过渡，会在第一帧把高度钳死，动画直接失效；
  2) 过渡只挂在**有显式高度**的层级（`slideList` 是 vh、`feed-item` 是页面写的内联 px、`.dy-pbox` 是内联 vh），`feed-active-video` / `sliderVideo` 是 `100%` 高度，给它们也挂过渡等于同一段动画在嵌套层级各跑一遍，尾巴拖到 600ms；
  3) 收起时页面自己也有一套高度动画（内联 px 每帧改，约 600ms），所以收起期间用 `--dy-sheet-h`（打开前量到的真实高度，实测 1688 而不是 100vh）把它钉住，过渡才只跑我们的 300ms。
- **点开那一百毫秒**：页面会先自己把视频压窄，我们的壳要等一次 JS 往返 + 扫 DOM 才套上，中间会露出下一条视频。现在两道保险：`comments()` 当场把当前条以外的**所有** `feed-item` 设成 `visibility:hidden`（以前只藏紧邻的一个，抽屉提到 58vh 后藏不住第二条），以及 `dy-sheet-geo` 在场期间用 CSS 配 `.dy-active-slide` 标记只显示当前条 —— geo 在 `setSheetVideo(true)` 那一帧加上、收起动画走完才撤，正好覆盖整个风险窗口。逐帧实测开/关各 80+ 帧，全程只有当前条可见。
- **为什么底部要自己做**：抖音网页版用的是 xgplayer，底部一整排（播放 / 时间 / 弹幕 / 倍速 / 清晰度 / 音量 / 全屏）全部贴在屏幕最底边，手机上正好压在「圆角 + 手势条」里，两头被裁、也点不准。现在注入层把 `.xgplayer-controls` 整排 `display:none`，位置和样式由 `FeedChrome` 重排，页面改版也影响不到。
- **工具行是「接管」而不是「删掉」**：那排控件藏掉之后，弹幕开关、清晰度、倍速、合集入口不能跟着消失，所以工具行把它们重新做了一遍——状态由页面每 250ms 随进度一起回传（`progress(sec, dur, playing, quality, rate, danmu, hasCollection)`），点一下改变，胶囊立刻变色。
- **触摸穿透**：浮层容器 `clickable=false`，除按钮外事件直达 WebView，所以滑动翻页、双击点赞全部照旧。Dock 位于 WebView 下方，触摸天然分离。

### 头像上方那两个东西

播放器右侧、作者头像上方还有两坨：`.ai-douyin-entry`（AI 入口）和 `.xgplayer-playswitch`（上下切视频箭头）。

用 `visibility:hidden` 而不是 `display:none` 藏掉——箭头正是我们翻页的实现方式（脚本点它），`visibility` 保留布局，`getBoundingClientRect()` 还能拿到坐标，元素也仍然接受程序化 `click()`，「看不见」和「点得着」可以同时成立。换成 `display:none` 会让 `switchArrow()` 的可见性过滤全部落空，翻页直接废掉。

### 每个按钮到底做了什么

| 按钮 / 手势 | 实现 | 备注 |
| --- | --- | --- |
| 上滑 / 下滑 | 注入层判手势 → 点页面的 `video-switch-*-arrow` | 620ms 冷却，一滑一页；两个箭头按钮本身被 `visibility:hidden` 藏掉 |
| 单击画面 | 页面自己的 click（播放 / 暂停） | 所以我们不再需要一颗播放按钮 |
| 双击画面 | 注入层判「两次抬手 < 300ms 且位移 < 24px」→ 原生点右侧那颗心 | 未登录会先说「登录后才能点赞」再拉起登录框，见第四节 |
| 点赞（Dock） | 页面报按钮坐标（`__DY.point('like')`），原生 `dispatchTouchEvent` 真点一下 | **不能 `el.click()`**：这按钮只认 `isTrusted` 输入，见下 |
| 评论 | 同上，点 `[data-e2e="feed-comment-icon"]` | 面板由页面自己开合 |
| 收藏 | 同上，点 `[data-e2e="video-player-collect"]` | 页面上那颗星是描边，Dock 里已收藏换成实心黄色 |
| 转发 | 同上，点 `[data-e2e="video-player-share"]` | 弹出的是页面自己的分享面板 |
| 关注 | 同上，点 `[data-e2e="feed-follow-icon"]` 里的可见子元素 | 那颗 `+` 是绝对定位徽标，父元素高 0，得往下找有尺寸的后代 |
| 作者头像 | Dock 左侧 30dp 圆图，HTTP 直接下 CDN 图 | 页面用 `span + background-image` 画头像，不是 `<img>`，得连背景图一起捞 |
| 音量 | 拖音量条 / 点喇叭 → `video.volume` + `muted` | 不动系统媒体音量；**启动默认静音**，见下 |
| 全屏 | Dock 收起 + 隐藏系统栏，WebView 自己变高 | 出口在顶栏的 `退出全屏`；返回键也先退全屏 |
| 进度条 | 拖动 → `__DY.seekToFraction()`；进度由页面每 250ms 推给原生，原生按倍速外推 | 页面那排控件已隐藏，这是唯一进度入口 |
| 推荐 | 直接导航到 `/?recommend=1&from_nav=1`（`__DY.enterFeed`） | 已经在推荐流里就提示「已经在推荐流」 |
| 完整 / 精简 | 恢复 / 隐藏左侧导航与整条 header（`__DY.setFocus`） | 纯 CSS 层操作，随时可逆 |
| 弹幕 | `__DY.toggleDanmu()`，控制 `[class~="danmu"]` 层显隐 | 默认关；页面那排控件里的弹幕按钮已随控件条一起隐藏 |
| 清晰度 | `__DY.cycleQuality()`，点一下切下一档 | 跳过「1080P 登录即享」，否则一点就弹登录框；没有选中项时回退到 `智能` |
| 倍速 | `__DY.cycleRate()` → `video.playbackRate` | 0.5 → 0.75 → 1 → 1.25 → 1.5 → 2 循环；换片后 `keepRate()` 每 600ms 补回来一次 |
| 合集 | 同点赞：算坐标 → 原生合成触摸 | 入口是内容匹配（文本以「合集」开头的短胶囊）；胶囊约每 5 秒复核一次，入口没了自动变灰；点之前还会现算一次，不会拿旧状态骗人 |
| 看相关 / `…` | 不实现 | 「看相关」按文案匹配整格 `display:none`；`[data-e2e="video-play-more"]` 直接 CSS 藏掉 |

### 音量：为什么不用 AudioManager

`AudioManager.setStreamVolume(STREAM_MUSIC, ...)` 改的是**全局**音乐音量——用户拖一次视频音量，铃声、通知、别的 App 全跟着变，很难解释，也没法「只让这个 App 静音」。

所以直接改 `<video>.volume` / `muted`：

- 元素级，只影响这一路播放，退出 App 不影响系统；
- 启动 `muted = true`（`CFG.startMuted`），自动播放也更稳——浏览器对静音视频的自动播放是放行的；
- 换片时页面会把 `volume` 重置成 0.5 并取消 `muted`，所以 `work()` 每轮补一次（和 `keepRate()` 一个道理）；
- 拖动时「边拖边生效」，声音跟着手指走，松手不回弹。静音时不去动 `volume`，留着上次的值，取消静音才有合理起点。

### 右侧那一整列都藏了（而且只能用 opacity）

头像、关注、赞、评论、收藏、转发在页面上都是重复入口（Dock 里都有），整列隐藏。这里**只能**用 `opacity:0`：

- `display:none` → 整列塌陷，上面的头像往下挤，坐标全变，合成触摸全部点歪；
- `visibility:hidden` → 元素不再参与命中测试，原生合成的那一下触摸会穿到 `<video>` 上，变成「暂停」而不是「点赞」；
- `opacity:0` → 不可见、仍占位、仍然接受指针事件，三个条件同时满足。

例外是「看相关」和「…」：它们不需要被点，所以直接 `display:none` 拿掉，塌陷无所谓。

**但 `opacity:0` 也意味着真手指点那个位置仍然有效**——实测点旧评论图标会偷偷打开评论区，还带出悬浮标签。注入层在 `document` 上挂了捕获阶段的 `click` / `pointerdown` / `mouseover` 监听，只拦 `isTrusted=true` 的事件，且目标必须在右侧竖列（`left > 60% 屏宽`）或那两个切视频箭头上。脚本 `.click()` 是 `isTrusted=false`，照常放行。

原生 Dock 的点击本身也是合成的 `MotionEvent`，对页面同样是 `isTrusted=true`，所以 `MainActivity.tapElement()` 会先写 `window.__DY_NATIVE_TAP__ = Date.now()+500`，注入层看到这个标记就放行自己人，600ms 后清掉。

### 点赞/收藏的状态从哪来

不从自己记的账来。页面按钮上挂着权威状态：

```
[data-e2e="video-player-digg"]     data-e2e-state = video-player-is-digged | video-player-no-digged
[data-e2e="video-player-collect"]  data-e2e-state = video-player-is-collected
```

注入层每 250ms 随进度读一次，签名变了才过桥（`author(avatar, nick, liked, collected)`）。
这样取消点赞、收藏被作者删掉、翻页，Dock 上的心和星都会跟着回来，不需要本地状态机。

**「关注」没有权威状态**：那颗 `+` 是 hover 才渲染的徽标（元素常驻、内部 `svg` 带 `hidden`、高度恒为 0），
从 DOM 上分不出已关注还是没关注。硬判的结果是「所有视频都显示已关注」，比不显示还糟。
所以关注只做交互反馈：点一下变「已关注」，翻到下一个视频时重置回「关注」。

### 为什么点赞/评论要点「两次」（合成触摸）

`el.click()` 派发出去的事件 `isTrusted=false`。翻页箭头是纯 `onClick`，所以脚本点得动；但点赞、评论这两个挂在 xgplayer 上的按钮只认真实输入事件，脚本点了完全没反应。做法是页面先算出按钮中心的 CSS 坐标，交给 `MainActivity.performTap()` 用 `MotionEvent` 合成一次 `ACTION_DOWN` + `ACTION_UP`（间隔 70ms）——和手指按下去走的是同一条链路，`scale = WebView 实宽 / innerWidth` 负责把 CSS 像素换算成 View 坐标。

顺带一个坑：未登录时点这些按钮会弹「登录后即可点赞」，而我们的遮挡层清洁工 600ms 一轮，弹窗刚出来就被关掉——用户视角就是「按了没用」。现在只要屏幕上有触摸，就给遮挡层 12 秒豁免（`holdOverlaySweepOnGesture`），走登录流程时更是直接暂停 4 分钟。

### 流量：两级磁盘缓存

`app/src/main/java/com/example/douyinweb/MediaCache.java` 接管 `shouldInterceptRequest`，缓存目录 `Android/data/com.example.douyinweb/cache/media-cache/`，视频和资源共用一个上限（`min(192MB, 可用空间/16)`）、按最久未用淘汰。位置和上限为什么这么定，见下一节。

**视频**：抖音是 fetch + MSE，按 200KB 左右的分片拉流，整文件缓存几乎永远命不中。所以缓存单位是「URL + Range」，同一分片下次直接读盘。两个关键点：

- 回源时**必须把 CORS 响应头原样吐回去**。少了 `Access-Control-Allow-Origin`，播放器判跨域失败，会换个 range 从头再要——实测 1 秒内 7 次 `bytes=0-`，缓存反而变成流量放大器。
- 只有整段读完才落盘；划走 / seek 导致的半截流直接丢掉。

实测往回滑到看过的视频：5 秒 **3.66MB → 0.99MB**。

**网页静态资源**：JS / CSS / 字体 / 图片（按扩展名识别），文件名带 hash，缓存 3 天。主文档 HTML 和 `/aweme/`、`/api/` 接口**故意不缓存**——登录态和推荐流必须拿最新的。抖音很多 JS chunk 是 chunked 传输、`Content-Length=-1`，只按声明长度读会把布局的大头全跳过去，所以要流式读到 6MB 上限。

实测同一安装第二次冷启动：**291 次资源命中，只新下 2 个**。日志里 `资源回源` / `资源命中` / `已缓存` 可以直接看效果。

### 存储：为什么现在不会一直膨胀

缓存省流量，代价是占地方。上一版用的是「`files/` 目录 + 512MB 固定上限」，实测用两天就吃满 **375MB**（`MediaCache`）**+ 181MB**（WebView 自己的 HTTP 缓存）。两个问题都很致命：

1. **`files/` 在系统眼里是「应用数据」，不是「缓存」。** 设置里的『清除缓存』、各家管家、系统低存储驱逐**全都碰不到它**。用户看到的就是「装完越用越胖，还清不掉」。
2. **WebView 的缓存从来没人管。** `cache/WebView/Default/HTTP Cache/` 下 `Cache_Data`（响应体）+ `Code Cache`（V8 编译产物）会随浏览一直长，Chromium 的回收策略非常保守。

现在分三层治理，各自有明确的天花板：

| 位置 | 内容 | 上限 | 谁来收 |
| --- | --- | --- | --- |
| `cache/media-cache/`（外部） | 我们拦下来的视频分片 + 静态资源 | `min(192MB, 可用/16)`，<1GB 时降到 32MB | 写入后 20s 触发 LRU；`onTrimMemory` 切后台再收一次 |
| `cache/WebView/.../Cache_Data` | WebView 的 HTTP 响应体 | 120MB | 启动时（WebView 还没创建）整块删 |
| `cache/WebView/.../Code Cache` | V8 编译产物 | 40MB | 同上 |
| `app_webview/` | **Cookie / IndexedDB / LocalStorage** | 不设限 | **永远不碰**，删了就要重新扫码登录 |

几个必须守住的点：

- **缓存必须放 `getCacheDir()` / `getExternalCacheDir()`**。只有 cache 目录才会被系统当成缓存：用户在设置里点一下、系统在低存储时驱逐，都能真正释放。升级后第一次启动会把旧的 `files/media-cache` 整棵删掉（实测释放 358MB）。
- **WebView 的目录只在启动时删**。跑起来之后删 `Cache_Data` 会踩到 Chromium 自己的索引和文件句柄。启动窗口里删，代价实测只有 **+67ms**（删 162MB）。
- **`Code Cache` 和 `Cache_Data` 要分两条阈值**。前者清掉只费 CPU（JS 重新编译），阈值给 40MB；后者清掉要重新下载首屏 JS/CSS（十几兆流量），阈值才给 120MB。抖音每次发版都换资源 hash，旧版本的缓存永远命中不了，纯占地方。
- **`ensureDir()` 不能省**。缓存挪进 cache 目录后，系统在低存储时会**连目录一起删掉**，写入前必须补一次。

改完实测：`375MB(files) + 181MB(WebView) → 50MB + 26MB`，日常稳定在 **80MB 上下**，天花板约 350MB 且不会再涨。日志目录 `Download/DouyinShell/` 也做了收敛：文件名复用 + 只保留最新两份，稳定在 1MB。

### 落地页必须是 /?recommend=1

`www.douyin.com/` 落地是「精选」四列网格，翻起来很别扭，所以冷启动直接导航到推荐流：

```
https://www.douyin.com/?recommend=1&from_nav=1
```

**两个参数都不能省**：`recommend=1` 是推荐队列，`from_nav=1` 是「从左侧导航点进来」的标记——只带 `recommend=1` 时服务端会 302 回 `/jingxuan` 网格页（真机日志里能看到 `加载完成 https://www.douyin.com/jingxuan`）。

#### 踩过的坑：最早的入口会「一直刷同一个人」

第一版是「在精选网格里点开第一条，再跳到 `/jingxuan?modal_id=<id>`」。看着是沉浸式全屏播放器，但那个 modal 的「下一个」只在**这张卡片所属的合集 / 同一作者的其他作品**里循环——实测连刷十条全是同一个人。更坑的是日志完全正常：`WHEEL 点「下一个」按钮` 一条不少，时长、封面对得上，只有作者不变。

换成 `?recommend=1` 之后，一次滑动换一个作者，真机连滑三次：`@美团老鼠 → @阿布漫文 → @林恩环球记 → @一只黑Q`。

精选 modal 这条路径没有删，只是降级成兜底：如果推荐路由又被重定向回 `/jingxuan`（`sessionStorage` 里有 40 秒的尝试窗口），就退回「点开第一条卡片」。

#### 其它细节

- **只自动进入一次**（`sessionStorage` 标记）。用户按返回键退回网格页是明确意图，不能又把他弹回去；想再进就点顶部的 `推荐`。
- **已经在推荐页上时绝不能再导航**。视频挂载要两三秒，这期间 `isFeedPage()` 还是 false；不加这个判断，每轮轮询都会刷一次页面，永远加载不完。
- **兜底卡片要是带 `href` 的 `<div>`**。精选页的卡片不是 `<a>`，写 `a[href*="/video/"]` 在真机上永远是 0 个；广告卡（角标「广告」）也要跳过，点进去是落地页。

### 双击点赞：页面自己不做

桌面版把点赞做成了「双击播放器」，但那是给鼠标 `dblclick` 的：

| 尝试 | 结果 |
| --- | --- |
| 手指双击（`adb input tap` ×2） | **不点赞**。被当成两次单击：暂停 → 播放 |
| 脚本 `dispatchEvent(new MouseEvent('dblclick'))` | **不点赞**。`isTrusted=false`，页面直接丢 |

所以双击由注入层自己判：两次抬手间隔 < 300ms、位置相差 < 44px、位移 < 24px（少了这条，连着两次快滑会被当成双击），命中后通过桥调 `doubleTapLike()`，原生再走和 Dock 那颗心一样的路径——算坐标、合成真实触摸去点 `[data-e2e="video-player-digg"]`。

- **未登录先给提示**：`Toast("登录后才能点赞")` + 拉起抖音自己的登录框（和点 Dock 上的心同一个流程）。
- **要有反馈**：页面上那颗心被我们 `opacity:0` 了，点的是它但看不见，所以 Dock 上的心会弹一下（放大 1.35 倍 + 染成强调色）。不加这个动画，用户会觉得「双击没反应」。

### 开屏页 + 冷启动黑幕

启动顺序是三层叠出来的：

1. `Splash`（开屏页）：深色圆角图标 + 「抖音 · 网页版」+ 一条 3dp 的加载线，**最少显示 2 秒**；
2. `cover`（纯黑 View）：挡在 WebView 上，防止先闪一下「精选」网格；
3. WebView 自己。

`revealPage()` 撤销黑幕，并且只在「2 秒到了 **且** 内容已经露出来」时才收开屏页——否则会出现「开屏页 2 秒 → 然后一段纯黑等加载」的观感。2 秒还没起来的话，开屏页上的小字会从「正在载入推荐流…」变成「正在连接抖音…」，让人知道它没死。

不用 `setVisibility(INVISIBLE)` 挡 WebView：不可见的 WebView 会暂停渲染和 `requestAnimationFrame`，页面自己的首屏都渲染不出来，反而永远进不去。

兜底三道，防一直盖着：脚本回报进入推荐流（正常，约 3~6 秒）→ 首屏加载完 8 秒 → 启动 18 秒。

### 全屏

原生做的，不点页面那个 `[data-e2e="xgplayer-page-full-screen"]`——那是浏览器的 Fullscreen API，在 WebView 里会把 `<video>` 提成一个原生 `customView`，Dock、手势、注入层全部失效。

本方案：`setFullscreen(true)` → Dock `GONE` + `enterFullscreen()`（`FLAG_FULLSCREEN` + immersive sticky）→ 系统栏收起、`WindowInsets` 变小、WebView 自己长高（真机 `innerHeight` 1564 → 1897）→ 260ms 后调一次 `__DY.refreshLayout()` 重算视口和播放器铺满。

出口有两个：顶栏的 `退出全屏` 胶囊（进全屏时先亮 4 秒，之后手指一碰屏幕就回来），以及返回键（`onBackPressed` 先退全屏，不会一下子退回网格页）。

### 系统栏：顶部沉浸，底部让位

targetSdk 35 起 Android **强制 edge-to-edge**，`setDecorFitsSystemWindows` 已经是空操作，`statusBarColor` 也基本失效。不管的话页面会直接顶到状态栏底下。

处理方式是**只做一半**：

- **底部**留出 `bottom` inset（这台机器 52px），Dock 不能被手势条压住；
- **顶部不给留白**，WebView 从 y=0 开始，视频真正铺到状态栏底下。状态栏图标直接压在画面上（画面是深的就看得清）；
- 顶栏那排胶囊（未登录 / 推荐 / 完整 / 网页）会和状态栏重叠，所以 `top` inset 交给 `FeedChrome.setTopInset()`，让它自己把 `topMargin` 加上这个高度。

注意 `applySystemBarInsets()` 在 `buildChrome()` **之前**调用，第一次 insets 回调时 `chrome` 还是 null，所以建完控制层要再 `requestApplyInsets()` 一次。

### 铺满：右侧那 60px 是页面的 padding

视频右侧曾经一直空一条 60px 的深色带。不是 WebView 没铺满，是页面自己留的：

```
[data-e2e="slideList"]  width=900  padding: 0px 60px 0px 0px
```

那 60px 是给右侧竖列按钮留的位置。按钮全搬进 Dock 之后没用了，一条 `padding-right:0 !important` 就铺满。

顶部那 56px 同理，是三层叠加的：

1. 一个 `position:fixed` 的外壳（高 56、底色 `#161823`），header 藏了之后它就只剩一条黑带；
2. header 紧邻的父级盒子（原来的 `flattenHeader()` 只收了这层）；
3. 更外层的容器用 `padding-top:56px` 给 header 让位。

第 1 层最容易漏：它比 header 高两级，且左边有 72px 侧栏缩进 —— 只收第 2、3 层的话，状态栏右侧仍是 `#161823` 黑带、左侧 72px 露出视频画面，两块颜色割裂，看着就是「状态栏上一块黑的」。现在从 header 的父级往上最多找 4 层，命中「贴顶 + 高 24~96 + 宽过半 + `position:fixed`」的外壳一并 `hideEl()`（走同一套隐藏记录，关精简模式能还原）。都不按 class 找（hash 过的），见 `flattenHeader()`。

### 精简模式：把「网页版的外壳」收起来

左侧那条「精选 / AI抖音 / 关注 / 朋友 / 我的 / 放映厅…」导航栏和顶部整条 header，是桌面网页版的东西，手机上纯属占地方。

不按 class 名找——抖音的类名是构建时 hash 过的。改按**几何特征**匹配：

- 导航栏：贴左（`left < 44`）、窄（`width < 24%`）、高（`height > 35%`）、里面有一排链接或图标；
- 顶栏：`<header>` 标签、贴顶（`top < 8`）、高 24~96、宽度过半屏。

推荐流里那条 header 还有个具体危害：它占掉顶部 56px，手指想暂停时很容易先点到搜索框。藏掉它不影响登录——入口是自己的左上角状态条 + 点赞拉起的浮层，登录态判断以 cookie 为准。

误伤了点一下 `完整` 就复原（`setFocus` 会把 `hiddenEls` 里的 `display` 全部 remove）。

### 登录

- 本应用**没有账号体系**，不采集手机号 / 验证码 / 密码——引导页上没有任何输入框，这是刻意的：有输入框就要处理凭据，性质就变了。
- 点「在页面内登录」后会自动把抖音的登录框弹出来（见下），扫码或短信验证都在官方页面上完成。
- 登录成功后抖音自己会写 cookie，原生侧轮询 `CookieManager` 判断，命中即自动收工并提示「登录成功」。
- 判定依据（纯只读，不伪造、不请求接口）：`sessionid` / `sessionid_ss` / `sid_tt` / `sid_guard` / `uid_tt` 任一存在；再用页面的 DOM 状态（`__DY.loginState()`）兜底。
- **引导层永远留一个「先随便看看」**：cookie 名是猜的，万一抖音改了也不能把用户锁在门外。
- **冷启动不自动弹引导页**。登录入口只留在左上角的 `未登录` 状态条上，点一下就是同一个引导页。想看视频的人不用先做一次选择。

两个坑：

**1. 沉浸层把页头的「登录」按钮盖住了。** 用户在沉浸视图里根本找不到登录入口，所以「在页面内登录」不能只是收起引导层。程序化点页头那个「登录」按钮无效（它只认真实点击，点它的父级 0~3 层全试过，都没反应），最后借「点赞」当入口：未登录时点 `[data-e2e="video-player-digg"]` 会弹「登录后即可点赞」，正好就是登录框。代价说明白——万一把「已登录」误判成未登录，就是给某个视频点了个赞，可撤销。

**2. 登录态判断不能认「头像」。** 早期写 `img[src*="aweme-avatar"]` 当已登录，结果**视频作者**的头像也是这个域名，一进页面就判成已登录，还弹「登录成功」——用户以为登录了，实际点赞评论全失败。现在只认顶栏自己的 `[data-e2e="user-avatar"]`，拿不准就返回 `unknown`，绝不误报。

### 遮挡层清理

未登录访问时，抖音会在沉浸页上叠三层东西，全部要清掉，否则「只关注视频本身」是空话：

| 目标 | 选择器 | 说明 |
| --- | --- | --- |
| 登录弹窗 | `#login-panel-new` 内右上角 37×36 的带 svg 元素 | class 是 hash 过的，只能按几何位置找关闭按钮。z-index 9999，盖住所有卡片 |
| 新手引导 | `#douyin-web-recommend-guide-mask` | 「滚动鼠标或点按键盘上下键查看更多推荐视频」，有稳定 id |
| 弹幕层 | `[class~="danmu"]` | 压在画面顶部一大片。注意它有 `pointer-events:none`，用 `elementFromPoint` 反查永远命中下面的 `<video>`，很容易误判成「弹幕烧进了视频」 |

**用户点「去登录」后要暂停清理**（`__DY.pauseOverlaySweep(240s)`），否则登录框刚出来就被自己关掉，登录永远走不完。

### 显示模式 + 播放器铺满：这是「刷视频」体感的来源

桌面版播放器固定 16:9，在 900 布局里是 900×506，而竖屏的布局视口有 2000 高——上下各空出一大片。两件事分开做：

1. **视口宽度**（顶部 `网页 / 沉浸`）：只在 `designWidth` 上做文章，不动 DOM。
2. **播放器容器高度**（默认开）：把视频所在容器的高度改成 `100vh`，`object-fit: contain`。竖视频按宽度铺满后能占满整个屏幕高度（506 → 1999），横视频保持 16:9 居中，**不裁切任何画面**。

| 模式 | designWidth | 412dp 屏上的缩放 | 效果 |
| --- | --- | --- | --- |
| 沉浸（默认） | 900 | 0.458 | 画面整体放大约 1.42 倍 |
| 网页 | 1280 | 0.322 | 完整桌面版，信息全但字小 |

改容器高度是有前提的自我保护，不是无脑改：

- 只对「宽过半屏、高过两成屏」的 video 动手，网格页里的悬停预览不会误伤；
- 改完必须立刻量一遍，真的变高了才认，否则撤回再试上一层（最多 4 层），防止父级有 `overflow` 把改动吃掉；
- 仍然**不做 `transform` 裁切**。裁切要看容器的真实结构（几层 wrapper、谁 `overflow:hidden`），盲写大概率得到一块被裁掉的黑框。铺满高度已经解决了大部分空白，剩下的留给真机截图再定。

如果 900 触发了抖音的窄屏布局（播放器和评论列会重排），把 `douyin.user.js` 里的 `WIDTH_IMMERSIVE` 调回 `1024` 或 `1100` 即可，不用改别的。

## 四、构建

本机 SDK：`$ANDROID_HOME`（build-tools 36.0.0 / android-36）

```bash
./build.sh
# 产物：douyin-web-shell.apk
adb install -r douyin-web-shell.apk
```

`build.sh` 直接调 `aapt2 → javac → d8 → zipalign → apksigner`，不需要 Gradle、不需要 Android Studio、不需要联网。

也可以把工程直接用 Android Studio 打开（AGP 8.10.1 + compileSdk 36），但**没有任何第三方依赖**，所以 Studio 那条路只是可选。

## 五、日志

没有真机在手时，日志是唯一能看见 App 内部发生了什么的东西。启动时会在**手机的「下载」目录**建一个日志文件，任何时候都能用文件管理器或 USB 直接翻。

```
Download/DouyinShell/douyin-shell.txt
```

| 系统版本 | 落盘方式 | 需要权限 |
| --- | --- | --- |
| Android 10+ | MediaStore 写入公共「下载」目录 | 不需要 |
| Android 8 / 9 | 直接写公共目录 | `WRITE_EXTERNAL_STORAGE`，首次启动会弹一次 |
| 权限被拒 / 写失败 | 退到 `Android/data/com.example.douyinweb/files/Download/DouyinShell/` | 不需要 |

冷启动的 toast 那句已经去掉了（每次启动弹一遍太吵），路径写在日志第一行和 logcat 里。`ENABLE_FILE_LOG` 是本项目的调试设施，不是产品功能——不想留日志就把它改成 `false` 重新出包，代码里没有任何地方依赖它。

### 记了什么

| 前缀 | 内容 |
| --- | --- |
| `TITLE` | 分节标题（设备与环境 / 会话分隔） |
| `APP` | 版本、机型、Android 版本、内核版本、Activity 生命周期 |
| `LOG` | 日志文件位置、存储权限授予结果 |
| `UA` | 内核原始 UA 与改写后 UA，一眼看出桌面伪装有没有生效 |
| `REQ` | 主文档请求头采样（UA / `Sec-CH-UA-*` / Accept-Language / Cookie 大小） |
| `PAGE` | 开始加载、加载完成（含耗时）、主文档错误码、主文档 HTTP 状态码 |
| `JS` | 注入脚本回传的判断（视口、wheel 派发、浮层清理、video 适配、脚本异常） |
| `CONSOLE/E` `CONSOLE/W` | 页面自身的 error / warn，每页封顶 150 条 |
| `SCHEME` | 被拦下的 `snssdk1128://` `aweme://` `intent://` 等跳转 |
| `VIDEO` / `FILE` / `DOWNLOAD` / `PERM` | 全屏进出、文件选择器、下载回调、网页权限请求 |
| `WEBVIEW` | 渲染进程崩溃（`onRenderProcessGone`） |
| `UI` | 控制条上的每次点按，以及对应的 JS 返回结果 |
| `LOGIN` | 登录引导展示、cookie 判定、轮询命中 / 超时 |
| `MODE` | 显示模式切换与最终 designWidth |

### 三条设计约束

1. **只留尾部 256KB**。环形缓冲，超出丢最旧的行，不会把手机写满。
2. **跨会话累积**。启动时把上次内容读回内存，插一条「会话开始」分隔线再继续写，所以 App 崩了、被杀了，重启后还能看到上一轮的尾巴。
3. **延迟 1.5s 批量刷盘**，`onPause` / `onDestroy` / 渲染进程崩溃时立即同步落盘。代价是极端情况下最多丢最后 1.5 秒。每次只**追加**新增的那几行，只有首次写、缓冲区裁剪、追加失败这几种情况才整体重写，不会每分钟往闪存写十几兆。

### 开关

都在 `MainActivity.java` 顶部：

| 常量 | 默认 | 说明 |
| --- | --- | --- |
| `ENABLE_FILE_LOG` | `true` | 总开关，关掉后只剩 logcat |
| `MAX_CONSOLE_LINES` | 150 | 每页最多记多少条页面 console |
| `MAX_HEADER_SAMPLES` | 3 | 每页采样几次主文档请求头 |

也可以只看 logcat，内容与文件一致：

```bash
adb logcat -s DyShell
# 或把日志文件拉下来
adb pull /sdcard/Download/DouyinShell/douyin-shell.txt
```

### chrome://inspect

1. `adb shell am start -n com.example.douyinweb/.MainActivity`
2. Chrome 打开 `chrome://inspect`，能看到 WebView 的页面与 console
3. 控制台里可以直接调：

```js
__DY.state()                // 环境快照：视口 / dpr / video 数量 / 当前模式
__DY.log('手动一条')         // 写进 Download 目录的日志

__DY.next() / __DY.prev()   // 翻页（和滑动手势同一条路径）
__DY.togglePlay()           // 播放 / 暂停，返回 playing | paused | none
__DY.like()                 // 双击点赞（合成 dblclick，页面不认，仅供对照）
__DY.comments()             // 点开评论区，返回命中的选择器
__DY.video()                // 当前播放器的尺寸与暂停状态
__DY.loginState()           // in | out | unknown
__DY.setMode('immersive')   // 沉浸（900）/ setMode('web') 网页（1280）

__DY.setVolume(0.6)         // 设置音量（0~1 的元素级音量），返回设置后的值
__DY.toggleMute()           // 静音 ↔ 恢复上次音量
__DY.volume()               // 当前音量
__DY.refreshLayout()        // 全屏切换 / 旋转后重算视口 + 播放器铺满

__DY.wheel(1)               // 手动翻下一个视频（绕过按钮直接派发）
__DY.refit()                // 重新套一遍视口
__DY.sweep()                // 手动清一次浮层
__DY.config                 // 当前配置
```

## 六、调参

改 `app/src/main/assets/douyin.user.js` 顶部的 `CFG`，然后 `./build.sh`：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `designWidth` | 900（App 内）/ 1280（油猴） | 桌面布局宽度。由 `WIDTH_IMMERSIVE` / `WIDTH_WEB` 决定，控制条右上角可实时切换 |
| `viewportMode` | `meta` | `meta` 只写 `width=1280` 交给引擎 fit；`meta-scale` 额外写 `initial-scale`；`css` 改用 `html{zoom}` 兜底 |
| `spoofTouch` | `true` | 关掉可保留原生触摸，用来确认某个问题是伪装引起的还是别的原因 |
| `wheelCooldown` | 620 | 冷却毫秒，一滑跳多个视频就调大 |
| `wheelInvert` | `false` | **如果上滑反而回到上一个视频，把它改成 `true`**，不用动逻辑 |
| `hideDanmaku` | `true` | 隐藏弹幕层（`[class~="danmu"]`） |
| `swipeMinPx` / `swipeMinSpeed` | 34 / 0.25 | 滑动判定：距离阈值与速度阈值（px/ms），快滑看速度、慢滑看距离 |
| `synthDoubleTap` | `true` | 双击点赞由注入层判定后交给原生（页面不认 dblclick，见第三节） |
| `removePromos` | `true` | 「打开/下载 App」浮层清理开关 |
| `autoFeed` | `true` | 冷启动自动进入推荐流 |
| `recommendUrl` | `/?recommend=1&from_nav=1` | 落地页。`from_nav=1` 去掉会 302 回 `/jingxuan` |
| `startMuted` | `true` | 启动静音（`video.muted = true`），音量条默认 0 |
| `focusMode` | `true` | 精简模式：隐藏左侧导航 + 整条 header，只留视频 |
| `fillVideo` | `true` | 播放器容器拉到 `100vh`，竖视频铺满屏幕高度 |
| `hideOwnRail` | `true` | 隐藏页面右侧整列按钮（头像/关注/赞/评论/收藏/转发）。只能 `opacity:0` |
| `hideExtras` | `true` | 去掉「看相关」和 `[data-e2e="video-play-more"]`（`…`），这两个 Dock 里没有对应功能 |
| `fullBleed` | `true` | 归零页面顶部的 56px 留白和右侧的 60px 留白，视频顶到状态栏、贴到屏幕右缘 |
| `bigInfo` | `true` | 左下角作者名 28px / 文案 24px，描述铺到右边缘再折行，字体强制 `system-ui` |
| `sheetComments` | `true` | 评论区改成 Dock 上方的底部抽屉（原来是右侧抽屉，会把视频压成半宽） |
| `sheetPushVideo` | `true` | 抽屉展开时视频压到 `42vh`、抽屉占 `58vh`（`SHEET_VH` 控制）；关闭后恢复 `100vh` |
| `simpleCommentTabs` | `true` | 评论区顶部只留「评论」一个 tab，去掉 AI 图标 / TA的作品 / 相关推荐 |
| `blockGhostRail` | `true` | 拦掉点在旧按钮 `opacity:0` 区域上的真实触摸（脚本点击放行） |
| `trueFullscreen` | `true` | 视频铺满整屏（`object-fit:cover`），网页版自带全屏只藏系统栏、画面还是 16:9 |

还有两个不在 `CFG` 里、但调起来最常用的，都在 `douyin.user.js` 顶部：

- `SHEET_MS`（默认 **300**）—— 评论抽屉开合动画时长。它喂给**抽屉自己的 transform 过渡**和「动画结束才关页面 / 拆壳」的定时器，两处必须一致。注意 23:1x 之后动画不再由视频区高度驱动（那样会甩动视频），改用抽屉 translateY(100%)↔0，高度瞬时到位。调大更从容但显慢。
- `SHEET_VH`（默认 **58**）—— 抽屉占视口高度的百分比，视频区自动拿 `100 - SHEET_VH`。**所有用到高度的地方都引用它**：`.dy-sheet` 的 height、`dy-sheet-open` 时视频区的 height、`setSheetVideo()` 和 `fillPlayer()` 写的内联高度。别再各处写死 `50vh`，漏掉一处就会在翻页 / 换视频时把抽屉压回画面上。

存储相关的参数在 Java 侧，改完同样 `./build.sh`：

| 位置 | 参数 | 默认 | 说明 |
| --- | --- | --- | --- |
| `MediaCache` | `MAX_BYTES` | 192MB | 视频+资源缓存的**硬上限**，实际取 `min(它, 可用/16)` |
| `MediaCache` | `MIN_BYTES` | 16MB | 打底下限，再小就等于没缓存了 |
| `MediaCache` | `TIGHT_AVAIL` / `TIGHT_BYTES` | 1GB / 32MB | 剩余空间低于 1GB 时切「应急档」 |
| `MediaCache` | `MAX_UNIT_BYTES` | 24MB | 单个响应单元上限，超过不缓存（长视频整段就是透传） |
| `MediaCache` | `ASSET_TTL_MS` | 3 天 | 静态资源有效期 |
| `WebViewCache` | `DATA_LIMIT` | 120MB | WebView 响应缓存上限，超了启动时清。**调小省空间、调大省流量** |
| `WebViewCache` | `CODE_LIMIT` | 40MB | V8 编译缓存上限，清它只费 CPU 不费流量 |
| `ShellLog` | `KEEP_LOG_COPIES` | 2 | `Download/DouyinShell/` 最多留几个日志文件 |

## 七、已知限制

1. **Client Hints 没处理**。`setUserAgentString` 只改 UA，`Sec-CH-UA-Platform` 仍是 `"Android"`。如果某天抖音开始按这个判定，就得加本地反代改写请求头（`shouldInterceptRequest` 只能读不能改，必须自己做转发）。目前实测 UA 是主判据，先不加这层复杂度。
2. **注入不是真正的 document-start**。零依赖构建用不了 `WebViewCompat.addDocumentStartJavaScript`，只能在 `onPageStarted` 注入，页面自身的首个脚本有可能比我们早跑。真要抢在最前面，就把 `androidx.webkit` 加回来（会失去 `build.sh` 离线直编能力，得走 Gradle）。
3. **视觉密度**。桌面版是 1280 设计宽度，默认的沉浸模式压到 900，等效 0.46 倍缩放——比原来的 0.32 好读，但仍然不是手机原生布局。真正要「一屏一个视频」得做播放器裁切，见第三节末尾的说明。
4. **清晰度**。WebView 软解，默认 1080p 会掉帧发热，建议在播放器里手动切 720p。
5. **扫码登录**在 WebView 里可用，登录态靠 WebView cookie；`onPause` 已 `flush()`，但清后台被杀仍可能掉登录。
6. **混合内容开关是放开的**（`MIXED_CONTENT_ALWAYS_ALLOW`）。抖音全站 https，本可收紧；放开是为了兜住 http 视频 CDN，属主动权衡，不是疏忽。
7. `ENABLE_WEBVIEW_DEBUG` 默认 `true`，USB 连上就能用 `chrome://inspect` 读到页面数据（含登录态）。长期使用请改 `false` 重新出包。
8. **日志里会有页面 URL 和接口路径**（Cookie 只记字节数，不记内容）。自己看没问题，往外贴之前先扫一眼。
9. **登录态判定是启发式的**。cookie 名（`sessionid` 等）和 DOM 选择器（`data-e2e`）都是按公开页面结构写的，抖音改版就可能失效。失效的表现是：已经登录了但状态条还显示「未登录」。此时不影响使用，点状态条重新走一遍即可；要修就是改 `cookieSaysLoggedIn()` 里的关键字。
10. **评论按钮是「猜」出来的**。`[data-e2e="comment-icon"]` 这类钩子一旦改名，按钮就会提示「没找到评论按钮」，日志里会记录当时试过的选择器。
11. **推荐流入口靠一条 URL**。`?recommend=1&from_nav=1` 一旦被抖音改掉（或改成必须登录才给推荐），日志里会先是 `加载完成 .../jingxuan` 再出现「推荐路由没落地，退回精选 modal」，也就是退化成「同一作者连着播」的老状态。这时换入口地址即可，别去猜签名参数。
12. **翻页按钮是页面自己的控件**。`[data-e2e="video-switch-next-arrow"]` / `-prev-arrow` 一旦改名，滑动手势就会失效（日志里 `WHEEL` 行会消失）。这时按第三节的表格重找入口，别回头去折腾 `WheelEvent`——`isTrusted` 那道墙翻不过去。
13. **双击点赞是「两段式」**：注入层判手势，原生点按钮。判定阈值（300ms / 44px / 24px 位移）是手感值，设备触摸采样率差一点可能觉得不灵，改 `douyin.user.js` 双击那一段即可。
14. **音量是元素级的**。用户如果先在别处把系统媒体音量调成 0，App 里的音量条拉满也没声音——这时要去按音量键，不是 App 的问题。
15. **视频仍然是 16:9 放在屏幕中间**，上下用模糊背景填。这是桌面版播放器按视频原始比例铺的结果，横向视频在竖屏上必然这样；真要「一屏一视频」只能裁切，而那会把画面左右切掉，所以没做。
16. **首次安装（缓存为空）冷启动要十几秒**才露出画面。正常二次启动是 3~6 秒。
17. **幽灵触摸有 500ms 放行窗口**。原生 Dock 点击先写 `__DY_NATIVE_TAP__` 再合成 MotionEvent；这 500ms 内恰好点到旧按钮位置会漏过去。窗口再小就容易因为 `evaluateJavascript` 的异步延迟拦住自己人，属于权衡。
18. **清完 WebView 缓存后的第一次启动会慢一点**。`Cache_Data` 超 120MB 时下次启动整块删，那一次首屏要重新下载 JS/CSS（十几兆流量）。这是拿「一次慢」换掉「永远涨」，阈值给 120MB 就是为了让这件事尽量少发生。
19. **缓存上限跟着可用空间走，不是固定值**。同一个 App 在不同手机上日志里的「上限」会不一样（`min(192MB, 可用/16)`），这是有意的，不是 bug。

## 八、上机后按这个顺序验证

以下各项已在小米 24129PN74G（Android 16 / 1200×2670 / WebView 143）上逐条验过：

1. **冷启动先看到开屏页**（深色图标 + 「抖音 · 网页版」），2 秒后淡出 —— ✅ 日志 `APP 开屏页收起`
2. **打开就是推荐流里的单个视频**（不是四列网格）—— ✅ 日志 `FEED 进入推荐流 https://www.douyin.com/?recommend=1&from_nav=1`
3. **每滑一次换一个作者** —— ✅ 连滑三次：`@美团老鼠 → @阿布漫文 → @林恩环球记 → @一只黑Q`
4. **顶部沉浸、右侧铺满** —— ✅ `slideList` `top=0 padding-right=0`，`video` `0,0 900x1660`；日志 `UI: 系统栏：顶部沉浸 top=140，底部让出手势条 bottom=52`
5. **周边收干净了**：左侧导航、整条 header、右侧整列按钮、看相关、`…`、弹幕、新手引导、登录弹窗全部消失 —— ✅ `FOCUS` / `OVERLAY` 各有对应日志
6. **上滑切下一个 / 下滑回上一个 / 单击暂停** —— ✅ `adb input swipe` 连续翻页；单击后 `video.paused` 取反
7. **双击点赞**：未登录时先 toast「登录后才能点赞」再弹扫码框 —— ✅ 日志 `UI: 双击点赞：未登录，提示并拉起登录`，面板标题「登录后即可点赞」
8. **音量**：启动 `muted=true`；点音量条 66% → `muted=false, volume=0.68`；喇叭图标能静音再恢复 —— ✅
9. **全屏**：Dock 收起、系统栏隐藏、顶栏常驻可退 —— ✅ 顶栏「退出全屏」和返回键都能退
10. **「未登录 → 在页面内登录」自动弹出扫码框**，且不会被自动清理关掉 —— ✅
11. **登录态不再误报** —— ✅ 改成以页面 `loginState()` 为准（cookie 只兜底）
12. **日志文件不再每启动一个**：同一安装内复用同一份 —— ✅ `Download/DouyinShell/douyin-shell.txt`
13. **Dock 与播放区不重叠** —— ✅ 画面底 `y=2295`、Dock `2295~2614`，作者信息完整露在 Dock 上方
14. **进度条融合在交界处** —— ✅ 点 70% 处，`currentTime` 从 0 跳到 `94.9s / 135.6s`
15. **工具行四枚胶囊** —— ✅ 弹幕 `false→true`；清晰度 `智能→720P`；倍速 `1→1.25x` 且 `video.playbackRate` 同步；合集点开右侧选集面板，没有合集时胶囊置灰
16. **点赞 / 收藏会自己亮** —— ✅ 点完 `digg.data-e2e-state` 变 `video-player-is-digged`、`collect` 变 `video-player-is-collected`，Dock 上的心变 `#FE2C55`、星变实心 `#F3C24B`；翻页后自动恢复白色
17. **收藏 / 转发 / 关注** —— ✅ 收藏落库（`data-e2e-state` 就是证据）；转发弹出页面自己的分享面板（未登录显示「登录后分享给朋友」）；关注走 `feed-follow-icon` 里的可点子元素
18. **作者头像和关注胶囊** —— ✅ 头像 CDN 直连下载成功（100×100 图，30dp 圆画）；关注是实心红底白字
19. **左下角字号** —— ✅ 作者名 28px / 文案 24px（页面默认约 15px），描述铺到右边缘再折行，字体强制 `system-ui`
20. **评论抽屉把视频顶上去** —— ✅ 点击后视频 `0,850`、`dy-sheet-open=true`，抽屉 `850,850`，五五分严丝合缝；关闭后视频恢复 `1699`
21. **旧按钮位置点不动了** —— ✅ CDP 真触摸点 `feed-comment-icon`（`opacity:0`）后评论区仍关闭，日志 `RAIL 旧按钮的幽灵触摸已拦掉`；Dock 点赞仍亮 `#FE2C55`
22. **静态资源磁盘缓存** —— ✅ 第二次冷启动 `资源命中` +291、`资源回源` 仅 +2
23. **评论区像手机版** —— ✅ 顶部只剩「评论」一个标签（AI / TA的作品 / 相关推荐 已去掉），「大家都在搜」整行摘掉；正文 32px、昵称 27px、时间/回复 24px、输入框 26px，右侧无悬浮数字或「评论 ✕」
24. **评论开合稳定** —— ✅ 连点 5 次 Dock 评论按钮：`开→关→开→关→开`，视频高度在 `850 / 1699` 间正确切换；在评论区右滑也能收起
24.1 **评论列表能滑** —— ✅ 物理坐标 `600` 竖线上滑 `900 → 1253`、下滑 `1253 → 898`；评论内的赞可以点（`47 → 48`）、点输入框能起键盘（`DraftEditor-content` 获得焦点）
24.2 **侧滑不误伤纵向滚动** —— ✅ 斜着滑列表不会被判成收起（要 `dx > 60dp` 且 `dx > |dy| × 1.8`）；确认收起后日志是「关评论区 via 侧滑返回」
24.3 **点抽屉右上角 × 能关干净** —— ✅ 连开三次再点 ×：`open=false`、`.dy-sheet` 剩 0 个、`slideList` 回到 `1687`；下半屏不再留 50vh 空抽屉或另一条视频。点 Dock 按钮关、评论区右滑关同样干净（在 21:11 版本验证）
24.4 **关完马上又开不被误拆** —— ✅ `closeComments()` 后 120ms 再 `comments()`：`sheetTimer` 被撤销、页面不再 toggle，高度从当时的中间值直接过渡回 `850`（看起来是「滑一半又弹回来」）
24.5 **点 × 后立刻按返回 / 点 Dock 不会重开** —— ✅ 点 × 后 150ms 内再点 Dock：`.dy-sheet` 仍在、`dy-sheet-open=false`，收起动画照旧走完；页面若没吃下延迟的那一刀，日志会补一条「页面没关掉，补一次 toggle」，最终面板宽度回到 0
24.6 **开合动画时长** —— ✅ 25fps 录屏逐帧：打开 7 帧（约 280ms）、关闭 7 帧（约 280ms）；抽屉 top 与视频区下沿**逐帧相等**（`sheet 851/fav 851`…`sheet 1688/fav 1688`），没有一帧错位
24.7 **开合不露出下一条视频** —— ✅ 1080 帧里下半屏始终是抽屉或视频区；点开那一百毫秒由 `hideNextSlide()` 补住，关闭全程 `next - sheet = 12px` 恒定
24.8 **按下响应** —— ✅ 真机点 Dock 评论按钮：约 150ms 起动画（原来要等 1 秒轮询，最坏 800ms）
25. **进度条连续** —— ✅ 30fps 录屏逐帧量红色线头：1x 平均 1.7px/帧、1.5x 3~4px/帧，**0 次回退、0 次卡住**；250ms 真值校正肉眼看不到
26. **侧滑回桌面不杀后台** —— ✅ 系统左缘侧滑后回桌面，`pid` 不变，日志只有 `APP 返回桌面（保留后台，不 finish）`，没有 `Activity 销毁`；再点图标是 `task brought to the front`，同一视频从原进度继续
27. **评论空间利用率** —— ✅ 搜索行隐藏后列表起点 `976 → 946`，正文从 17px 抬到 32px，同屏仍能完整显示 2~3 条长评论，视频:评论保持 `850 / 850`
28. **缓存搬到 cache 目录** —— ✅ 升级后首启日志 `已清掉旧缓存目录（files/media-cache），释放 358.04MB`；`files/` 剩 4KB，新目录 `.../cache/media-cache`，上限 `192.00MB`
29. **重排后的评论区（22:5x 版本）** —— ✅ 抽屉 `712,986`（42/58），视频区 `714`；标题左对齐头像、关闭 × 仍在抽屉内 `(856,717,36×36)`；头像 84px 圆形（页面原本 `opacity:0` + `40px!important` 导致看不到 / 被裁，均已按下）；正文 34px、表情 34px、昵称 65% 白；时间在左、回复·分享·点赞在右同一行；「展开 N 条回复」28px 蓝色；输入框 92px 胶囊；列表底部渐变淡出
30. **58vh 后不再露出下一条** —— ✅ 打开 / 关闭各逐帧采样 80+ 帧，全程只有当前一条 `feed-item` 可见；翻页后再开：`dy-active-slide` 恰好 1 个、其余 7 条被藏；点 × / `closeComments()` 后 `.dy-sheet` 与标记都归零
31. **回归** —— ✅ 翻页、再开评论、点 × 关闭、侧滑回桌面（pid 不变、task 回前台）均正常；`node --check` 通过，`./build.sh` 出的 APK 约 172KB
29. **系统「清除缓存」能清掉** —— ✅ 缓存位于 `cache/` 下，设置里点『清除缓存』/ 系统低存储驱逐都能释放（旧的 `files/` 做不到，这是本次改动的核心动机）
30. **WebView 缓存有天花板** —— ✅ 人为塞 150MB 到 `Cache_Data` 后冷启动：日志 `清掉 WebView 响应缓存 157.6MB`，目录回落到 `191KB`；冷启动 `TotalTime` 只从 `189ms → 256ms`

32. **翻页中开评论不再鬼畜**（23:0x） —— ✅ `next()` 后立刻调 `comments()`，逐帧采样 114 帧：`big-jump`（相邻帧位移 >120px）从 4 降到 0、黑屏帧 0（修前 19）、抽屉 parent 恒为 `slideList`；shell top 从 1500 单调收敛到 714，起段两次大跳都在正常动画区间。标记跟随 active（mark=active），250ms 轮询补住套壳后才切 active 的竞态。关闭（× / `closeComments()`）后壳/标记/隐藏节点全归零、wrap 归位，再开正常；侧滑 pid 5859 不变。APK 约 176KB
31. **日志不累积** —— ✅ `Download/DouyinShell/` 稳定在 12 个文件 / 1MB，重启后不再新增「(5)」，一直复用 `douyin-shell (4).txt`
32. **缓存命中没退化** —— ✅ 改完后同一次会话 `资源命中 310 / 未命中 20`，累计省 `44.17MB`；推荐流正常播放、登录态 `in`
33. **状态栏黑块修复**（23:1x） —— ✅ 冷启动后 `getComputedStyle(#douyin-header).display === 'none'`、`__dyHidden=1`；状态栏 y=30 一行 6 个采样点全为视频画面色、无 `#161823` 黑带。翻页后 header 与壳仍隐藏；开评论 / `closeComments()` 后状态栏保持统一；侧滑回桌面 pid 7924 不变。APK 约 176KB
34. **开合评论不闪了**（23:2x） —— ✅ 逐帧采样开 85 帧 / 关 111 帧：video 视觉 y `maxAbs=0`、跳变 0（修前 20+ 帧甩到 -2300）；抽屉滑入 1507→714、滑出 1687→2673 完整动画保留。开→关→再开→点×（sheets/mark/pin 全归零）→翻页中开→侧滑回桌面 pid 4641 不变。APK 约 176KB
35. **评论区滑动不再切视频**（23:4x） —— 待真机确认：① 评论列表内上/下滑只滚评论、视频 `currentTime` 不变；② 滚到顶/底继续滑不再翻页；③ 视频区上/下滑仍能正常翻页；④ 评论内点赞、输入框聚焦不受影响。日志侧 `SWIPE 评论区手势，交给原生滚动` 应在评论内滑动时出现，且没有 `WHEEL 点「下一个」按钮`。`node --check` 通过，APK 约 180KB
28. 关掉重开 App（真正杀进程）登录态是否还在 —— 未实测（需要真账号）

## 九、上机调试修掉的东西

| 问题 | 影响 | 处理 |
| --- | --- | --- |
| 没实现 `onShowFileChooser` | 网页版投稿 / 换头像点上传**毫无反应** | 已补，`ValueCallback` + `onActivityResult` |
| 进程被回收后不恢复页面 | 切后台回来白屏 | `saveState` / `restoreState` |
| 没处理 `touchcancel` | 手势被系统打断后，下一次 touchend 误判 | 已加重置 |
| 滑动判定卡死 700ms | 慢速滑一下没反应，像失灵 | 改成「速度 + 距离」双阈值 |
| `dispatchWheel` 先记时间再判断 | 冷却期内仍吞掉时间戳 | 改为派发成功才记 |
| `viewportMode` 只有两档且 meta 混写 `initial-scale` | 部分机型缩放错位 | 拆成 `meta` / `meta-scale` / `css` 三档 |
| `window.open` / 下载无兜底 | 点击静默失败 | 加了下载拦截提示 |
| 断言「document-start 注入」 | 文档与实现不符 | 文档改为如实描述 |
| `setBackgroundResource` 覆盖胶囊底色 | 控制条按钮变成无底色的裸图标 | 改成 `RippleDrawable`，把底色作为 content 传进去 |
| 淡出后仍可点击 | `alpha=0` 的按钮会吃掉触摸，等于页面上盖了 5 个隐形按钮 | 淡出结束设 `INVISIBLE` |
| `GradientDrawable.setCornerRadius` 收的是 px | 高密度屏上圆角变成直角 | 统一从 dp 换算 |
| 视口日志按 `screenW` 去重 | 切模式后 designWidth 变了却不记录 | 签名里加入 `designWidth` |
| **卡片选择器写成 `a[href*="/video/"]`** | 精选页卡片是带 href 的 `<div>`，真机上命中 0 个，永远进不了推荐流 | 改成 `[href*="/video/"]` + 尺寸过滤 |
| **点卡片 + 1.5s 兜底跳转** | 前端路由不生效时兜底跳到整页 `/video/<id>`，下方挂着「推荐视频」列表，不是沉浸式 | 当时改成拼 `/jingxuan?modal_id=<id>`；**后来发现那个 modal 只播同一作者，已整体换成 `?recommend=1`** |
| **在 `?modal_id=` 上重复导航** | 播放器挂载要 2~3 秒，期间每轮轮询都重跳一次，页面永远加载不完 | 已在目标地址就只等，不再跳 |
| **`WheelEvent` 合成翻页** | 抖音校验 `event.isTrusted`，合成事件被丢弃，「能看不能刷」 | 改点 `data-e2e="video-switch-*-arrow"` |
| **翻页按钮点错屏** | swiper 有 3 组 prev/next，点了不在视口内的那组等于没点 | 按 `getBoundingClientRect()` 过滤可见的 |
| **没有系统栏留白** | targetSdk 35+ 强制 edge-to-edge，抖音 tab 栏压在状态栏上、进度条压在手势条上 | `setOnApplyWindowInsetsListener` 做内边距 |
| **`img[src*="aweme-avatar"]` 判已登录** | 视频作者的头像命中同一条选择器，一进页面就假报「登录成功」 | 只认 `[data-e2e="user-avatar"]`，拿不准返回 unknown |
| **JS 桥参数个数和 Java 签名不一致** | 页面按 6~7 个参数调，Java 只声明 3 个，Android 直接抛错，被页面 `try/catch` 吞掉，症状是「进度条彻底不动」 | 两侧参数表固定，改一侧必须同步另一侧 |
| **合集入口按「视口内」过滤** | swiper 预挂下面三屏，那些卡片的信息区 `top=3000`，拿去合成点击必然落空，还会假报「有合集」 | 先认同一张 `dySwiperSlide` 里的，再要求中心点在视口内 |
| **合集入口用 `el.click()`** | 和点赞一样只认 `isTrusted`，点了毫无反应 | 并入 `point('collection')`，走原生合成触摸 |
| **登录弹窗关闭按钮按「贴右边缘」找** | 「登录后即可点赞」这个弹窗的 `#login-panel-new` 比里面的对话框宽 200px，按右边缘过滤一个都匹配不到，弹窗关不掉 | 改成「顶部那一排里最靠右的那个」 |
| **右侧点赞/评论用 `visibility:hidden` 藏** | 元素退出命中测试，合成触摸穿到 `<video>` 上，点赞变成暂停 | 只能用 `opacity:0`（+ 压高度收缝隙） |
| **沉浸层里没有登录入口** | 页头「登录」按钮被沉浸层盖住，用户在播放页无法登录 | 用户点「在页面内登录」时借点赞入口弹出登录框 |
| **日志文件名 `.log` + MIME `text/plain`** | MediaStore 存成 `.log.txt`，下次按原名查不到，每启动多一个「(1)」 | 名字对齐成 `.txt`，精确名优先查找 |
| **弹幕按 `.danmakuContainer` 隐藏** | 那只是评论区输入区外壳，真正飘字的 `.danmu` 层有 `pointer-events:none`，反查命中的是 video，容易误判「烧进视频」 | 选择器改成 `[class~="danmu"]` |
| **冷启动直接露网格** | 用户看到的第一眼就是「精选」九宫格 | 加黑幕，进推荐流后淡出，另有两道超时兜底 |
| **底部控件贴着屏幕最底边** | xgplayer 那一排（播放/时间/倍速/清晰度/音量/全屏）落在圆角 + 手势条里，两头被裁、点不准 | 整排 `display:none`，底部换成原生「按钮条 + 常驻进度条」两层 |
| **头像上方的 AI 入口和上下箭头** | 纯视觉噪音，还挡着播放器右侧 | `visibility:hidden`（不能用 `display:none`，箭头要留着被脚本点） |
| **`el.click()` 点不动点赞/评论** | 这两个按钮在 xgplayer 里只认 `isTrusted` 输入，脚本派发的事件被丢弃 | 页面报坐标 + 原生 `dispatchTouchEvent` 合成真实触摸 |
| **「点完赞没反应」** | 未登录点赞会弹「登录后即可点赞」，被 600ms 一轮的遮挡层清洁工当场关掉 | 有触摸就给 12 秒豁免；走登录流程时暂停 4 分钟 |
| **进度条靠页面自己那根** | 换层级 / 开评论区时宽度乱跳，还压在圆角上 | 原生重画：2dp 细线 + 游标 + 时间，页面每 250ms 推一次秒数 |
| **底部按钮做成悬浮胶囊** | 压着画面，作者名和话题标签从半透明底里透出来，字叠字 | Dock 改成在纵向 LinearLayout 里真实占位，WebView 高度自动让出这一条 |
| **用精选 modal 当「推荐流」** | 那个 modal 的「下一个」只在同一作者 / 同一合集里循环，刷十条全是同一个人；日志还完全正常（时长、封面都在变） | 落地页改成 `/?recommend=1&from_nav=1`，精选 modal 降级为兜底 |
| **评论抽屉只 `bottom:0`，不压视频** | 抽屉浮在整屏视频上，下半画面被遮住；用户要的是「把视频挤上去」 | 给 `feed-active-video` 加 `dy-sheet-open` 压到 `50vh`，抽屉用 `top:100%` 接在下面；父级 `overflow:hidden` 必须一起放开，否则抽屉被裁掉 |
| **关评论点抽屉的 ×** | × 只做视觉淡出，页面内部仍认为评论开着；下次点评论按钮变成 toggle 关闭，表现为「关掉之后再也打不开」 | 关闭也点**页面评论按钮**（toggle），让页面状态和原生一致；`__DY.sheetOpen()` 每秒回读同步 |
| **抽屉的旧壳带着 `dy-sheet` 类残留** | `patchCommentSheet` 一见有类就早退，`setSheetVideo(true)` 永远不再执行，表现为「评论开了、视频还是全屏」 | 有类但 `sheetApplied=false` 时也要补调 `setSheetVideo(true)` |
| **缓存放 `getExternalFilesDir()`** | 系统把它算「应用数据」而不是「缓存」，设置里的『清除缓存』、管家、低存储驱逐**全都碰不到**；实测两天吃满 375MB，用户看到「清不掉的膨胀」 | 全部搬到 `getExternalCacheDir()` / `getCacheDir()`；升级首启把旧目录整棵删一次（释放 358MB） |
| **缓存上限写死 512MB** | 手机只剩 1GB 时它照样敢吃到 512MB，用户既不知道在哪也清不掉 | `min(192MB, 可用空间/16)`，可用 <1GB 时降到 32MB；切后台（`TRIM_MEMORY_UI_HIDDEN`）再收一次 |
| **以为 WebView 缓存在 `app_webview/`** | 实际在 `cache/WebView/Default/HTTP Cache/`（`Cache_Data` 181MB + `Code Cache` 17MB）。按 `app_webview` 去找只统计到 0.2MB，白做 | 路径改成 `getCacheDir()/WebView/...`；`Code Cache` 阈值 40MB、`Cache_Data` 120MB，分开判 |
| **运行中删 WebView 缓存目录** | Chromium 自己的索引和文件句柄还开着，容易导致首屏失败一次 | 只在**启动窗口**里删（WebView 还没 `new` 出来），实测代价 +67ms |
| **缓存挪进 cache 后不再 `mkdirs`** | 系统低存储会连 `cache/media-cache` 目录一起删掉，下一次写入直接失败、静默不缓存 | 落盘前统一走 `ensureDir()` |
| **右侧竖列只藏按钮不藏数字** | 数字（「1.3万」「1425」）是按钮的兄弟节点，不在 `opacity:0` 范围内，糊在画面上成了「评论 ✕」悬浮标签 | 遍历按钮内「无 data-e2e、无 svg、高 <70px、含数字」的子节点单独藏；**不能藏整个按钮块**，否则脚本点不着 |
| **合成触摸点完按钮留下 semi-tooltip** | 「评论 ✕」的 hover 提示挂在 `semi-portal` 里，抽屉关了也不消失 | `.semi-tooltip-wrapper{display:none}` |
| **系统左缘右滑直接 finish Activity** | targetSdk 36 默认走预测性返回，Activity 的 `onBackPressed()` 根本收不到；以前 super 里一 finish，WebView、登录态、缓存、播放进度全没了 | 注册 `OnBackInvokedCallback` 接管手势返回，最后一档改成 `moveTaskToBack(true)`，**绝不 finish**；验证 `pid` 不变、日志无「Activity 销毁」 |
| **`dispatchKeyEvent` 接管返回键** | DOWN/UP 只吃一个仍会落到 WebView 触发 goBack；且系统手势会另发一次返回，越拦越乱 | 不走 key 事件；统一由 `handleBack()` 处理（全屏 → 登录 → 评论 → 网页历史 → 回桌面） |
| **`onTouch` 里 DOWN 返回 true 接管侧滑** | 评论抽屉正好铺在下半屏，那一块是网页的滚动列表。DOWN 一旦返回 true，整条手势链都归原生，WebView 收不到事件 —— 表现是「评论滑不动、评论点不了赞、输入框点不进去」，但上滑仍能滚（起点落在列表内）极具迷惑性 | **DOWN 一律返回 false**，先把事件交给 WebView；等 MOVE 里 `dx > 60dp && dx > \|dy\| × 1.8` 才认定侧滑并收起。纵向滚动 `dx≈0`，永远不误判 |
| **关评论只清 `dy-sheet-open`、不清 `.dy-sheet`** | 两个类是分开生效的：`.dy-sheet` 自己就带 `position:absolute / height:50vh / 深色底`。点抽屉右上角 × 后页面把面板摘走，`dy-sheet-open` 掉了，但 `.dy-sheet` 还钉在旧 wrap 上 —— 下半屏留一条 50vh 空抽屉，用户看到的就是「评论区那块被下一条视频占住」 | 抽出 `dropSheet()`：摘类 + `setSheetVideo(false)` + 清 `sheetApplied` + 把 `__DY_WANT_SHEET__` 设 false（防轮询套回去）。三条关闭路径（×、Dock 按钮、侧滑）全部走它；`patchCommentSheet` 的 `!frame` 分支兜底 |
| **`setSheetVideo(false)` 在拿不到 `activeVideo()` 时直接 return** | 只 toggle 了 class，播放器内联的 `50vh !important` 留在原地，视频恢复不成 100vh | 高度复位不要依赖当前视频存在：`dropSheet` 之后由 `fillPlayer()` 按 `dy-sheet-open` 重新对齐（`__dyPBox` 那套逻辑本来就在） |
| **延迟拆壳没有守卫** | `closeComments()` 让 React 先跑一帧再拆（`setTimeout 30`），这 30ms 里用户可能又点开了评论 —— 定时器照跑，把新壳拆掉、`want` 写回 false，评论卡在「半开、无壳、视频 100vh」 | 加 `__DY_OPEN_SEQ__`（`comments()` 每打开一次 +1）：`dropSheetSoon` 在回调里比对序号，变了就放弃拆壳。**不能用 `__DY_WANT_SHEET__` 当守卫**——点 × 时它仍是 true，那样 × 永远拆不掉 |
| **`!frame` 兜底把「还没渲染」当成「已关闭」** | 刚点开评论时面板还没挂载，`commentFrame()` 同样返回 null；若顺手 `dropSheet`，`want` 被清成 false，面板随后渲染出来也不会再套壳（慢机上一秒多的窗口） | 兜底条件只认 `sheetApplied`（之前真的套过壳），不看「有没有 `.dy-sheet`」，也不看 frame 是否存在 |
| **页面已关还去点评论按钮** | `closeComments()` 点的是页面 toggle；用户先点 × 关掉后，原生 `commentOpen` 还要最多 1 秒才更新，这期间按返回 / 点 Dock 会再 toggle 一次，把评论重新打开 | `closeComments()` 入口先用 `commentFrame()` 判断面板还在不在，不在就只同步壳、不打 toggle |
| **进度条按 `1 / duration` 外推** | 1.5x 时 `currentTime` 走得比墙钟快，外推永远追不上，每 250ms 被真值拽一下 | 外推速率改成 `playbackRate / duration`，并把倍速一起过桥 |
| **真值比外推略慢时重设 anchorAt** | 旧逻辑保留旧 `anchorFrac` 却刷新 `anchorAt`，等于每 250ms 往回退一小格 | 真值略慢时把 `anchorFrac` 设成当前显示值（停在原地等它追上来）；只有差值 >0.8s 才当作 seek 直接对位 |
| **评论字号只写 `.dy-sheet span`** | 正文是 `div > span` 的嵌套结构，17px 只放大到 tab、头像和「全部评论」，正文仍是 12~17px | 按内容分别设：正文 32px、昵称 27px、时间/操作 24px、tab 26px、输入 26px |
| **隐藏「大家都在搜」写成 `[class*=comment-head]`** | 会连 `comment-header-inner-container`（「全部评论」那行）一起 `display:none` | 精确匹配 `.comment-head` 和 `[class*="comment-header-with-search"]` |
| **只改 CSS 想压 `xg-video-container`** | `fillPlayer()` 写的是内联 `height:100vh !important`，样式表的 `!important` 盖不过内联 `!important` | 在 `setSheetVideo()` 里用 JS `style.setProperty(...,'important')` 改回去；换片时 `fillPlayer()` 也要跟着读 `dy-sheet-open` |
| **把旧按钮改成 `visibility:hidden` 或 `pointer-events:none`** | 合成触摸穿到 `<video>` 变暂停，或者脚本 `.click()` 也失效 | 保留 `opacity:0`，改为在 `document` 捕获阶段只拦真手指事件；原生 Dock 点击另开 500ms 白名单 |
| **缓存响应式丢掉 CORS 头** | 播放器判跨域失败，1 秒 7 次 `bytes=0-` 从头重下，比不缓存更费流量 | 命中/回源都补 `Access-Control-Allow-Origin` 等头 |
| **静态资源只按 `Content-Length` 读** | 抖音 JS chunk 多是 chunked，`Content-Length=-1`，全被跳过，等于没缓存布局 | 长度未知时流式读到 6MB 上限 |
| **构建脚本会自动安装** | `build.sh` 只打印 `adb install` 命令不执行，改完直接测还是旧包 | 每次 `./build.sh` 后要手动 `adb install -r douyin-web-shell.apk` |
| **`?recommend=1` 不带 `from_nav=1`** | 服务端 302 回 `/jingxuan`，等于又回到了九宫格 | 两个参数一起带；`isFeedPage()` 认 `recommend=` |
| **双击点赞靠页面自己** | 桌面版是给鼠标 `dblclick` 的，手指双击被当成两次单击（暂停 → 播放），合成 `dblclick` 页面也不认 | 注入层判「300ms / 44px」，交给原生合成触摸点右侧那颗心 |
| **双击判定不看位移** | 连着两次快滑会被当成双击，翻页翻着翻着就点了赞 | 抬手位移 > 24px 直接不算点击 |
| **全屏只藏系统栏不藏 Dock** | Dock 是真实占位，留着画面就永远少一截 | `setFullscreen` 里 Dock `GONE` + 退出胶囊放顶栏 |
| **全屏用页面的 `xgplayer-page-full-screen`** | 那是浏览器 Fullscreen API，会把 `<video>` 提成原生 `customView`，Dock / 手势 / 注入层全没了 | 原生做：`FLAG_FULLSCREEN` + Dock 收起 + `refreshLayout()` |
| **音量想用 `AudioManager`** | 改的是全局音乐音量，调一次视频连铃声一起变 | 改 `video.volume` / `muted`，元素级、可静音、退出不影响系统 |
| **开屏页先加进 root** | 顶栏浮层后加，结果「未登录 / 推荐 / 完整」那排胶囊透在开屏页上 | 开屏页放到 `buildChrome()` 之后再加，压在最上面 |
| **搜索框按 `<input placeholder="搜索">` 找** | 推荐流的搜索框是个 `<button>`，选择器命中 0 个，顶栏一直露着 | 精简模式改成按 `<header>` 标签 + 几何特征收整条顶栏 |
| **视频右侧空一条 60px** | 以为 WebView 没铺满，其实是 `slideList` 自己的 `padding-right:60px`（给竖列按钮留的位） | 按钮全进 Dock 后 `padding-right:0 !important` |
| **视频上方空 56px / 状态栏一条黑带** | header 藏了，但它外面还有一层 `fixed` 外壳（高 56 + 深色底）和一层 `padding-top:56px`。原来只收了 header 的**直接父级**，漏了再高一级的 fixed 壳 —— 状态栏右边是 `#161823` 黑带、左边 72px 露出视频，颜色割裂 | `flattenHeader()`：按「header 的父级」+「padding-top > 4」收留白，再从父级往上 4 层找贴顶的 fixed 纯色壳一并 `hideEl()`（宽过半、高 24~96） |
| **`setColorFilter(on ? ACCENT : null)`** | 三元里有 `null`，编译器挑 `setColorFilter(int)` 再自动拆箱，传 `false` 时 NPE 把整个 App 带走 | 拆成 `if/else`，`else` 走 `clearColorFilter()` |
| **登录态只看 cookie** | 设备上留着过期的 `sessionid`，一启动就报「已登录」，界面显示已登录但点赞毫无反应 | 以页面 `loginState()` 为准，cookie 只兜底；轮询也不再信 cookie |
| **`loginState()` 按 rect 找「登录」按钮** | 整条 header 被 `display:none` 之后 rect 全是 0，永远判成 unknown | 不看几何只看 DOM：header 里有「退出登录」= 已登录，有「登录」= 未登录 |
| **点赞状态写成 `video-player-digged`** | 实际值是 `video-player-is-digged`，猜错一个字，Dock 上的心永远不亮 | 认准 `data-e2e-state` 的四个真实值，见第三节 |
| **判断「已关注」靠 `feed-follow-icon` 是否存在** | 那颗 `+` 是 hover 才渲染的徽标，元素常驻、高恒为 0，结果所有视频都显示「已关注」 | 不做状态判断，只做交互反馈：点一下变「已关注」，翻页重置 |
| **「看相关」按标记早退** | 全屏 / 切清晰度后播放器整块重建，新元素没标记，右下角又冒出来 | 去掉早退，按「元素还在、rect 不为 0」判断，每次扫最多处理 4 个 |
| **头像按 `<img>` 找** | 抖音用 `span + background-image` 画头像，`img` 一个都没有，Dock 上永远是个灰圆 | `pickImageUrl()` 连背景图一起翻 |
| **全屏顶栏 4 秒淡出** | Dock 已经收了，「退出全屏」是唯一出口，淡掉之后要点两下才退得出去 | 全屏状态下 `setChromeVisible` 不排自动隐藏 |
| **打开态同时写 `min/max-height:50vh`** | 这两个属性不参与 `height` 过渡，会在过渡第一帧就把高度钳到 50vh，动画等于没写（表现为「瞬间跳到位」） | 只写 `height:50vh`，min/max 压成 `0 / none` |
| **给嵌套的 `100%` 高度也挂 `transition`** | 同一段高度变化在每一层各跑一遍，`sheet` 的 `top:100%` 落后外层 300ms，收起尾巴拖到 620ms | 只给有显式高度的层级（`slideList` / `feed-item` / `.dy-pbox`）挂过渡，`feed-active-video`、`sliderVideo` 每帧跟着父级算 |
| **收起时以为高度由我们控制** | `dy-sheet-open` 一撤，我们的 `!important` 就没了，页面自己那套内联 px 动画接管，600ms 才收完还带回头顿一下 | 收起期间加 `dy-sheet-closing` + `--dy-sheet-h`（打开前量到的真实高度，1688 而**不是** 100vh）把它钉住，700ms 后等页面动画跑完再撤 |
| **原生用 1 秒轮询的 `commentOpen` 判 toggle** | 刚点开评论、1 秒内再点一次，`commentOpen` 还是 false，于是又调一次 `comments()`，而页面按钮是 toggle —— 刚打开的评论区被当场关掉 | `onComment()` 每次按下实时问 `__DY.sheetOpen()`（套壳前那几十毫秒用 `want && commentFrame()` 兜住），多一次往返换个永远正确 |
| **点 × 后页面立刻卸掉面板** | 我们手上只剩一块空壳，画面变成「评论瞬间消失、视频自己长出来」，这就是「关闭动画生硬」 | 关闭动作一律推迟到动画结束：`window` 捕获阶段 `stopPropagation` 拦住 ×，`closeComments()` 则把 toggle 交给 `pageClose` 回调；滑完再点，谁也看不见 |
| **翻页未停时点开评论 → 鬼畜抖动** | 抽屉原本挂在 `feed-active-video` 下沿，那条的父级是 swiper 的 translate 层，翻页/回弹时每帧在变（`y` 从 726 弹到 −420 再回 0）。抽屉跟着上下抽；更糟的是套壳瞬间 active 还没切完，标记打在正滑出屏的旧条上，CSS `:not(.dy-active-slide)` 把新条藏掉 —— 实测连续 19 帧看不到任何视频 | 套壳时把 wrap **reparent 到 `[data-e2e="slideList"]`**（`position:absolute`、`y=0`，不吃 swiper 位移），`top:100%` 锚在稳定的视频区下沿，视觉不变但和位移解耦；拆壳时 `appendChild` 归位。另加 `syncActiveSlide()` 每帧跟随 active（pump + 250ms 轮询），标记切换才改 DOM。逐帧复测：big-jump 0、黑屏 0、shell top 单调收敛到 714 |
| **开 / 关评论时画面闪烁**（23:2x） | 视频区高度带 `transition:height 300ms`，item 的 `offsetTop` 逐帧漂移，而 swiper 的 wrapper translate 是瞬时跳的。video 视觉 y = translate + offsetTop，两者对不上 → 逐帧实测从 0 甩到 −2300 再弹回，持续 20+ 帧。这是「鬼畜」之外的第二种抖：抽屉已经解耦了，但**视频画面自己**在抽 | 两件事一起做：① 开合期间取消 item/slideList/.dy-pbox 的 height 过渡（高度瞬时到位，swiper 一次算准）；抽屉改用自己的 `translateY(100%)↔0` 做滑入滑出，顺滑感不丢。② 加**画面锁定器** `pinVideo()`：从点评论那一帧起，每帧读 active video 的视觉 y，在它自己身上叠反向 `translateY`（rAF + 帧尾 setTimeout + 监听 wrapper style 三重触发），把画面钉在 y=0；拆壳时归零。逐帧复测开、关各 90+ 帧：video `maxAbs=0`、跳变 0，抽屉仍完整滑动 |
| **在评论区滑动却切了视频、评论还滑不动**（23:4x） | `touchend` 靠 `scrollableAncestor(gesture.target, dir)` 判断「这一下是在滚评论还是在翻视频」。评论项是 semi-ui + React 的深层结构，从 `<span>/<svg>` 走到 `[data-e2e="comment-list"]` 会越过原来的 40 层遍历上限；滚到顶/底、落在 header 或空白区时方向判断也返回 null。任一情况都会掉进 `dispatchWheel()` → 点翻页箭头切走视频，swiper 重排把正在滚的评论打断 —— 两个症状其实是同一个根因 | ① `scrollableAncestor` 上限 40 → 120；② `touchstart` 记下 `gesture.inSheet = e.target.closest('.dy-sheet')`；③ 新增 `pointInSheet(x,y)` 用起点坐标比对抽屉矩形（`dy-sheet-open` 在场才算），`touchend` 里两条路径取或，**命中就一律放行给浏览器原生滚动、绝不翻页**。`comment-list` 本来就是 `overflow-y:scroll` 的真容器（实测 `scrollHeight 2293 / clientHeight 692`），passive 监听 `return` 之后浏览器自己会滚，无需注入层介入 |

## 十、2026-11-20 收尾：进度条 / 评论字号 / 返回保活

1. **进度条**：`pushProgress` 从 500ms 提到 250ms，秒数不再四舍五入到 0.1s，缓冲时 `playing=0`；
   `SeekStrip` 外推速率改为 `playbackRate / duration`，最大前瞻收到 0.6s，真值略慢时**不回退**。
   30fps 录屏逐帧量线头：1x 平均 1.7px/帧、1.5x 3~4px/帧，0 次回退。
2. **评论字号**：实测 1 CSS px 只落到约 0.41dp，17px 等于 7sp。正文 32px / 昵称 27px / 时间与操作 24px /
   tab 26px / 输入 26px；隐藏「大家都在搜」整行，列表起点从 976 提到 946。视频:评论仍 50/50。
3. **侧滑保活**：targetSdk 36 的预测性返回不再走 `onBackPressed`，改为注册 `OnBackInvokedCallback`，
   最后一档 `moveTaskToBack(true)`，不 `finish`、不 `destroy` WebView。实测侧滑后 `pid` 不变，
   重新打开是 `task brought to the front`，同一视频从原进度继续。
4. **评论抽屉开合动画**（22:15 之后）：原来是一刀切 —— 类一换，视频 100vh↔50vh 瞬间跳，抽屉
   同时瞬移到新位置。现在整段动画只由「视频区高度」一条属性驱动，抽屉的 `top` 恒挂在视频区下沿。
   实测开 280ms、关 280ms，抽屉 `top` 与视频区下沿逐帧相等；按下到起动画从最坏 800ms 降到约 150ms。
   顺带修掉三处会破坏动画的东西（详见坑表）：`min/max-height` 把过渡钳死、嵌套 `100%` 各跑一遍
   过渡导致拖尾、收起时页面自己那套 600ms 内联动画抢走控制权。
5. **评论区重排 + 高度改 42/58**（22:5x 之后）：五五分一屏只有两条半评论，提到 58% 后有三条半；头像从 16dp 放到 34dp 并修掉页面把 `span` 的 `opacity` 压成 0（头像一直不显示）和 `width:40px!important`（84px 的 img 被 42px 的圆形容器裁掉）两个坑。昵称降到 65% 白、正文提到 14sp，时间/操作并成一行，「展开回复」从 5sp 提到 11sp 蓝色，输入框改成 36dp 胶囊，列表底部留一个输入栏高度的余量并铺渐变。同时把开合期间「露出下一条」的兜底从藏一个兄弟改成藏全部 + CSS 标记。
   **刻意没动**：右上角 × 所在的 46px tab 行。试过压平高度和重新定位 ×，结果 × 被顶到抽屉外面（它的 containing block 不是那一层），已回退。
6. **翻页中开评论的鬼畜抖动**（23:0x）：抽屉 reparent 到 `slideList` 层，与 swiper 位移解耦；`syncActiveSlide()` 每帧 + 250ms 轮询跟随 active 条。实测 `next()` 后立刻点评论：big-jump 0、黑屏 0 帧、shell top 单调收敛；关闭（× / `closeComments()`）后壳、标记、隐藏节点全归零，reparent 的 wrap 归位，再开正常。
7. **开合评论的画面闪烁**（23:2x）：视频区高度的 300ms 过渡让 item `offsetTop` 与 swiper 的瞬时 translate 错帧，video 视觉 y 从 0 甩到 −2300。改为开合期间取消高度过渡（瞬时到位）+ 抽屉用自身 transform 做动画 + `pinVideo()` 每帧把 video 视觉 y 反向补偿到 0（rAF / 帧尾 setTimeout / MutationObserver 三重触发，拆壳归零）。逐帧复测开、关各 90+ 帧：video maxAbs=0、跳变 0，抽屉滑入 1507→714、滑出 1687→2673 完整保留。开→关→再开→点×→翻页中开→侧滑保活全通过。
7. **状态栏黑块**（23:1x）：页面顶栏比 header 高两级的 fixed 空壳（`#douyin-header`，高 56、底色 `#161823`、z=502）没被 `flattenHeader()` 收掉 —— 它从 x=72 CSS 起覆盖状态栏右侧，左侧露出视频，形成一块颜色割裂的「黑色东西」。`hideEl()` 收掉它后整条状态栏统一显示视频画面，图标仍清晰。翻页、开评论后不复现。

## 十一、合规与免责声明

### 项目立场

只做「用浏览器访问公开网页」这一件事：不伪造签名、不改接口、不绕过付费、不批量抓取。抖音页面如果自己改了检测策略，改动应落在 `douyin.user.js`，而不是往逆向方向走。本项目不是抖音的客户端替代品，只是一个带原生控制条的 WebView 壳——登录、推荐流、互动全部走官方网页自身，账号与内容归平台所有。

### 非官方声明

本项目由个人独立开发，**与抖音、字节跳动（ByteDance）及其任何关联公司不存在隶属、合作、授权或背书关系**。「抖音」「Douyin」等名称及页面内容仅用于说明本项目所访问的公开网页对象，相关商标、标识、界面与内容的一切权利归原权利人所有。本项目不使用上述商标作为自有品牌，也不分发任何官方应用或其组件。

### 使用责任

- 本项目仅供个人学习与技术研究使用，请遵守抖音网站的服务条款与当地法律法规；
- 使用者应自行判断其使用方式是否合规，作者不对因使用本项目产生的账号风险、内容风险或法律责任负责；
- 项目不包含任何绕过付费、批量抓取、账号批量操作或规避平台风控的能力，也不接受此类功能贡献。

### 许可证

本项目采用 [MIT License](LICENSE) 开源，可自由使用、复制、修改、合并、发布、分发、再许可及出售副本，条件是保留版权声明与许可声明。软件按「原样」提供，不作任何明示或暗示的担保。各源文件头部以 `SPDX-License-Identifier: MIT` 标注同一许可证。

### 第三方致谢

本项目零第三方代码依赖（见 `app/build.gradle.kts` 的 `dependencies {}`），全部原生代码与注入脚本为本项目原创。README 调研章节中提到的下列项目仅作技术思路引用，**其代码未被复制或引入本仓库**，各自版权与许可证归其作者所有：

| 项目 | 用途（仅调研引用） |
| --- | --- |
| `GangJust/FreedomPlus`、`NukeApp-OSS`、`Douyin Enhancer` | Xposed 类方案代表 |
| `chen-zeong/dtv_mobile` | 直播接口方案代表 |
| `zyronon/douyin`（douyin-vue, GPL-3.0） | 仿站演示代表 |
| `TikTokDownloader`、`f2`、`Douyin_TikTok_Download_API` | 解析/下载器代表 |
| ReVanced / Morphe | 国际版 TikTok 补丁方案代表 |

构建工具链（aapt2 / javac / d8 / zipalign / apksigner）来自 Android SDK，版权归 Google 所有。
