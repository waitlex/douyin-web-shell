/* ============================================================================
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 waitlex
 * ----------------------------------------------------------------------------
 * 抖音网页版 · 移动端适配层
 * ----------------------------------------------------------------------------
 * 由 MainActivity 在 onPageStarted / onPageFinished 各注入一次（脚本自带幂等保护）。
 * 注意：这不是真正的 document-start —— 零依赖构建拿不到
 * WebViewCompat.addDocumentStartJavaScript，所以页面自身的首个脚本可能比我们早执行。
 * 若确实需要更早，把工程的 androidx.webkit 依赖加回来即可。
 *
 * 设计边界（很重要）：
 *   只做「环境特征 / 视口 / 输入事件 / DOM 浮层 / video 元素」这一层的适配，
 *   不伪造签名参数、不劫持接口请求。所以它不属于逆向，也不该被改造成逆向工具。
 *
 * 调参入口：window.__DY_SHELL_CONFIG__
 * 日志出口：window.__DY_NATIVE__.log（由 MainActivity 注入的 JsBridge，
 *          落到手机 Download/DouyinShell/douyin-shell.log；油猴里自动退回 console）
 * ==========================================================================*/
(function () {
  'use strict';

  if (window.__DY_SHELL__) return;
  window.__DY_SHELL__ = 1;

  // 有原生桥说明在 App 里跑，此时直接按沉浸宽度起步，省掉「先按 1280 渲染再重排」的闪动
  var HAS_NATIVE = !!(window.__DY_NATIVE__ && window.__DY_NATIVE__.log);
  var WIDTH_WEB = 1280;
  var WIDTH_IMMERSIVE = 900;

  var CFG = {
    // 桌面布局的设计宽度。抖音网页版在这个宽度下是完整的桌面样式。
    designWidth: HAS_NATIVE ? WIDTH_IMMERSIVE : WIDTH_WEB,
    // 'meta'      只声明 width（引擎自己 fit，最稳，默认）
    // 'meta-scale' 额外写 initial-scale（某些机型 meta 不生效时用）
    // 'css'       给 html 加 zoom（前两种都失效时的兜底）
    viewportMode: 'meta',
    // 是否伪装成「没有触摸屏」。关掉它可保留原生触摸行为便于排查问题。
    spoofTouch: true,
    // 一次滑动折算成多少 wheel deltaY
    wheelStep: 120,
    // 两次翻页之间的最小间隔，防止一滑跳好几个视频
    wheelCooldown: 620,
    // 抖音若把方向做反了（上滑变上一个），把这个改成 true 即可，不用改逻辑
    wheelInvert: false,
    // 滑动判定：距离 + 速度，慢速长滑也算一次翻页
    swipeMinPx: 34,
    swipeMinSpeed: 0.25,
    swipeMaxMs: 900,
    // 是否清理「打开/下载抖音 App」浮层
    removePromos: true,
    // 双击点赞：页面自己**不**认双击（合成 dblclick 也没反应，实测过），
    // 所以由这一层判定双击，再让原生去真点一下右侧的点赞按钮
    synthDoubleTap: true,
    // 冷启动直接进「推荐」单视频流。
    //
    // 不要再走「精选页里点开第一条 + ?modal_id=」那条老路：那个 modal 的
    // 「下一个」是同一作者/同一合集的续播，实测连刷十条都是同一个人。
    // ?recommend=1 才是真正的推荐流，一屏一条、每滑一次换一个作者。
    // from_nav=1 不能省：不带它时服务端会把人重定向回 /jingxuan 网格页。
    autoFeed: true,
    recommendUrl: 'https://www.douyin.com/?recommend=1&from_nav=1',
    // 启动默认静音。音量由原生 Dock 的音量条控制，直接改 video 的 volume/muted，
    // 不动系统媒体音量（那会把用户的铃声/通知音量一起改掉）
    startMuted: true,
    // 精简模式：隐藏左侧导航栏、顶部搜索框这类「网页版的外壳」
    focusMode: true,
    // 把播放器容器拉到视口高度。桌面播放器是 16:9，竖屏上上下各留一大片空白
    fillVideo: true,
    // 隐藏弹幕层：压在画面上方一大片文字，只想看视频时是纯噪音
    hideDanmaku: true,
    // 底部整排播放器控件（播放/时间/弹幕/倍速/清晰度/音量/全屏）交给原生 UI，
    // 这里只负责把它们藏掉，进度条也由原生重画（页面那排贴在屏幕最底边，会被圆角切）
    trimPlayer: true,
    // 头像上方的 AI 入口 + 上下切视频箭头：视觉上拿掉，但箭头仍然可被脚本点到（翻页靠它）
    hideSwitchArrows: true,
    // 右侧竖向按钮列（头像 / 关注 / 赞 / 评论 / 收藏 / 转发）已经整列搬进 Dock，
    // 页面那一列全部藏掉。注意要可点的那几颗只能用 opacity —— 见 playerChromeCss
    hideOwnRail: true,
    // 「看相关」和它下面那颗「…」：接管之后没有对应功能，直接不要
    hideExtras: true,
    // 播放区铺满：右侧 60px 是页面给竖列按钮留的 padding，顶部 56px 是网页版
    // 搜索栏的占位；两个都归零，视频才真的顶到状态栏、贴到屏幕右缘
    fullBleed: true,
    // 左下角作者名 / 文案的字号。桌面版这套是给 1280 宽准备的，
    // 手机上按 900 渲染之后只剩 15px 上下，几乎看不清
    bigInfo: true,
    // 评论区改成「从 Dock 上方升起」的底部抽屉。
    // 网页版是右侧抽屉，还会把视频挤成半宽 —— 手机上这个交互很别扭。
    sheetComments: true,
    // 抽屉展开时把视频容器压到上方，而不是让抽屉浮在视频上面挡住画面。
    // 视频 : 评论 = 50 : 50（五五分），和手机抖音点开评论时的比例接近
    sheetPushVideo: true,
    // 评论抽屉顶部的 semi-tabs 有「AI 图标 / TA的作品 / 评论 / 相关推荐」四页，
    // 默认那一页经常不是评论（看到的是作品卡片）。只保留「评论」这一页。
    simpleCommentTabs: true,
    // 旧的那排按钮只是 opacity:0，仍然能命中真实触摸：点一下会偷偷打开评论区、
    // 还带出悬浮标签。这里在捕获阶段把「用户真手指」事件掐掉，脚本 .click() 放行。
    blockGhostRail: true,
    // 真全屏：视频铺满整屏（网页版的全屏只是把系统栏藏了，画面还是 16:9 那一块）
    trueFullscreen: true
  };
  window.__DY_SHELL_CONFIG__ = CFG;

  // 抽屉开合动画时长（ms）。CSS 的高度过渡和「什么时候拆壳」的定时器
  // 必须用同一个值，差一帧就会看到「视频回来了、抽屉还挂在那儿」。
  var SHEET_MS = 300;

  // 打开评论后抽屉占视口高度的百分比（vh）。视频区拿剩下的 100 - SHEET_VH。
  //
  // 原来五五分，一屏只放得下 2 条半评论 —— 而评论区的信息密度本来就靠
  // 「一次能看到几条」撑着。58% 大约能放 3 条半，视频还留得住画面主体。
  // **三处必须同时跟着变**（都在本文件里）：.dy-sheet 的 height、
  // dy-sheet-open 时视频区的高度、setSheetVideo / fillPlayer 写的内联高度。
  // 所以这里用一个常量，别再各处写死 50vh。
  var SHEET_VH = 58;

  /* ---------------------------------------------------------------- 0. 日志 */

  // 通过 MainActivity 注入的 __DY_NATIVE__.log 回传，落到手机的 Download/DouyinShell/
  // 在油猴里单独跑时没有这个桥，自动退回 console。
  var LOG = (function () {
    var bridge = window.__DY_NATIVE__;
    var lines = [];
    var timer = 0;
    // 单页上限，避免某个循环把日志刷爆（文件本身还有 256KB 环形上限）
    var MAX_LINES = 500;
    var budget = MAX_LINES;

    function flush() {
      timer = 0;
      if (!lines.length) return;
      var payload = lines.join('\n');
      lines.length = 0;
      try {
        if (bridge && typeof bridge.log === 'function') bridge.log(payload);
        else if (window.console) console.log('[DY]\n' + payload);
      } catch (e) { /* 桥失效时宁可丢日志，也不要打断页面 */ }
    }

    function put(msg) {
      if (budget < 0) return;
      if (budget === 0) {
        // 明确留一条，省得排查时以为「没日志 = 没事件」
        budget = -1;
        lines.push('WARN 本页已达 ' + MAX_LINES + ' 条上限，后续事件不再上报');
        flush();
        return;
      }
      budget--;
      lines.push(msg);
      if (lines.length >= 60) { flush(); return; }
      if (!timer) timer = setTimeout(flush, 400);
    }

    window.addEventListener('pagehide', flush);

    return {
      info: function (tag, msg) { put(tag + ' ' + msg); },
      err: function (where, e) {
        var detail = e && (e.stack || e.message) ? (e.stack || e.message) : String(e);
        put('ERROR ' + where + ' :: ' + detail);
      }
    };
  })();

  window.addEventListener('error', function (e) {
    LOG.err('window.onerror', e.error || e.message);
  });
  window.addEventListener('unhandledrejection', function (e) {
    LOG.err('unhandledrejection', e.reason);
  });

  var CHROME_MAJOR = (navigator.userAgent.match(/Chrome\/(\d+)/) || [])[1] || '131';
  var now = function () { return Date.now(); };

  /* ---------------------------------------------------------------- 1. 环境 */

  function lockGetter(obj, prop, value) {
    try {
      Object.defineProperty(obj, prop, {
        configurable: true,
        get: function () { return value; }
      });
      return true;
    } catch (e) { return false; }
  }

  lockGetter(navigator, 'platform', 'Win32');
  lockGetter(navigator, 'vendor', 'Google Inc.');

  if (CFG.spoofTouch) {
    lockGetter(navigator, 'maxTouchPoints', 0);
    try { delete Document.prototype.ontouchstart; } catch (e) {}
    try { delete HTMLElement.prototype.ontouchstart; } catch (e) {}
    try { delete Window.prototype.ontouchstart; } catch (e) {}
  }

  // WebView 的 navigator.userAgentData 会带 Android / mobile:true，桌面 Chrome 不会
  (function patchUaData() {
    if (!navigator.userAgentData) return;
    var brands = [
      { brand: 'Chromium', version: CHROME_MAJOR },
      { brand: 'Google Chrome', version: CHROME_MAJOR },
      { brand: 'Not_A Brand', version: '24' }
    ];
    var data = {
      brands: brands,
      mobile: false,
      platform: 'Windows',
      getHighEntropyValues: function () {
        return Promise.resolve({
          architecture: 'x86', bitness: '64', brands: brands,
          fullVersionList: brands, mobile: false, model: '',
          platform: 'Windows', platformVersion: '15.0.0',
          uaFullVersion: CHROME_MAJOR + '.0.0.0', wow64: false
        });
      },
      toJSON: function () { return { brands: brands, mobile: false, platform: 'Windows' }; }
    };
    try {
      Object.defineProperty(navigator, 'userAgentData', {
        configurable: true,
        get: function () { return data; }
      });
    } catch (e) {}
  })();

  LOG.info('ENV', 'ua=' + navigator.userAgent);
  LOG.info('ENV', 'platform=' + navigator.platform
    + ' maxTouch=' + navigator.maxTouchPoints
    + ' uaDataMobile=' + (navigator.userAgentData ? navigator.userAgentData.mobile : 'n/a')
    + ' ontouchstart=' + ('ontouchstart' in window));

  /** 日志里只用标签名 + id，不打整段 HTML。 */
  function tagOf(el) {
    if (!el || !el.tagName) return '?';
    var s = el.tagName.toLowerCase();
    if (el.id) s += '#' + String(el.id).slice(0, 24);
    return s;
  }

  /* ---------------------------------------------------------------- 2. 视口 */

  function ensureViewportMeta() {
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) {
      m = document.createElement('meta');
      m.setAttribute('name', 'viewport');
      (document.head || document.documentElement).appendChild(m);
    } else if (document.head && m.parentNode !== document.head) {
      // document-start 时 head 可能还不存在，挂到了 <html> 上，Chrome 不一定认
      document.head.appendChild(m);
    }
    return m;
  }

  var lastViewportSig = '';

  function applyViewport() {
    var html = document.documentElement;
    if (!html) return;

    var screenW = (window.screen && screen.width) ? screen.width : (window.innerWidth || CFG.designWidth);
    var scale = screenW / CFG.designWidth;
    if (!isFinite(scale) || scale <= 0.05 || scale > 2) scale = 0.5;

    if (CFG.viewportMode === 'css') {
      html.style.setProperty('zoom', String(scale));
      html.style.setProperty('width', CFG.designWidth + 'px');
      if (document.body) document.body.style.setProperty('width', CFG.designWidth + 'px');
      reportViewport('css', scale, screenW);
      return;
    }

    var content = 'width=' + CFG.designWidth;
    if (CFG.viewportMode === 'meta-scale') {
      content += ', initial-scale=' + scale.toFixed(4)
        + ', minimum-scale=0.1, maximum-scale=5, user-scalable=no';
    }
    var meta = ensureViewportMeta();
    if (meta.getAttribute('content') !== content) {
      meta.setAttribute('content', content);
    }
    reportViewport(CFG.viewportMode, scale, screenW);
  }

  // 视口每次 head 变动、每次 resize 都会重算，只在结果真的变了时才记一条
  function reportViewport(mode, scale, screenW) {
    var sig = mode + '|' + CFG.designWidth + '|' + screenW + '|'
      + window.innerWidth + 'x' + window.innerHeight + '|' + (window.devicePixelRatio || 1);
    if (sig === lastViewportSig) return;
    lastViewportSig = sig;
    LOG.info('VIEWPORT', mode + ' screenW=' + screenW + ' design=' + CFG.designWidth
      + ' scale=' + scale.toFixed(3)
      + ' inner=' + window.innerWidth + 'x' + window.innerHeight
      + ' dpr=' + (window.devicePixelRatio || 1));
  }

  /* ----------------------------------------------------- 3. 触摸 → wheel 合成 */

  var lastWheelAt = 0;
  var lastScrollLogAt = 0;

  /**
   * 从手指按下的元素往上找「还能继续滚」的容器。
   *
   * guard 从 40 提到 120：评论项是 semi-ui + React 的深层结构，实测从
   * e.target（常常是 <span>/<svg>）走到 [data-e2e="comment-list"] 会越过
   * 40 层。一旦在这里提前放弃，touchend 就会把「评论里滑动」误判成
   * 「翻视频」，表现成滑不动还换了下一条。多点几十层的开销可以忽略
   * （只在 touchend 跑一次，且找到就 return）。
   *
   * 但光提高上限仍不够：滚到顶/底、手指落在 header 或空白区时，方向
   * 判断本来就会返回 null。所以 touchend 里另有一道「抽屉内手势」硬
   * 闸门兜底，那里才是「评论里绝不翻视频」的最终保证。
   */
  function scrollableAncestor(el, dir) {
    var node = el;
    var guard = 0;
    while (node && node !== document.documentElement && guard++ < 120) {
      var st = window.getComputedStyle(node);
      var oy = st.overflowY;
      if ((oy === 'auto' || oy === 'scroll') && node.scrollHeight > node.clientHeight + 8) {
        if (dir > 0 && node.scrollTop + node.clientHeight < node.scrollHeight - 4) return node;
        if (dir < 0 && node.scrollTop > 4) return node;
      }
      node = node.parentElement;
    }
    return null;
  }

  /**
   * 起点是否落在评论抽屉矩形内 —— target 链失效时的兜底判定。
   *
   * 抽屉被 reparent 到 [data-e2e="slideList"] 下，页面也可能在任意时刻
   * 重建评论节点；单靠 e.target.closest('.dy-sheet') 有漏网的时候。
   * 用 touchstart 记下的起点坐标再比对一次矩形，两种路径取或。
   * 只在 dy-sheet-open 在场时判：关合动画期间抽屉虽还在 DOM 里，
   * 那几百毫秒不该拦住用户想翻页的手势。
   */
  function pointInSheet(x, y) {
    if (!document.documentElement.classList.contains('dy-sheet-open')) return false;
    var sheets = document.querySelectorAll('.dy-sheet');
    for (var i = 0; i < sheets.length; i++) {
      var r = sheets[i].getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) continue;
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true;
    }
    return false;
  }

  /**
   * 请原生层发一个真正的滚轮事件。
   *
   * 这是整个壳最关键的一处妥协：抖音的切视频组件校验 event.isTrusted，
   * 脚本里 new WheelEvent() 派发出去会被直接丢掉（桌面版也只监听 wheel，
   * 真实手指拖动都不翻页）。所以只能让 Java 侧合成 ACTION_SCROLL。
   */
  function nativeScroll(sign) {
    var b = window.__DY_NATIVE__;
    if (!b || typeof b.scroll !== 'function') return false;
    try {
      b.scroll(sign);
      return true;
    } catch (e) {
      LOG.err('nativeScroll', e);
      return false;
    }
  }

  /**
   * 点页面自己的「上一个 / 下一个」按钮。
   *
   * 这是本壳唯一可靠的翻页方式，绕了一圈才找到：
   *   1. new WheelEvent()         → 被 event.isTrusted 校验丢掉
   *   2. 真实手指拖动              → 桌面版只监听 wheel，无效
   *   3. Java 合成 ACTION_SCROLL   → 分发成功但页面不动
   *   4. 注入方向键                → 无效
   *   5. 点 data-e2e="video-switch-*-arrow" → 有效 ✅
   *
   * 但要注意 swiper 会同时存在 3 组按钮（相邻的上一屏/下一屏各有一套），
   * 只有当前这一屏的那组在视口内；点错组等于点了看不见的视频，没反应。
   */
  function switchArrow(dir) {
    var sel = dir > 0
      ? '[data-e2e="video-switch-next-arrow"]'
      : '[data-e2e="video-switch-prev-arrow"]';
    var nodes = document.querySelectorAll(sel);
    var limit = Math.min(nodes.length, 8);
    var vh = window.innerHeight || 0;
    for (var i = 0; i < limit; i++) {
      var el = nodes[i];
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) continue;
      if (r.bottom <= 0 || r.top >= vh) continue;   // 只要当前这一屏的
      try {
        el.click();
        return true;
      } catch (e) {
        LOG.err('switchArrow', e);
      }
    }
    return false;
  }

  function dispatchWheel(dir) {
    var t = now();
    if (t - lastWheelAt < CFG.wheelCooldown) {
      LOG.info('WHEEL', '冷却中，忽略（距上次 ' + (t - lastWheelAt) + 'ms）');
      return false;
    }

    var sign = CFG.wheelInvert ? -dir : dir;

    if (switchArrow(sign)) {
      lastWheelAt = t;
      LOG.info('WHEEL', '点「' + (sign > 0 ? '下一个' : '上一个') + '」按钮');
      return true;
    }

    // 页面上没有这组按钮（改版/别的页面）时退回原生滚轮，再不行才用合成事件
    if (nativeScroll(sign)) {
      lastWheelAt = t;
      LOG.info('WHEEL', '改用原生滚轮 dir=' + sign);
      return true;
    }

    var cx = Math.round((window.innerWidth || 2) / 2);
    var cy = Math.round((window.innerHeight || 2) / 2);
    var target = document.elementFromPoint(cx, cy);
    // 命中 <html>/<body> 说明这一层没有监听者，退到 body 上让事件冒泡
    var fellBack = false;
    if (!target || target === document.documentElement) {
      target = document.body;
      fellBack = true;
    }
    if (!target) {
      LOG.info('WHEEL', '找不到派发目标，这次滑动丢弃');
      return false;
    }

    var ev;
    try {
      ev = new WheelEvent('wheel', {
        deltaX: 0,
        deltaY: sign * CFG.wheelStep,
        deltaZ: 0,
        deltaMode: 0,
        bubbles: true,
        cancelable: true,
        composed: true,
        view: window
      });
    } catch (e) {
      LOG.err('dispatchWheel', e);
      return false;
    }

    lastWheelAt = t;
    target.dispatchEvent(ev);
    LOG.info('WHEEL', 'dir=' + sign + ' delta=' + (sign * CFG.wheelStep)
      + ' on <' + tagOf(target) + '>' + (fellBack ? '（中心无元素，退到 body）' : ''));
    return true;
  }

  var gesture = { active: false, x: 0, y: 0, t: 0, target: null, inSheet: false };

  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { gesture.active = false; return; }
    gesture.active = true;
    gesture.x = e.touches[0].clientX;
    gesture.y = e.touches[0].clientY;
    gesture.t = now();
    gesture.target = e.target;
    // 评论抽屉里的手势只滚评论、永不翻视频。touchstart 记下这份判定，
    // touchend 再用起点坐标兜一次（见 pointInSheet），两条路径取或。
    gesture.inSheet = !!(e.target && e.target.closest && e.target.closest('.dy-sheet'));
  }, { capture: true, passive: true });

  function cancelGesture() { gesture.active = false; }

  document.addEventListener('touchcancel', cancelGesture, { capture: true, passive: true });

  document.addEventListener('touchend', function (e) {
    if (!gesture.active) return;
    gesture.active = false;

    var ch = e.changedTouches && e.changedTouches[0];
    if (!ch) return;

    var dx = ch.clientX - gesture.x;
    var dy = ch.clientY - gesture.y;
    var dt = Math.max(now() - gesture.t, 1);

    // 横向手势交给页面自己（左右滑切 Tab）
    if (Math.abs(dy) < Math.abs(dx) * 1.2) return;

    // 「在评论区滑动却切了视频」的最终闸门。
    // 只要起点在抽屉内（DOM 链或矩形任一命中），就一律放行给浏览器原生滚动：
    // 评论列表的 [data-e2e="comment-list"] 是 overflow-y:scroll 的真容器，
    // passive 监听里 return 之后浏览器自己会滚，无需我们介入。
    // 这一条不依赖 scrollableAncestor 的启发式 —— 滚到顶/底、落在 header
    // 空白区、DOM 层级过深等任何情况下它都可能返回 null，绝不能让它
    // 把评论里的滑动翻译成 dispatchWheel()。
    if (gesture.inSheet || pointInSheet(gesture.x, gesture.y)) {
      if (now() - lastScrollLogAt > 2000) {
        lastScrollLogAt = now();
        LOG.info('SWIPE', '评论区手势，交给原生滚动 dy=' + Math.round(dy));
      }
      return;
    }

    var dist = Math.abs(dy);
    var speed = dist / dt;
    // 快滑看速度，慢速长滑看距离 —— 只卡固定时长会让人觉得「滑了没反应」
    var isSwipe = (dist >= CFG.swipeMinPx && speed >= CFG.swipeMinSpeed)
      || (dist >= CFG.swipeMinPx * 2.2 && dt <= CFG.swipeMaxMs);
    if (!isSwipe) return;

    // 手指上滑 → 想看下一个视频 → deltaY 为正
    var dir = dy < 0 ? 1 : -1;

    // 评论区、侧栏这类真能滚的容器交回原生滚动
    var scrollHost = scrollableAncestor(gesture.target, dir);
    if (scrollHost) {
      // 在评论区里滑动会连续触发，限流到 2s 一条
      if (now() - lastScrollLogAt > 2000) {
        lastScrollLogAt = now();
        LOG.info('SWIPE', '交给原生滚动 <' + tagOf(scrollHost) + '> dy=' + Math.round(dy));
      }
      return;
    }

    dispatchWheel(dir);
  }, { capture: true, passive: true });

  /* ------------------------------------------------------------ 4. 双击点赞 */
  // 桌面版把「点赞」做成了双击播放器，但那是给鼠标 dblclick 用的：
  // 手指双击只会被页面当成两次单击（= 暂停 → 播放），实测不会点赞，
  // 脚本里 dispatch 一个 dblclick 页面也不认（isTrusted）。
  //
  // 所以双击由这里自己判：位置相近 + 两次抬手间隔 < 300ms。
  // 判定到之后把活儿交给原生 —— 原生去合成一次真实触摸点右侧那颗心，
  // 未登录时顺便弹「登录后才能点赞」。页面那边唯一要保证的是别拦它的单击。

  var tapTrack = { t: 0, x: 0, y: 0 };
  var tapDown = { x: 0, y: 0 };

  /** 双击只认画面里的那一块，评论区、作者头像上的双击不该等于点赞。 */
  function inVideoArea(el) {
    if (!el) return false;
    if (el.tagName === 'VIDEO') return true;
    return !!(el.closest && el.closest('.xgplayer'));
  }

  if (CFG.synthDoubleTap) {
    document.addEventListener('touchstart', function (e) {
      var t = e.touches && e.touches[0];
      if (!t) return;
      tapDown.x = t.clientX;
      tapDown.y = t.clientY;
    }, { capture: true, passive: true });

    document.addEventListener('touchend', function (e) {
      var ch = e.changedTouches && e.changedTouches[0];
      if (!ch) return;

      // 滑动（哪怕只滑了十几像素）不算点击。
      // 少了这一步，连续两次上滑就会被当成「双击」而点赞 —— 手势翻页时特别容易撞上。
      var moved = Math.abs(ch.clientX - tapDown.x) + Math.abs(ch.clientY - tapDown.y);
      if (moved > 24) { tapTrack.t = 0; return; }

      var t = now();
      var near = Math.abs(ch.clientX - tapTrack.x) < 44
        && Math.abs(ch.clientY - tapTrack.y) < 44;
      if (t - tapTrack.t < 300 && near) {
        tapTrack.t = 0;
        if (!inVideoArea(document.elementFromPoint(ch.clientX, ch.clientY))) return;
        var b = window.__DY_NATIVE__;
        if (b && typeof b.doubleTapLike === 'function') {
          try { b.doubleTapLike(); } catch (err) { LOG.err('doubleTapLike', err); }
          LOG.info('LIKE', '双击点赞 @' + Math.round(ch.clientX) + ',' + Math.round(ch.clientY));
        } else {
          like();   // 没有原生桥（油猴直跑）：只能自己合成，页面认不认随缘
        }
        return;
      }
      tapTrack.t = t;
      tapTrack.x = ch.clientX;
      tapTrack.y = ch.clientY;
    }, { capture: true, passive: true });
  }

  /* ------------------------------------------------- 5. scheme 链接 / 新窗口 */

  var SCHEME_RE = /^(snssdk1128|aweme|bytedance|intent|weixin|alipays?|openapp\.[a-z]+):/i;

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (SCHEME_RE.test(href)) {
      e.preventDefault();
      e.stopPropagation();
      LOG.info('SCHEME', '拦掉链接跳转 ' + href.slice(0, 90));
    }
  }, true);

  (function patchWindowOpen() {
    var nativeOpen = window.open;
    if (typeof nativeOpen !== 'function') return;
    window.open = function (url) {
      if (typeof url === 'string' && /^https?:/i.test(url)) {
        LOG.info('WINDOW', 'window.open 收敛为同窗口 ' + url.slice(0, 90));
        window.location.href = url;
        return window;
      }
      return nativeOpen.apply(window, arguments);
    };
  })();

  /* ---------------------------------------------------------- 6. 浮层清理 */

  var PROMO_RE = /(打开|下载|安装|去)\s*(抖音|客户端|APP|App)|在\s*APP\s*内打开|立即打开/i;
  var SAFE_RE = /(登录|扫码|验证码|隐私|协议|青少年)/;

  function looksLikePromo(el) {
    if (!el || el.nodeType !== 1) return false;
    // 容器节点直接跳过：textContent 会遍历整棵子树，短视频站 DOM 很大
    if (el.childElementCount > 3) return false;
    var txt = (el.textContent || '').replace(/\s+/g, ' ').trim();
    if (!txt || txt.length > 40) return false;
    if (SAFE_RE.test(txt)) return false;
    if (!PROMO_RE.test(txt)) return false;

    var st = window.getComputedStyle(el);
    if (st.position !== 'fixed' && st.position !== 'absolute') return false;
    var z = parseInt(st.zIndex, 10);
    if (!(z >= 50)) return false;

    var r = el.getBoundingClientRect();
    if (r.width < 60 || r.height < 24) return false;
    return true;
  }

  function sweepPromos() {
    if (!CFG.removePromos || document.hidden) return;
    var nodes = document.querySelectorAll('div,section,aside,button,a');
    var limit = Math.min(nodes.length, 3000);
    var killed = 0;
    var sample = '';
    for (var i = 0; i < limit; i++) {
      var el = nodes[i];
      if (el.__dyKilled) continue;
      if (looksLikePromo(el)) {
        el.__dyKilled = 1;
        killed++;
        if (!sample) sample = (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 30);
        // 用 display 而不是 remove()，避免打断前端框架的 diff 过程
        el.style.setProperty('display', 'none', 'important');
      }
    }
    if (killed) LOG.info('PROMO', '隐藏 ' + killed + ' 个浮层，例: ' + sample);
  }

  /* ------------------------------------------------------------ 7. video */

  function tuneVideos() {
    var vs = document.querySelectorAll('video');
    var tuned = 0;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      if (v.__dyTuned) continue;
      v.__dyTuned = 1;
      tuned++;
      try {
        v.playsInline = true;
        v.setAttribute('playsinline', '');
        v.setAttribute('webkit-playsinline', '');
        v.setAttribute('preload', 'auto');
      } catch (e) {}
    }
    if (tuned) LOG.info('VIDEO', '适配 ' + tuned + ' 个 video 元素，当前共 ' + vs.length + ' 个');
  }

  /* ------------------------------------------- 7.1 播放器控件收编为原生 UI */
  // 抖音网页版用的是 xgplayer，底部一整排（播放/时间/弹幕/倍速/清晰度/音量/全屏）
  // 全部贴在屏幕最底边 —— 手机上正好落在「圆角 + 手势条」里，两头被裁、点也点不准。
  //
  // 处理方式：整排 display:none，底部只留原生那一条（进度条 + 按钮），
  // 位置、颜色、粗细都由 FeedChrome 决定，页面改版也影响不到。

  var STYLE_ID = '__dy_shell_style';

  function ensureStyle(css) {
    var el = document.getElementById(STYLE_ID);
    if (!el) {
      el = document.createElement('style');
      el.id = STYLE_ID;
      (document.head || document.documentElement).appendChild(el);
      el.textContent = css;
      return true;
    }
    if (el.textContent !== css) { el.textContent = css; return true; }
    return false;
  }

  function playerChromeCss() {
    var css = '';
    if (CFG.trimPlayer) {
      // xg-controls 下面三块：左（播放/时间/弹幕）、中（进度）、右（倍速/清晰度/音量/全屏）
      css += '.xgplayer-controls{display:none !important}';
      // 「看相关」那个悬浮提示也一起收掉，它贴着播放器底部，会压到原生进度条
      css += '.xg-tips{display:none !important}';
    }
    if (CFG.hideSwitchArrows) {
      // 关键：用 visibility 而不是 display。
      // 我们要把它藏掉，但翻页恰恰是脚本点这两个箭头实现的；
      // visibility:hidden 保留布局，getBoundingClientRect 还能拿到坐标，
      // 元素也仍然接受程序化 click()，所以「看不见」和「点得着」可以同时成立。
      css += '.xgplayer-playswitch,.ai-douyin-entry{visibility:hidden !important}';
    }
    if (CFG.hideOwnRail) {
      // 右侧那一列现在整列都归 Dock：头像、关注、赞、评论、收藏、转发。
      //
      // 必须用 opacity:0 而不是 display/visibility：
      //   display:none      → 布局塌陷，整列上移，合成触摸的坐标全废；
      //   visibility:hidden → 退出命中测试，触摸会穿透到下面的 <video> 上变成暂停；
      //   opacity:0         → 不可见、仍占位、仍能命中，三个条件同时满足。
      //
      // 头像和「关注」是例外里的例外：它们还得被原生点（点完变「已关注」），
      // 所以也留在 opacity 这一档，绝不 display:none。
      css += '[data-e2e="video-player-digg"],[data-e2e="feed-comment-icon"],'
        + '[data-e2e="video-player-collect"],[data-e2e="video-player-share"],'
        + '[data-e2e="video-avatar"],[data-e2e="live-avatar"],'
        + '[data-e2e="feed-follow-icon"]{opacity:0 !important}';
    }
    if (CFG.hideExtras) {
      // 「…」是分享面板的另一个入口，我们已经有转发按钮了；
      // 「看相关」是网页版特有的侧栏浮层，没有对应功能 —— 两个都彻底不要。
      css += '[data-e2e="video-play-more"]{display:none !important}';
      css += '[data-dy-gone="1"]{display:none !important}';
      // 合成触摸点完右侧按钮后，semi-ui 会留下一个「评论 ✕」的 hover 提示，
      // 它挂在 semi-portal 里、不随抽屉关闭而消失，糊在画面上当脏东西。
      // tooltip 没有功能，直接连壳拿掉。
      css += '.semi-tooltip-wrapper{display:none !important}';
    }
    if (CFG.fullBleed) {
      // 右侧 60px：页面给竖列按钮留的位置。按钮都进 Dock 了，这里直接铺满。
      css += '[data-e2e="slideList"]{padding-right:0 !important}';
      // 顶部 56px：网页版搜索栏的占位，header 藏了之后这里要跟着塌
      css += '[data-dy-flat="1"]{padding-top:0 !important}';
    }
    if (CFG.bigInfo) {
      // 左下角作者名和文案。页面原本约 15px（按 900 设计宽渲染到 1200 物理屏上
      // 只有 20 物理像素），放到 28 / 24 才算「一眼能读」。
      css += '[data-e2e="feed-video-nickname"]{font-size:28px !important;'
        + 'font-weight:600 !important;line-height:1.3 !important}';
      css += '[data-e2e="video-desc"],[data-e2e="video-desc"] *{font-size:24px !important;'
        + 'line-height:1.5 !important}';
      // 文案别提前折行。桌面版把描述块卡在 324px（右边要留一列按钮），
      // 那一列按钮已经进 Dock 了，这里把宽度还给文字 —— 让它铺到右边缘再折。
      // 两个约束都是页面写的内联样式，只能 !important 覆盖：
      //   video-info 的 padding-right:172px  → 给竖列按钮留的空
      //   它的孩子 div 的 max-width:324px    → 真正的折行点
      css += '[data-e2e="video-info"]{padding-right:14px !important}';
      css += '[data-e2e="video-info"]>div{max-width:none !important;width:100% !important}';
      // 字体跟着系统走：网页版给这套信息区指定了自己的字体栈，
      // 和原生 Dock 上的字放在一起看像两个 App。system-ui 在 Android 上
      // 落到 Roboto + Noto Sans CJK，正是系统默认那一套。
      css += '[data-e2e="video-info"],[data-e2e="video-info"] *{'
        + 'font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif !important}';
    }
    if (CFG.sheetComments) {
      // 视频永远占满整宽。网页版开评论时会把播放器压到 calc(100% - 336px)，
      // 336 就是那条右侧抽屉的宽度 —— 抽屉改成浮层之后这个压缩就没道理了。
      css += '.playerContainer,.basePlayerContainer,.xgplayer{width:100% !important}';
      // 播放器盒子是按「减掉抽屉」算出来的旧尺寸，里面的 video 还留着旧宽高，
      // 不一起改就会在左边孤零零显示一条竖片
      css += '.playerContainer video,.xg-video-container video{'
        + 'width:100% !important;height:100% !important}';
      // 抽屉本体：满宽、顶部圆角。加在它的外层容器上（见 patchCommentSheet）。
      // 它的包含块是 [data-e2e="feed-active-video"]（position:relative），所以
      // bottom:0 是相对那个容器，而不是相对视口 —— 抽屉想贴视口底边，
      // 就必须让那个容器自己只占上方 40%，见下面的 dy-sheet-open 规则。
      // 透明度由我们控（页面在这层上没有内联 opacity）：只在 .dy-sheet-geo 在场时
      // 才是不透明，撤掉那一瞬间开始淡出，配合下滑收得干净。
      css += '.dy-sheet{position:absolute !important;left:0 !important;right:0 !important;'
        + 'top:auto !important;bottom:0 !important;width:100% !important;'
        + 'height:' + SHEET_VH + 'vh !important;'
        + 'transform:translateY(100%) !important;opacity:0 !important;'
        + 'transition:opacity 150ms linear !important;'
        + 'background:#161823 !important;border-radius:16px 16px 0 0 !important;'
        + 'overflow:hidden !important;box-shadow:0 -10px 30px rgba(0,0,0,.55) !important;'
        + 'z-index:20 !important}';
      if (CFG.sheetPushVideo) {
        // 开合动画只由一条属性驱动：**视频区的高度**。
        //
        // 抽屉挂在视频区的下沿（top:100%），视频区从 100vh 过渡到 42vh 时，
        // 它的 top 每一帧都按当时的容器高度重算 —— 也就是说抽屉是被这个
        // 高度变化「顶」上来的；关的时候容器长回去，抽屉顺着下沿滑出屏幕。
        // 两条动画同帧、同曲线，不需要给抽屉再写一份 transform，也不会出现
        // 「抽屉动了、视频还没动」而露出下一屏的空窗。
        //
        // 注意几何关系必须单独用一个类（dy-sheet-geo）：dy-sheet-open 一撤，
        // 如果 top 同时回到 auto，抽屉会先跳到视口底部再滑，反而更跳。
        css += 'html.dy-sheet-geo [data-e2e="feed-active-video"],'
          + 'html.dy-sheet-geo [data-e2e="feed-item"],'
          + 'html.dy-sheet-geo [data-e2e="slideList"]'
          + '{overflow:visible !important}';
        // 抽屉自己用 transform 做滑入/滑出：高度现在瞬时到位，不能再靠「被顶上来」
        // 那套几何动画。geo 在场但还没 open = 停在屏外，open 加上 = 滑到位，
        // 关时先撤 open（geo 还在）= 滑出去，滑完才拆壳。
        css += 'html.dy-sheet-geo .dy-sheet{top:100% !important;bottom:auto !important;'
          + 'opacity:1 !important;transform:translateY(100%) !important;'
          + 'transition:transform ' + SHEET_MS + 'ms cubic-bezier(.2,0,0,1) !important}';
        css += 'html.dy-sheet-open .dy-sheet{transform:translateY(0) !important}';
        // 打开评论的整段动画里（geo 在场期间），只让当前这一条视频可见。
        // 视频区要从 100vh 一路缩到 42vh，页面自己的入场动画还会挪 swiper，
        // 空窗里露出来的往往是「下一条」甚至「下下条」的画面 —— 以前只藏
        // 紧邻的一个兄弟，抽屉高度从 50vh 提到 58vh 之后藏不住了。
        // 这里用 CSS 兜底：geo 在 setSheetVideo(true) 那一帧就加上，
        // 一直到收起动画走完才撤，正好覆盖整个风险窗口。
        // 当前条由 setSheetVideo() 打 .dy-active-slide 标记。
        css += 'html.dy-sheet-geo [data-e2e="feed-item"]:not(.dy-active-slide)'
          + '{visibility:hidden !important}';
        // 只过渡 height，而且只在抽屉在场期间生效：页面自己在这几个节点上也
        // 写过 transition（feed-item 是 all、feed-active-video 是 .15s linear），
        // 全程覆盖会把 swiper 的位移过渡一起废掉；而抽屉开着时用户本来也翻不了页。
        //
        // **只给「有显式高度」的那几层挂过渡**：slideList 是 vh、feed-item 是
        // 页面写的内联 px、.dy-pbox 是内联 vh。feed-active-video / sliderVideo
        // 是 100% 高度，它们每帧跟着父级算就行 —— 给它们也挂过渡，等于让
        // 同一段动画在嵌套层级上各跑一遍，尾巴会拖到 600ms 以上。
        // 这里**不能**给 item/slideList 挂 height 过渡：它们一平滑，offsetTop
        // 就逐帧漂移，而 swiper 的 wrapper translate 是瞬时跳的，两者对不上，
        // video 的视觉 y（= translate + offsetTop）就会甩到 -2300 再弹回 —— 就是
        // 「开合闪烁」。高度瞬时到位（setSheetVideo 写内联 px），swiper 一次算准；
        // 顺滑感由 video 画面锁定器 + 抽屉自己的收放接管。
        css += 'html.dy-sheet-geo [data-e2e="feed-item"],'
          + 'html.dy-sheet-geo [data-e2e="slideList"],'
          + 'html.dy-sheet-geo .dy-pbox'
          + '{transition:none !important}';
        // 视频区缩到上方 42%，抽屉从它的下沿继续往下铺满剩下的 58vh。
        //
        // 只写 height，**不要**再顺手写 min-height/max-height 的同一个值 —— 那两个
        // 属性不参与我们这条过渡，会在过渡开始的第一帧就把高度钳死，
        // 结果是「瞬间跳到位」，动画等于没写。页面自己给的 min/max 也要按下去，
        // 否则 height 过渡会被它们夹住。
        css += 'html.dy-sheet-open [data-e2e="feed-active-video"],'
          + 'html.dy-sheet-open [data-e2e="feed-item"],'
          + 'html.dy-sheet-open [data-e2e="slideList"]'
          + '{height:' + (100 - SHEET_VH) + 'vh !important;min-height:0 !important;max-height:none !important}';
        // 收起时页面自己也有一套高度动画（它每帧往里写内联 px），比我们的
        // SHEET_MS 慢一倍，还会在收尾处往回顿一下。收起期间把 feed-item 的
        // 高度钉在「打开之前量到的真实值」（--dy-sheet-h），过渡就只跑我们这一段。
        css += 'html.dy-sheet-closing [data-e2e="feed-item"]'
          + '{height:var(--dy-sheet-h,100vh) !important}';
      }
      // 抽屉里的列表要能自己滚（外层 overflow:hidden 了）
      css += '.dy-sheet [class*="comment"]{max-height:100% !important}';

      // 评论打开时视频区只剩 42vh，16:9 的画面居中后上下各留一条黑边，
      // 作者 / 标题 / 话题标签正好挤在这条黑边里 —— 抽屉高度一提，它们就
      // 开始字叠字。读评论的时候这块信息本来也用不上，淡出掉，
      // 视频画面反而更干净。关掉评论就从 0 淡回来（180ms 跟抽屉差不多）。
      css += 'html.dy-sheet-open [data-e2e="video-info"]{'
        + 'opacity:0 !important;pointer-events:none !important;'
        + 'transition:opacity 180ms linear !important}';

      // 整套尺寸的来源：网页版按 900 CSS px 的设计宽排版，落到这块 1200 物理
      // 像素、369dp 宽的屏上，1 CSS px ≈ 0.41dp。所以页面里的 16px 内边距只有
      // 6.5dp、40px 的头像只有 16dp、20px 的图标只有 8dp —— 按桌面比例看没问题，
      // 按手机手感全是「太小」。下面这些值都是先按 dp 定，再乘 2.44 折回 CSS px，
      // 改的时候也请这么算，别直接拿网页版的数字微调。

      // ---- 头部：把「全部评论(N)」做成标题栏 ----
      // 顶部那一行只剩一个「评论」tab（其余被 trimCommentTabs 摘了），文字本身
      // 留着无妨；页面把关闭用的 × 也挂在这一层，动它的定位容易让 × 跑出抽屉，
      // 所以这行只保留 tab 精简，不再压高度。
      css += '.dy-sheet [class*="semi-tabs-tab"]{display:none !important}';
      // 标题栏：左对齐、34px（≈14sp）、半粗，底部一条极淡分隔线给列表定界。
      // 左右 32px（≈13dp）—— 原来列表只有 16px，字几乎顶到屏幕边。
      //
      // 负 margin 是为了抵掉外层那层 `padding:0 16px`：只改内层 padding 的话，
      // 标题会比下面列表的头像左边缘多缩 16px，两处对不齐，看着就别扭。
      css += '.dy-sheet [class*="comment-header-inner-container"]{'
        + 'margin:0 -16px !important;height:auto !important;width:auto !important;'
        + 'box-sizing:border-box !important;padding:24px 32px 16px !important;'
        + 'border-bottom:1px solid rgba(255,255,255,.06) !important}';
      css += '.dy-sheet [class*="comment-header"] span{'
        + 'font-size:34px !important;line-height:1.25 !important;font-weight:600 !important;'
        + 'color:rgba(255,255,255,.92) !important}';
      // 「大家都在搜」是网页版塞进评论头的推荐位，和评论无关，占 40px；
      // 摘掉它，列表能多出一行正文。类名是 comment-head 或
      // comment-header-with-search —— 注意别用 [class*=comment-head]，
      // 那会连 comment-header-inner-container（「全部评论」那行）一起藏掉。
      css += '.dy-sheet .comment-head,.dy-sheet [class*="comment-header-with-search"]'
        + '{display:none !important}';

      // ---- 列表与条目 ----
      // 右边留 24px 而不是原来的 7px：滚动条贴边不算问题，但点赞数会顶到屏幕边。
      // 底部留 88px（≈36dp，正好一个输入栏高）：滚到最后一条时它能整个露在
      // 输入栏上方，而不是被列表边界切一半。
      css += '.dy-sheet [data-e2e="comment-list"]'
        + '{padding:0 24px 88px 32px !important}';
      css += '.dy-sheet [data-e2e="comment-item"]{'
        + 'margin-top:30px !important;align-items:flex-start !important}';

      // 头像 40px → 84px（≈34dp）。原来那条 16dp 的小圆点在这块屏上基本
      // 认不出是谁，评论列表也就没了「扫一眼看人」的能力。
      //
      // 光改尺寸不够，页面给这套 Avatar 组件下了 `.jHiQ8CA1{width:40px!important}`，
      // 而且**把 span 的 opacity 压到 0**（等某个时机再淡入，实测一直没等到）。
      // 所以这里要三件事一起做：撑开 span 的溢出（否则 84px 的 img 被 42px 的
      // 圆形容器裁掉一半）、强制 opacity:1、再让 <a> 自己裁成圆形。
      // 容器本身被页面拉成了整条高度（235px），也要显式钉回正方形，
      // 否则 margin-right 会让下面的正文和头像错开。
      css += '.dy-sheet [class*="comment-item-avatar"]{'
        + 'flex:0 0 84px !important;width:84px !important;min-width:84px !important;'
        + 'height:84px !important;margin-right:22px !important}';
      css += '.dy-sheet [class*="comment-item-avatar"] a{'
        + 'display:block !important;width:84px !important;height:84px !important;'
        + 'line-height:0 !important;border-radius:50% !important;overflow:hidden !important}';
      css += '.dy-sheet [data-e2e="comment-item"] [class*="comment-item-avatar"] span{'
        + 'width:84px !important;height:84px !important;max-width:none !important;'
        + 'max-height:none !important;opacity:1 !important;overflow:visible !important;'
        + 'border-radius:50% !important;background:rgba(255,255,255,.08) !important}';
      css += '.dy-sheet [class*="comment-item-avatar"] img{'
        + 'width:84px !important;height:84px !important;border-radius:50% !important;'
        + 'display:block !important;object-fit:cover !important;opacity:1 !important}';

      // 正文列改成 flex-wrap：昵称、正文各占一整行，时间/地区和
      // 回复·分享·点赞挤在最后一行 —— 左边时间、右边操作。
      // 原来这两块各占一行（时间一行、操作一行），每条白吃 30px 高度，
      // 还把右半边整片留空。flex 排完就跟手机 App 一样了。
      //
      // 层级：item(flex) > 内容列(block) > 行容器(block) > 上面那四块。
      // flex-wrap 必须加在**行容器**上（`>div>div:first-child`），加在外层
      // 只是把一整列当做一个 flex item，起不到并排的作用。
      css += '.dy-sheet [data-e2e="comment-item"]>div+div{'
        + 'flex:1 1 auto !important;min-width:0 !important}';
      css += '.dy-sheet [data-e2e="comment-item"]>div+div>div:first-child{'
        + 'display:flex !important;flex-wrap:wrap !important;align-items:center !important;'
        + 'align-content:flex-start !important;width:100% !important}';
      css += '.dy-sheet [class*="comment-item-info-wrap"]{'
        + 'flex:0 0 100% !important;width:100% !important;margin:0 0 14px !important}';
      // 昵称压到 27px、65% 白、常规字重：它只是「这是谁」，不该和正文抢。
      // 之前跟正文同亮度同级，一屏看下来整片糊成一块。
      css += '.dy-sheet [class*="comment-item-info-wrap"] span'
        + '{font-size:27px !important;line-height:1.3 !important;font-weight:400 !important;'
        + 'color:rgba(255,255,255,.65) !important}';
      // 正文 32px → 34px（≈14sp）、行高 1.52：中文长句在 1.45 下会发黏。
      css += '.dy-sheet .FduGc_lz{flex:0 0 100% !important;width:100% !important}';
      css += '.dy-sheet .FduGc_lz,.dy-sheet .FduGc_lz *'
        + '{font-size:34px !important;line-height:1.52 !important;'
        + 'color:rgba(255,255,255,.92) !important}';
      // 正文里的表情是 <img alt="[送心]">，页面按 16px 画 —— 混在 34px 的字里
      // 像一排小图标。按行高抬到 34px；正文配图（没有 alt 的那种）不动。
      css += '.dy-sheet .FduGc_lz img[alt^="["]{'
        + 'height:34px !important;width:auto !important;vertical-align:-5px !important;'
        + 'margin:0 3px !important}';
      // 时间/地区：左边，压到 42% 白，别和正文抢注意力
      css += '.dy-sheet .VAQA49VP{'
        + 'flex:0 0 auto !important;width:auto !important;margin:16px 0 0 !important}';
      css += '.dy-sheet .VAQA49VP,.dy-sheet .VAQA49VP span'
        + '{font-size:25px !important;line-height:1.3 !important;'
        + 'color:rgba(255,255,255,.42) !important}';
      // 回复/分享/点赞：靠右。图标 20px → 32px（≈13dp），间距交给 gap，
      // 页面原本靠 margin-left:10px 顶开，太挤。
      css += '.dy-sheet .Ctt1baq8{'
        + 'flex:1 1 0 !important;width:auto !important;margin:16px 0 0 !important;'
        + 'display:flex !important;justify-content:flex-end !important}';
      css += '.dy-sheet [class*="comment-item-stats-container"]{'
        + 'width:auto !important;height:auto !important;margin:0 !important;'
        + 'justify-content:flex-end !important;align-items:center !important;'
        + 'gap:26px !important}';
      css += '.dy-sheet [class*="comment-item-stats-container"] div,'
        + '.dy-sheet [class*="comment-item-stats-container"] p{'
        + 'margin:0 !important;display:flex !important;align-items:center !important;'
        + 'gap:8px !important}';
      // 点赞那一格里并排两个 p：第一个是「♡ 42」，第二个是同一个心形图标
      // 但没数字（页面画了却没用）。不摘掉的话右边会多出一个孤零零的心。
      css += '.dy-sheet [class*="comment-item-stats-container"] p+p'
        + '{display:none !important}';
      css += '.dy-sheet [class*="comment-item-stats-container"] span,'
        + '.dy-sheet [class*="comment-item-stats-container"] div'
        + '{font-size:26px !important;line-height:1.2 !important}';
      css += '.dy-sheet [class*="comment-item-stats-container"] svg'
        + '{width:32px !important;height:32px !important}';

      // 「展开 N 条回复」只有 12px（≈5sp）—— 小于系统最小可读字号，
      // 之前完全靠眼睛猜。给到 28px、蓝色，一眼能看出这是个能点的入口。
      // 页面在它里面还画了一条竖引线（26×1 的 div），放大后就是一道横杠，
      // 摘掉，只留文字和箭头。
      css += '.dy-sheet [class*="comment-reply-expand-btn"]{'
        + 'display:flex !important;align-items:center !important;padding:0 !important;'
        + 'width:100% !important;margin-top:18px !important;'
        + 'font-size:28px !important;line-height:1.3 !important;'
        + 'color:rgba(109,166,255,.9) !important}';
      css += '.dy-sheet [class*="comment-reply-expand-btn"]>div:first-child'
        + '{display:none !important}';
      css += '.dy-sheet [class*="comment-reply-expand-btn"] span'
        + '{font-size:28px !important}';

      // 列表滚到输入栏顶边时是硬裁的，最后一行经常「切一半」，看着像被输入框
      // 压住了。铺一层从透明到抽屉底色的渐变，内容自己淡出，比硬切干净。
      // 定位在输入栏之上（bottom = 输入栏高度），不挡点击，也不挡输入栏本身。
      css += '.dy-sheet::after{content:"" !important;position:absolute !important;'
        + 'left:0 !important;right:0 !important;bottom:92px !important;height:72px !important;'
        + 'pointer-events:none !important;z-index:5 !important;'
        + 'background:linear-gradient(to bottom,rgba(22,24,35,0),rgba(22,24,35,1)) !important}';

      // ---- 底部输入栏 ----
      // 容器只有 46px（≈19dp）高，比一根手指还细，点起来像在戳一条缝。
      // 改成 92px 外框 + 68px 胶囊：胶囊自己画底色（页面那层渐变留着当过渡），
      // 提示文字 28px，右侧三个图标 40px。
      css += '.dy-sheet [class*="comment-input-container"]{'
        + 'height:92px !important;box-sizing:border-box !important;'
        + 'padding:12px 28px !important;flex:0 0 auto !important}';
      css += '.dy-sheet [class*="comment-input-inner-container"]{'
        + 'height:68px !important;box-sizing:border-box !important;'
        + 'border-radius:34px !important;background:rgba(255,255,255,.09) !important;'
        + 'padding:0 24px !important;display:flex !important;align-items:center !important}';
      css += '.dy-sheet [class*="comment-input-container"] span'
        + '{font-size:28px !important;line-height:1.3 !important}';
      css += '.dy-sheet [class*="commentInput-right-ct"]{'
        + 'display:flex !important;align-items:center !important;gap:20px !important}';
      css += '.dy-sheet [class*="commentInput-right-ct"] svg'
        + '{width:40px !important;height:40px !important}';
      css += '.dy-sheet input,.dy-sheet textarea,.dy-sheet [contenteditable]'
        + '{font-size:26px !important}';
    }
    if (CFG.trueFullscreen) {
      // 真全屏：播放器、容器、video 一起拉到 100vh，再用 cover 铺满。
      // 网页版全屏只把系统栏藏了，画面还是按 16:9 那一块算的 ——
      // 在 20:9 的机器上底下就会留一大条黑边。
      css += 'html.dy-fs [data-e2e="slideList"],html.dy-fs .slider-video,'
        + 'html.dy-fs .playerContainer,html.dy-fs .xgplayer,'
        + 'html.dy-fs .xg-video-container,html.dy-fs video{height:100vh !important}';
      css += 'html.dy-fs video{object-fit:cover !important}';
      // 全屏时不看信息区：文字压在画面最底边，和手势条打架
      css += 'html.dy-fs [data-e2e="video-info"]{opacity:0 !important;pointer-events:none !important}';
    }
    return css;
  }

  function trimPlayerChrome() {
    if (!CFG.trimPlayer && !CFG.hideSwitchArrows && !CFG.hideOwnRail
      && !CFG.hideExtras && !CFG.fullBleed && !CFG.bigInfo
      && !CFG.sheetComments && !CFG.trueFullscreen) return;
    ensureStyle(playerChromeCss());
  }

  /* ---------------------------------------- 7.5 评论区：右侧抽屉 → 底部抽屉 */
  //
  // 网页版的评论区是排在播放器右边的一列：开评论 = 把视频压窄，再往右让出 336px。
  // 手机竖屏上这等于视频突然变成半屏宽，非常难看。
  //
  // 这里不接管评论内容，只改「外面那层壳」的定位：
  // 找到评论框（LookModalFrameFast）的父容器，给它加 .dy-sheet，
  // 由 CSS 把它钉成「满宽 + 贴底 + 顶圆角」。
  //
  // 为什么改父容器而不是评论框自己：评论框是 position:absolute，
  // 它的包含块就是那个父容器（父容器带 transform，即使 transform 是单位矩阵
  // 也会成为包含块）。只改子元素的话 left/right 的 100% 还是按 336px 算。
  //
  // 页面重新打开评论会重建这层壳，所以每次 tick 都要检查一次（只查一个选择器，很便宜）。

  var sheetApplied = false;
  // 当前套着壳的那层容器。拆壳只认它 —— 「关掉的一瞬间又点开」时，
  // 定时器里那一刀不能砍到新壳上。
  var sheetWrap = null;
  // 套壳前 wrap 的父节点。套壳时会把 wrap 挪到 [data-e2e="slideList"] 下
  // （那个层 position:absolute、y=0，不受 swiper 位移影响），拆壳时必须挪回去，
  // 否则页面自己关评论时会找不到它，留下一个永不消失的空壳。
  var sheetWrapHome = null;
  // 收起动画结束后的拆壳定时器
  var sheetTimer = 0;
  // 打开前量到的视频区真实高度（页面写的是内联 px，不等于 100vh）
  var sheetBaseH = 0;
  // 收起期间「强制高度」的撤除定时器
  var holdTimer = 0;
  // 注：「关掉后不要又被套回来」原先用 sheetSuppressUntil（时间窗）实现，
  // 现在统一由 window.__DY_WANT_SHEET__ 表达意图（false = 用户不要抽屉），
  // 时间窗那套已经删了，别再引回来。

  /**
   * 只留「评论」那一页。
   *
   * 抽屉顶部是 semi-tabs：AI 图标 / TA的作品 / 评论 / 相关推荐。
   * 页面默认激活哪页不稳定，经常是「TA的作品」—— 于是点开评论看到的是
   * 作品卡片瀑布流，根本不是评论。按文本找到其余 tab 用 display:none 摘掉，
   * 让 semi-tabs 只剩一个选项，页面自然落到评论那一页。
   *
   * 类名带 hash 但 semi-tabs-tab 这段可读；用 classList 加自己的标记避免重复扫。
   */
  function trimCommentTabs() {
    if (!CFG.simpleCommentTabs) return;
    var bar = document.querySelector('[class*="semi-tabs-bar"]');
    if (!bar) return;
    if (bar.__dyTrimmed) return;
    var tabs = [].slice.call(bar.querySelectorAll('[class*="semi-tabs-tab"]'));
    var kept = 0;
    tabs.forEach(function (t) {
      var text = (t.textContent || '').trim();
      if (text === '评论') {
        kept++;
        return;
      }
      // 第一个 tab 是空文本的 AI 图标，同样要去掉
      t.style.setProperty('display', 'none', 'important');
    });
    if (!kept) return;   // 没找到「评论」就别动，等下一轮
    bar.__dyTrimmed = true;
    LOG.info('SHEET', '评论 tab 精简：保留「评论」，去掉 ' + (tabs.length - kept) + ' 个');
  }

  /**
   * 抽屉开合时同步改播放器盒子的高度。
   *
   * fillPlayer() 给 xg-video-container 写的是内联 height:100vh !important，
   * 样式表里的 !important 盖不过内联 !important，只能在这里原样改回去。
   * 不加这一步的话，视频还是 100vh，抽屉浮在画面上，只是「遮住」而不是「挤上去」。
   */
  function setSheetVideo(on) {
    if (!CFG.sheetPushVideo) return;
    // 打开之前先把视频区的真实高度量下来：收起要回到这个值，而不是写死
    // 100vh —— 页面自己的高度是 1688px，和 100vh 差 11px，用 100vh 收尾
    // 会在最后一帧往下顿一下。
    if (on && !document.documentElement.classList.contains('dy-sheet-open')) {
      sheetBaseH = measureFeedHeight();
      // 标记当前这一条：dy-sheet-geo 在场时，其余 feed-item 一律 visibility:hidden，
      // 免得视频区缩小的过程中把下一条画面漏出来。换视频后标记要重新打，
      // 所以先把旧的清干净。
      document.querySelectorAll('.dy-active-slide').forEach(function (el) {
        el.classList.remove('dy-active-slide');
      });
      var act = document.querySelector('[data-e2e="feed-active-video"]');
      var cur = act && act.closest ? act.closest('[data-e2e="feed-item"]') : null;
      if (cur) cur.classList.add('dy-active-slide');
    }
    if (on) {
      // 收起途中又要打开：先把「钉住高度」的那条撤掉，否则两条 !important
      // 打架（同权重、后写的赢），打开动画会被钉死不动。
      clearTimeout(holdTimer);
      holdTimer = 0;
      document.documentElement.classList.remove('dy-sheet-closing');
      document.documentElement.classList.add('dy-sheet-geo');
    }
    document.documentElement.classList.toggle('dy-sheet-open', !!on);
    var v = activeVideo();
    if (!v) return;
    var node = v;
    for (var i = 0; i < 5 && node && node !== document.body; i++) {
      if (node.__dyPBox) {
        try {
          node.style.setProperty('height',
            on ? (100 - SHEET_VH) + 'vh' : (sheetBaseH ? sheetBaseH + 'px' : '100vh'), 'important');
        } catch (e) {}
        return;
      }
      node = node.parentElement;
    }
  }

  /**
   * 视频区（feed-item）在「没有抽屉」时的真实高度。
   * 页面自己写的是内联 px（实测 1688），和 window.innerHeight 差 11px。
   */
  function measureFeedHeight() {
    var it = document.querySelector('[data-e2e="feed-item"]');
    if (!it) return 0;
    var h = it.getBoundingClientRect().height;
    return h > 100 ? Math.round(h) : 0;
  }

  function commentFrame() {
    // 类名是打包后的 hash，但 LookModalFrameFast 这一段是可读的（页面自己 debug 用）
    var all = document.querySelectorAll('[class*="LookModalFrameFast"]');
    for (var k = 0; k < all.length; k++) {
      // 被我们主动关掉的抽屉：等页面新建实例，别再套壳
      if (!all[k].getAttribute('data-dy-closing')) return all[k];
    }
    var el = null;
    // 兜底：从评论列表往上找
    var list = document.querySelector('[data-e2e="comment-list"]');
    if (!list) return null;
    var cur = list;
    for (var i = 0; i < 8 && cur; i++) {
      // 别从「已经关掉的」那棵旧树里再把抽屉套回来：
      // 页面关评论只是把它挪回右侧、淡出，DOM 还在，宽度又是 336。
      if (cur.getAttribute && cur.getAttribute('data-dy-closing')) return null;
      var r = cur.getBoundingClientRect();
      if (r.width > 260 && r.width < 700 && r.height > innerHeight * 0.5) return cur;
      cur = cur.parentElement;
    }
    return null;
  }

  /**
   * 拆掉「抽屉壳」：类、开关、播放器高度一起复位。
   *
   * 必须单独一个函数，因为 `.dy-sheet` 自带 position:absolute / height:58vh /
   * 深色背景 —— 它**不依赖** `dy-sheet-open`。只清掉 dy-sheet-open 的话，那
   * 58vh 的抽屉还死死压在下半屏（里面是页面垂死的评论节点），用户看到的就是
   * 「关不掉」或者「下半屏被另一条视频占住」。
   */
  function dropSheet(reason) {
    clearTimeout(sheetTimer);
    sheetTimer = 0;
    hideNextSlide(false);
    var shells = document.querySelectorAll('.dy-sheet');
    for (var i = 0; i < shells.length; i++) {
      shells[i].classList.remove('dy-sheet');
      // 挪回原父节点：页面关评论时要从原位置摘面板，留在这边会变成幽灵节点
      if (shells[i] === sheetWrap && sheetWrapHome) {
        try { sheetWrapHome.appendChild(shells[i]); } catch (e) {}
      }
    }
    sheetWrap = null;
    sheetWrapHome = null;
    // 几何类和高度类要一起摘：只摘 dy-sheet-open 的话 top:100% 还在，
    // 抽屉会留在屏幕外一条，下一次打开还会从错的位置开始动。
    document.documentElement.classList.remove('dy-sheet-geo');
    // 壳彻底拆了才停补偿：关闭动画那一整段 geo 还在，画面要一直钉着。
    window.__DY_PIN_VIDEO__ = false;
    stopPinVideo();
    // geo 一撤，CSS 那条「只显示当前条」的约束也跟着失效；标记顺手清掉，
    // 免得下次翻页后还挂在旧节点上。
    document.querySelectorAll('.dy-active-slide').forEach(function (el) {
      el.classList.remove('dy-active-slide');
    });
    // 收起动画那条路会自己排期撤「钉住高度」，这里只兜底那些没走动画的拆壳
    if (!holdTimer) document.documentElement.classList.remove('dy-sheet-closing');
    setSheetVideo(false);
    sheetApplied = false;
    // 把「想要抽屉」的意图一起清掉 —— 否则 1 秒轮询会照着旧 wrap 重新套壳。
    window.__DY_WANT_SHEET__ = false;
    LOG.info('SHEET', '摘掉抽屉壳（' + reason + '）');
  }

  /** 只摘「不是当前这把」的壳。关掉的一瞬间又打开时用来清残留。 */
  function dropStrayShell() {
    var shells = document.querySelectorAll('.dy-sheet');
    for (var i = 0; i < shells.length; i++) {
      if (shells[i] !== sheetWrap) shells[i].classList.remove('dy-sheet');
    }
  }

  /**
   * 带动画收起抽屉。
   *
   * 只撤「高度类」，几何类留着：抽屉还挂在视频区下沿，视频区用 SHEET_MS
   * 长回 100vh 的这段时间里它顺着下沿滑出屏幕 —— 所以**页面那边先别关**，
   * 面板内容要跟着一起滑走才像回事。真正的关闭动作由 pageClose 在动画结束
   * 后执行（点页面自己的评论按钮 toggle / 点抽屉的 ×）。
   *
   * 顺序反了会怎样：页面在同一帧把面板节点卸掉，我们手上就只剩一块空壳，
   * 画面变成「评论瞬间消失、视频自己长出来」—— 那就是用户说的生硬。
   *
   * 到 SHEET_MS 之后还要再等一帧才拆壳：页面 toggle 是异步渲染的，紧跟着拆
   * 会让面板闪回桌面版右侧那条 336 窄条。
   */
  function beginClose(reason, pageClose) {
    var root = document.documentElement;
    window.__DY_WANT_SHEET__ = false;
    sheetApplied = false;
    var shell = document.querySelector('.dy-sheet');
    if (!root.classList.contains('dy-sheet-open') && !shell) {
      if (pageClose) pageClose();
      return false;
    }
    var seq = window.__DY_OPEN_SEQ__ || 0;
    var waitRetry = 0;
    clearTimeout(sheetTimer);
    // 接管收起过程的高度：页面自己也有一套高度动画（内联 px 每帧改，总共
    // 约 600ms），比 SHEET_MS 慢一倍，还会在收尾往回顿。把它钉在打开前的
    // 真实高度上，这段过渡就只跑我们这一条。
    root.style.setProperty('--dy-sheet-h', (sheetBaseH || window.innerHeight) + 'px');
    root.classList.add('dy-sheet-closing');
    setSheetVideo(false);
    // 抽屉的 top 恒等于视频区的下沿，所以只要壳还在，下一条视频就被它盖着。
    // 反之：壳一摘，视频区如果还没长满，底部就会露出「下一条视频」一小条。
    // 所以摘壳的时机不看固定时长，看视频区是不是真的回到原位（最多等 450ms）。
    sheetTimer = setTimeout(function waitFull() {
      if ((window.__DY_OPEN_SEQ__ || 0) !== seq
          || document.documentElement.classList.contains('dy-sheet-open')) {
        // 这 SHEET_MS 里用户又点开了：新壳别动，只把旧壳摘掉
        dropStrayShell();
        sheetTimer = 0;
        return;
      }
      var target = sheetBaseH || window.innerHeight;
      var item = document.querySelector('[data-e2e="feed-item"]');
      var full = !item || item.getBoundingClientRect().height >= target - 2;
      waitRetry += SHEET_MS / 15;
      if (!full && waitRetry < 450) {
        sheetTimer = setTimeout(waitFull, SHEET_MS / 15);
        return;
      }
      // 抽屉已经滑到屏幕外了，这时候关页面谁也看不见
      if (pageClose) {
        try { pageClose(); } catch (e) { LOG.err('beginClose', e); }
      }
      sheetTimer = setTimeout(function () {
        sheetTimer = 0;
        dropSheet(reason);
        // 兜底：页面没吃下刚才那一刀（比如它的关闭绑在 pointerdown 上，
        // click 捕获拦不住）。这时壳已经摘了，面板还开着，必须补一次 toggle，
        // 否则会留下「评论浮在桌面上、壳没了」的怪状态。
        if (pageSheetVisible()) {
          var b = findCommentToggle();
          if (b) {
            try { b.click(); } catch (e) {}
            LOG.info('SHEET', '页面没关掉，补一次 toggle');
          }
        }
      }, 80);
      // 页面那套高度动画（约 600ms）还在跑，它每帧写内联 px；强制高度提前
      // 撤掉会被拽回它的中间值，看着像往回顿一下。等它跑完再撤。
      clearTimeout(holdTimer);
      holdTimer = setTimeout(function () {
        holdTimer = 0;
        document.documentElement.classList.remove('dy-sheet-closing');
      }, 700);
    }, SHEET_MS);
    LOG.info('SHEET', '收起抽屉（' + reason + '）');
    return true;
  }

  /**
   * 临时藏掉「下一条视频」。
   *
   * 点开评论后，页面会先自己把视频压到半屏（它自己的入场动画），我们的壳要
   * 等 JS 往返 + 扫 DOM 才能套上，中间有一百毫秒左右的空窗：视频已经矮了，
   * 下半屏露出的却是下一条视频，看着像画面撕裂。这期间把下一个 feed-item
   * 的 visibility 关掉，露出来的就是底色。
   */
  var hiddenNext = [];

  function hideNextSlide(on) {
    if (!on) {
      hiddenNext.forEach(function (el) { el.style.visibility = ''; });
      hiddenNext = [];
      return;
    }
    syncActiveSlide();
  }

  /**
   * 把 .dy-active-slide 标记（以及内联 visibility）跟住页面当前的 active 条。
   *
   * 必须在抽屉在场期间**每帧**调用，不能在套壳那一刻打一次就完事：
   * 刷视频时点评论，swiper 的翻页过渡还没结束，[data-e2e="feed-active-video"]
   * 会在我们套壳之后的几十毫秒里从旧条切到新条。标记如果停在旧条上，CSS 的
   * `:not(.dy-active-slide){visibility:hidden}` 就会把**正要显示的新条藏掉**，
   * 而旧条已经滑出屏外 —— 屏幕上一帧一帧全空，抽屉还跟着乱跳，就是「鬼畜」。
   * 实测翻页中打开会有连续 19 帧看不到任何视频。
   *
   * 这里只在标记真的换了节点时改 DOM，稳定后每帧零写入。
   */
  function syncActiveSlide() {
    var act = document.querySelector('[data-e2e="feed-active-video"]');
    var cur = act && act.closest ? act.closest('[data-e2e="feed-item"]') : null;
    if (!cur) return;
    var marked = document.querySelector('.dy-active-slide');
    if (marked !== cur) {
      if (marked) {
        marked.classList.remove('dy-active-slide');
        // 之前被藏、现在成了 active 的那一条要放出来
        for (var i = hiddenNext.length - 1; i >= 0; i--) {
          if (hiddenNext[i] === marked) {
            marked.style.visibility = '';
            hiddenNext.splice(i, 1);
          }
        }
      }
      cur.classList.add('dy-active-slide');
    }
    // 找到的是 active，但 hideNextSlide(true) 早先按旧 active 藏过它
    for (var j = hiddenNext.length - 1; j >= 0; j--) {
      if (hiddenNext[j] === cur) {
        cur.style.visibility = '';
        hiddenNext.splice(j, 1);
      }
    }
    var p = cur.parentElement;
    if (!p) return;
    // 不能只藏 nextElementSibling：抽屉从 50vh 提到 58vh 后，入场动画
    // 露出来的可能是第二、第三条（swiper 会按自己的节奏挪 transform）。
    [].forEach.call(p.children, function (s) {
      if (s === cur || s.style.visibility === 'hidden') return;
      s.style.visibility = 'hidden';
      hiddenNext.push(s);
    });
  }

  // ------------------------------------------------ 开合期间锁住视频画面
  //
  // 开/关评论时页面会把视频区压到 42vh，swiper 跟着重排 translateY。它分几帧
  // 更新 wrapper 的 translate 和 item 的 offsetTop，中间态对不上 —— 逐帧实测
  // video 的视觉 y 会从 0 甩到 -2300 再弹回，这就是「开合闪烁」。
  //
  // 页面每帧重写 wrapper 的 transform，从外部冻结它做不到（试过，被覆盖）。
  // 改成在 **video 元素自己**身上叠一个反向 translateY：每帧读它的实际视觉 y，
  // 把补偿调到让 y=0。只动 video 的 transform，不碰任何高度/布局，swiper 照常
  // 跑它的动画，用户看到的画面却始终钉在原位。补偿量记在元素上，关评论拆壳时
  // 归零（__DY_PIN_VIDEO__ 由 comments() 打开、dropSheet() 关闭）。
  // 另一半是去掉 item/slideList 的 height 过渡 —— 它让 offsetTop 逐帧漂移，
  // 是甩动的根源；抽屉改用自己的 transform 做滑入滑出，顺滑感不丢。
  var pinnedVideos = [];
  var pinRaf = 0;
  var pinObserver = null;
  var pinObservedWrap = null;

  function stopPinVideo() {
    if (pinRaf) { cancelAnimationFrame(pinRaf); pinRaf = 0; }
    if (pinObserver) { pinObserver.disconnect(); pinObserver = null; }
    pinObservedWrap = null;
    for (var i = 0; i < pinnedVideos.length; i++) {
      try { pinnedVideos[i].el.style.removeProperty('transform'); } catch (e) {}
      pinnedVideos[i].el.__dyPin = 0;
      pinnedVideos[i].el.__dyPinTracked = false;
    }
    pinnedVideos = [];
  }

  // 每帧把 video 的视觉 y 拉回 0。必须读到 swiper 当帧写入的最新 transform：
  // MutationObserver 的回调在 DOM 属性改动后、浏览器渲染前跑，正好卡在 swiper
  // 写完、屏幕还没画之间。纯 rAF 会和 swiper 的回调抢顺序，慢一帧就留尖峰。
  function observeActiveWrap() {
    if (!pinObserver || !window.MutationObserver) return;
    var act = document.querySelector('[data-e2e="feed-active-video"]');
    var item = act && act.closest ? act.closest('[data-e2e="feed-item"]') : null;
    var wrap = item ? item.parentElement : null;
    if (!wrap || wrap === pinObservedWrap) return;
    try { pinObserver.disconnect(); } catch (e) {}
    try { pinObserver.observe(wrap, { attributes: true, attributeFilter: ['style'] }); } catch (e) {}
    pinObservedWrap = wrap;
  }

  function pinVideoStep() {
    if (!window.__DY_PIN_VIDEO__) { stopPinVideo(); return; }
    observeActiveWrap();
    var v = document.querySelector('[data-e2e="feed-active-video"] video');
    if (v) {
      if (!v.__dyPinTracked) { pinnedVideos.push({ el: v }); v.__dyPinTracked = true; }
      // 连补两遍：写 transform 后立刻读 rect 会强制同步布局，第二遍能拿到
      // 刚才那次补偿的结果。swiper 甩动是单帧突变，一遍追不平，两遍就够。
      for (var pass = 0; pass < 2; pass++) {
        var y = v.getBoundingClientRect().y;           // 视觉 y（含已有补偿）
        var cur = v.__dyPin || 0;
        var want = -y + cur;                            // 让视觉 y 回到 0
        if (Math.abs(want - cur) > 0.3) {
          try { v.style.setProperty('transform', 'translateY(' + want + 'px)', 'important'); } catch (e) {}
          v.__dyPin = want;
        }
      }
    }
  }

  function startPinVideo() {
    window.__DY_PIN_VIDEO__ = true;
    if (pinRaf) return;
    // rAF 兜底：即使 swiper 这一帧没动（比如动画已停），也要把残留偏差收干净
    (function tick() {
      if (!window.__DY_PIN_VIDEO__) { pinRaf = 0; return; }
      pinVideoStep();
      // 帧尾再补一次：swiper 的 rAF 可能排在我们后面，帧尾读到的才是当帧
      // 最终位置。setTimeout 排在渲染后，把那一点的残差收掉。
      setTimeout(function () {
        if (window.__DY_PIN_VIDEO__) pinVideoStep();
      }, 0);
      pinRaf = requestAnimationFrame(tick);
    })();
    // MutationObserver：swiper 每次改 wrapper 的 transform / item 的 style 时同步补偿
    if (window.MutationObserver) {
      pinObserver = new MutationObserver(function () {
        if (window.__DY_PIN_VIDEO__) pinVideoStep();
      });
      // 只盯 swiper 的位移层（active item 的父级）：它就是甩动的源头，
      // 每次改 transform 都触发同步补偿。不监听 body 全子树 —— 抖音高频写
      // 样式会把回调打爆。没找到就只靠 rAF 兜底。
      var act = document.querySelector('[data-e2e="feed-active-video"]');
      var item = act && act.closest ? act.closest('[data-e2e="feed-item"]') : null;
      var wrap = item ? item.parentElement : null;
      if (wrap) {
        try { pinObserver.observe(wrap, { attributes: true,
          attributeFilter: ['style'] }); } catch (e) {}
      }
    }
  }

  /**
   * 面板一挂载就套壳。
   *
   * 以前只靠 1 秒的兜底轮询，实测从点按到套壳有 400~800ms 的空窗：这段时间里
   * 用户看到的是网页版原样（面板贴在最右边、视频还全屏），然后「啪」地跳一下。
   * 这里在点击后连追十几帧，面板一出现就接住（实测 100ms 上下）。
   */
  function pumpSheet(ms) {
    var t0 = Date.now();
    var budget = ms || 700;
    // 套壳之后还要再跟一段：翻页动画没停时点评论，active 会在套壳后继续
    // 从旧条切到新条，标记必须跟着挪，否则新条被 CSS 藏掉（见 syncActiveSlide）。
    var syncUntil = t0 + 1400;
    (function step() {
      patchCommentSheet();
      var geo = document.documentElement.classList.contains('dy-sheet-geo');
      if (geo) syncActiveSlide();
      // 壳套上了、且 active 已经稳定（或超时）才停。geo 在场期间即使
      // sheetApplied 也要继续跟，这是「翻页中打开」不出黑屏的关键。
      // 翻页回弹期间 patch 会主动延后套壳，预算放到 1400ms 才不会先断帧。
      if (Date.now() > syncUntil || (!geo && sheetApplied)
          || (!geo && Date.now() - t0 > budget + 400)) return;
      if (window.requestAnimationFrame) requestAnimationFrame(step);
      else setTimeout(step, 16);
    })();
  }

  /**
   * 抽屉右上角的 × 是页面自己的关闭键。点它之后页面会把面板从布局里摘掉，
   * 但 `.dy-sheet` 还挂在旧 wrap 上 —— 所以在这里当场清，不等下一轮轮询。
   *
   * 只认「抽屉顶部 90px 内 + 右侧 90px 内」，不会误伤评论正文和输入框。
   * 不 preventDefault：关闭动作仍由页面自己做，我们只同步自己的壳。
   */
  function watchSheetClose() {
    document.addEventListener('click', function (e) {
      if (!CFG.sheetComments) return;
      // 判存在用的是 DOM，不是 sheetApplied：后者可能因为「壳在但状态没同步」
      // 而短暂为 false，那样点 × 就只能等 1 秒轮询兜底了。
      var s = document.querySelector('.dy-sheet');
      if (!s || !s.contains(e.target)) return;
      var r = s.getBoundingClientRect();
      if (e.clientY - r.top > 90) return;
      if (r.right - e.clientX > 90) return;
      var el = e.target;
      // 先按住页面这一帧，让抽屉带着面板内容滑下去，滑完再点它真正关闭。
      // 不拦的话页面会当场把面板卸掉，只剩一块空壳在动。
      e.preventDefault();
      e.stopPropagation();
      beginClose('点右上角 ×', function () {
        try { el.click(); } catch (err) {}
      });
    }, true);
  }

  /** 页面侧评论面板是不是还开着（用来给「推迟关闭」兜底）。 */
  function pageSheetVisible() {
    var f = commentFrame();
    if (!f) return false;
    var r = f.getBoundingClientRect();
    return r.width > 260 && r.height > window.innerHeight * 0.3;
  }

  function patchCommentSheet() {
    if (!CFG.sheetComments) return;
    if (window.__DY_WANT_SHEET__ === false) return;
    var frame = commentFrame();
    if (!frame) {
      // 抽屉已经从页面摘掉（点 ×、点背景、翻页…）：壳要一起拆干净，
      // 否则 `.dy-sheet` 的 58vh 会留在下半屏 —— 那正是「评论区位置被下一条
      // 视频占住」的来源。这里只兜底，点 × 的当场清理见 watchSheetClose()。
      //
      // 但必须用 sheetApplied 当条件，不能看「有没有 .dy-sheet」：
      // 刚点开评论、面板还在渲染（慢机可能要一秒多）时同样没有 frame，
      // 这时如果顺手 dropSheet，会把 want 清成 false —— 面板随后渲染出来
      // 也再不会被套壳，表现为「评论区半屏、没被接管」。
      if (sheetApplied) beginClose('抽屉已从页面移除');
      return;
    }
    // 先把「TA的作品 / AI / 相关推荐」摘掉，只留评论那一页
    trimCommentTabs();
    var wrap = frame.parentElement;
    if (!wrap || wrap === document.body) return;
    // 换视频后旧抽屉可能还挂着 dy-sheet 类但 dy-sheet-open 已经掉了。
    // 这里不能因为有类就早退，否则 setSheetVideo(true) 永远不再执行，
    // 表现为「评论区开了、视频却还是全屏」。
    if (wrap.classList.contains('dy-sheet')) {
      sheetWrap = wrap;
      if (!sheetApplied) {
        setSheetVideo(true);
        sheetApplied = true;
        hideNextSlide(false);   // 壳已经盖住了，下一条可以放出来
      }
      return;
    }
    // 评论区是「点开才挂载」的，1 秒扫一次很容易撞上还没量出尺寸的那一帧。
    // 这时候给它套 .dy-sheet（width:100%），包含块宽度还是 0，会直接把它压成隐身。
    if (wrap.querySelector('video')) return;      // 别把播放器容器当抽屉
    if (wrap.getBoundingClientRect().width < 120) return;  // 还没渲染出来，下一轮再说

    // 把 wrap 搬到稳定的 slideList 层下。原来它挂在 feed-active-video 里，
    // 那层的父级带 swiper 的 translateY（翻页 / 回弹时每帧在变），抽屉用
    // top:100% 锚着它就会跟着上下抽动 —— 就是「点开评论鬼畜」的根因。
    // slideList 是 position:absolute、y=0，视频区缩到 42vh 后它的下沿
    // 正好是抽屉该在的位置，视觉完全一样，但不再吃 swiper 位移。
    var list = document.querySelector('[data-e2e="slideList"]');
    if (list && wrap.parentElement !== list) {
      sheetWrapHome = wrap.parentElement;
      list.appendChild(wrap);
    } else {
      sheetWrapHome = wrap.parentElement;
    }
    wrap.classList.add('dy-sheet');
    sheetWrap = wrap;
    // 加完立刻量一次：真被压平了就说明这层不是抽屉外壳，撤回，别把页面搞坏
    var after = wrap.getBoundingClientRect();
    if (after.width < 120) {
      wrap.classList.remove('dy-sheet');
      if (sheetWrapHome) sheetWrapHome.appendChild(wrap);   // 归位，别丢节点
      sheetWrap = null;
      return;
    }
    if (!sheetApplied) {
      LOG.info('SHEET', '评论区 → 底部抽屉 ' + Math.round(after.width) + 'x'
        + Math.round(after.height) + ' @' + Math.round(after.left) + ',' + Math.round(after.top));
      setSheetVideo(true);
      hideNextSlide(false);   // 壳已经盖住了，下一条可以放出来
    }
    sheetApplied = true;
  }

  /* --------------------------------------- 7.6 旧按钮的幽灵触摸 */
  //
  // 右侧那一列是 opacity:0 —— 对脚本来说这样才「看不见但仍能 .click()」，
  // 代价是它一样能吃到用户的真实手指。实测点在旧评论图标上，评论区会偷偷打开，
  // 还带出悬浮标签。这里只在捕获阶段拦「isTrusted 的事件」：
  //   - 真手指 / adb tap / CDP 输入 → isTrusted=true，掐掉；
  //   - 我们自己的 __DY.point(...).click() → isTrusted=false，放行。
  // 于是「脚本点得着、人手点不着」两件事可以同时成立。

  var ghostLogged = false;

  function isGhostTarget(node) {
    if (!node || node.nodeType !== 1 || !node.getAttribute) return false;
    var e2e = node.getAttribute('data-e2e') || '';
    var cls = (node.className || '').toString();
    var isRail = e2e === 'video-player-digg' || e2e === 'feed-comment-icon'
      || e2e === 'video-player-collect' || e2e === 'video-player-share'
      || e2e === 'video-avatar' || e2e === 'live-avatar'
      || e2e === 'feed-follow-icon';
    var isArrow = cls.indexOf('xgplayer-playswitch') >= 0
      || cls.indexOf('ai-douyin-entry') >= 0;
    if (!isRail && !isArrow) return false;
    if (isRail) {
      // 评论列表里评论者的头像也叫 video-avatar，不能连它们一起封掉
      var r = node.getBoundingClientRect();
      if (r.width < 1 || r.left < (window.innerWidth || 0) * 0.6) return false;
    }
    var cs = getComputedStyle(node);
    return cs.opacity === '0' || cs.visibility === 'hidden' || cs.pointerEvents === 'none';
  }

  function blockGhostRail(e) {
    if (!CFG.blockGhostRail || !e.isTrusted) return;
    // 原生 Dock 合成的那一次触摸由 MainActivity 打这个标记，别误伤自己
    if (window.__DY_NATIVE_TAP__ && Date.now() < window.__DY_NATIVE_TAP__) return;
    var el = e.target;
    for (var depth = 0; el && el.nodeType === 1 && depth < 6; depth++) {
      if (isGhostTarget(el)) {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        if (!ghostLogged) {
          ghostLogged = true;
          LOG.info('RAIL', '旧按钮的幽灵触摸已拦掉 (' + e.type + ')');
        }
        return;
      }
      el = el.parentElement;
    }
  }

  /* ------------------------------------------------ 7.7 真全屏 */

  function setFullscreen(on) {
    if (!CFG.trueFullscreen) return false;
    var root = document.documentElement;
    var next = !!on;
    if (root.classList.contains('dy-fs') === next) return next;
    // 全屏要和抽屉互斥：dy-fs 把所有容器拉到 100vh，和抽屉的挤压规则打架。
    // 直接整套拆掉（连几何类一起），否则 top:100% 会把抽屉留在屏幕外一条。
    if (next && (root.classList.contains('dy-sheet-open')
        || document.querySelector('.dy-sheet'))) {
      dropSheet('进全屏');
    }
    root.classList.toggle('dy-fs', next);
    LOG.info('FULL', '全屏 ' + (next ? '开：视频铺满 100vh' : '关'));
    // 视口高度没变，但播放器盒子变了，让播放器重新量一次
    setTimeout(refreshLayout, 60);
    return next;
  }

  /* ------------------------------------------- 8. 推荐流入口 / 精简模式 */
  // 目标：打开应用看到的是「一个能上下滑的视频」，而不是精选九宫格。
  //
  // 落地页必须是 /?recommend=1（推荐），不能是「精选网格里点开一条」：
  // 精选那个全屏 modal 的「下一个」只在这张卡片所属的合集/相同作者里循环，
  // 实测连刷十条全是同一个人 —— 看代码时会以为翻页成功了，
  // 因为时长、封面确实在变，变的只是同一个作者的下一集。
  //
  // 推荐流的翻页是真的按推荐队列走的，每滑一次换一个作者。

  var FEED_KEY = 'dy_feed_entered';
  var FEED_TRY_KEY = 'dy_feed_try_at';
  var feedStopped = false;

  function onRecommendPage() {
    return /[?&]recommend=/.test(location.search || '');
  }

  function isFeedPage() {
    // 三种落地形态：
    //   /?recommend=1            推荐流（沉浸，用它）
    //   /jingxuan?modal_id=<id>  精选网格上盖一层播放器（只作为兜底）
    //   /video/<id>              独立页，下面还挂着列表
    if (onRecommendPage()) return !!activeVideo();
    if (/[?&]modal_id=/.test(location.search)) {
      return !!document.querySelector('.modal-video-container');
    }
    var p = location.pathname || '';
    return p.indexOf('/video/') >= 0 || p.indexOf('/note/') >= 0;
  }

  // sessionStorage 在部分内核/隐私模式下会抛异常，包一层，日志功能不能因此挂掉
  function storeGet(k) {
    try { return typeof sessionStorage !== 'undefined' ? sessionStorage.getItem(k) : null; }
    catch (e) { return null; }
  }

  function storeSet(k, v) {
    try { if (typeof sessionStorage !== 'undefined') sessionStorage.setItem(k, v); } catch (e) {}
  }

  /**
   * 网格页里第一条「不是广告」的视频卡片。
   * 注意：精选页的卡片是带 href 属性的 <div>（waterfall-videoCardContainer），
   * 不是 <a>，所以不能写 a[href*=...]——那样在真机上永远是 0 个。
   */
  function firstCard() {
    var links = document.querySelectorAll('[href*="/video/"], [href*="/note/"]');
    var limit = Math.min(links.length, 160);
    var fallback = null;
    for (var i = 0; i < limit; i++) {
      var a = links[i];
      if (!a || a.__dyHidden) continue;
      if (a.tagName === 'LINK' || a.tagName === 'SCRIPT') continue;
      var r = a.getBoundingClientRect();
      // 图标、作者名这类小链接跳过，只要卡片级别的
      if (r.width < 80 || r.height < 80) continue;

      // 广告卡（角标写着「广告」）点进去是落地页，不是推荐流
      var scope = a.parentElement;
      var guard = 0;
      while (scope && guard++ < 3 && scope.getBoundingClientRect().height < 140) {
        scope = scope.parentElement;
      }
      var txt = scope ? (scope.textContent || '') : '';
      if (txt.length < 600 && txt.indexOf('广告') >= 0) continue;

      if (r.top <= (window.innerHeight || 0) * 1.2) return a;
      if (!fallback) fallback = a;
    }
    return fallback;
  }

  /**
   * 关掉「登录后免费畅享高清视频」那个全屏弹窗。
   *
   * 未登录访问精选页时抖音一定会弹它，而且它 z-index 9999 盖住所有卡片；
   * 程序化点击卡片虽然能穿透（click() 不看遮挡），但进去之后播放器还是被盖着。
   * 关闭按钮的 class 是 hash 过的，只能按几何位置找：面板右上角、40x40 左右、带 svg。
   *
   * 用户点了「去登录」之后要暂停这件事，否则弹窗刚出来就被关掉，登录永远走不完。
   */
  var overlaySweepPausedUntil = 0;

  function pauseOverlaySweep(ms) {
    overlaySweepPausedUntil = now() + (ms || 180000);
    LOG.info('OVERLAY', '暂停自动清理遮挡层 ' + Math.round((ms || 180000) / 1000) + 's（用户去登录了）');
  }

  /**
   * 用户一碰屏幕，就先别清理遮挡层。
   *
   * 这是「点了点赞没反应」的真正原因：未登录时点任何互动按钮，
   * 抖音会弹「登录后即可点赞」，而我们的清洁工 600ms 一轮，
   * 弹窗刚出来就被关掉 —— 从用户视角看就是「按了没用」。
   * 现在只要有触摸发生，就给弹窗 12 秒活命时间，够他看清并扫码。
   */
  function holdOverlaySweepOnGesture() {
    var until = now() + 12000;
    if (until > overlaySweepPausedUntil) overlaySweepPausedUntil = until;
  }

  document.addEventListener('pointerdown', holdOverlaySweepOnGesture,
    { capture: true, passive: true });
  document.addEventListener('touchstart', holdOverlaySweepOnGesture,
    { capture: true, passive: true });

  function dismissLoginModal(force) {
    if (!force && now() < overlaySweepPausedUntil) return false;
    var panel = document.querySelector('#login-panel-new');
    if (!panel) return false;
    var box = panel.getBoundingClientRect();
    if (box.width < 40) return false;   // 已隐藏/未布局

    var hit = null;
    var nodes = panel.querySelectorAll('div,button,span,a');
    var limit = Math.min(nodes.length, 200);
    for (var i = 0; i < limit; i++) {
      var el = nodes[i];
      var r = el.getBoundingClientRect();
      if (r.width < 18 || r.width > 56 || r.height < 18 || r.height > 56) continue;
      if (r.top - box.top > 120) continue;         // 只认面板顶部那一排
      if (!el.querySelector('svg')) continue;      // 关闭是个叉形 svg
      // 不比较「离面板右边缘多远」：有些弹窗（比如「登录后即可点赞」）
      // #login-panel-new 比里面的对话框宽得多，按右边缘过滤会一个都匹配不到。
      // 顶部那一排里最靠右的那个就是 ✕。
      if (!hit || r.left > hit.getBoundingClientRect().left) hit = el;
    }
    if (!hit) {
      LOG.info('FEED', '登录弹窗在，但没定位到关闭按钮');
      return false;
    }
    try { hit.click(); } catch (e) { LOG.err('dismissLoginModal', e); return false; }
    LOG.info('FEED', '已关闭登录弹窗');
    return true;
  }

  /** 告诉原生层「已经在推荐流里了」，可以撤掉黑幕。只报一次。 */
  var readyReported = false;
  function notifyFeedReady() {
    if (readyReported) return;
    readyReported = true;
    var b = window.__DY_NATIVE__;
    if (b && typeof b.feedReady === 'function') {
      try { b.feedReady(); } catch (e) { LOG.err('feedReady', e); }
    }
  }

  /** 播放器挂上来之后再报 ready，太快撤黑幕会先看到一层空壳。 */
  function waitFeedReady() {
    (function tick(tries) {
      if (isFeedPage()) { notifyFeedReady(); return; }
      if (tries > 30) return;
      setTimeout(function () { tick(tries + 1); }, 300);
    })(0);
  }

  /**
   * 兜底入口：精选网格里点开第一条的 modal 形态。
   *
   * 只在推荐流路由没落地（被重定向回 /jingxuan）时才会走到这里。
   * 内容质量不如推荐流（同一个作者会连着播），但总比停在九宫格强。
   */
  function enterModalFeed() {
    var card = firstCard();
    if (!card) return false;
    var href = card.getAttribute('href') || '';
    var id = (href.match(/\/(?:video|note)\/(\d+)/) || [])[1];
    if (!id) return false;
    var target = 'https://www.douyin.com/jingxuan?modal_id=' + id;
    LOG.info('FEED', '推荐流没落地，退回精选 modal ' + target);
    try {
      location.href = target;
    } catch (e) {
      LOG.err('enterModalFeed', e);
      return false;
    }
    waitFeedReady();
    return true;
  }

  function enterFeed(force) {
    if (isFeedPage()) {
      notifyFeedReady();
      return 'already';
    }
    if (!force && !CFG.autoFeed) return 'off';

    // 已经站在推荐页上了，只是视频还没挂上来。
    // 这时候绝对不能重新导航 —— 否则每轮轮询都刷一次页面，永远加载不完。
    if (onRecommendPage()) {
      waitFeedReady();
      return 'waiting';
    }
    if (/[?&]modal_id=/.test(location.search)) {
      waitFeedReady();
      return 'waiting';
    }

    // 用户自己按返回键退回网格页时不要再把他弹回去，只认第一次自动进入。
    // 例外：刚跳过去这段时间内还没落到推荐流，说明路由被拦了 —— 走兜底。
    if (!force && storeGet(FEED_KEY) === '1') {
      var triedAt = parseInt(storeGet(FEED_TRY_KEY) || '0', 10) || 0;
      if (!(now() - triedAt < 40000)) return 'skipped';
      LOG.info('FEED', '推荐路由没落地（当前 ' + location.pathname + '），改用精选 modal 兜底');
      return enterModalFeed() ? 'clicked' : 'waiting';
    }

    // 未登录时首页一定有全屏登录弹窗挡着，先关掉
    dismissLoginModal(true);

    storeSet(FEED_KEY, '1');
    storeSet(FEED_TRY_KEY, String(now()));
    LOG.info('FEED', '进入推荐流 ' + CFG.recommendUrl);
    try {
      location.href = CFG.recommendUrl;
    } catch (e) {
      LOG.err('enterFeed.href', e);
      return 'waiting';
    }
    waitFeedReady();
    return 'clicked';
  }

  /** 首页首屏是异步渲染的，500ms 一轮往后等，最多等 30 秒。 */
  function startFeedPoll() {
    if (feedStopped || !CFG.autoFeed) return;
    var tries = 0;
    (function tick() {
      if (feedStopped) return;
      var r = enterFeed(false);
      if (r !== 'waiting') { feedStopped = true; return; }
      if (++tries >= 60) {
        feedStopped = true;
        LOG.info('FEED', '等了 ' + tries + ' 次仍未找到视频卡片（页面里 a[href*="/video/"] 共 '
          + document.querySelectorAll('a[href*="/video/"]').length + ' 个），放弃自动进入');
        return;
      }
      setTimeout(tick, 500);
    })();
  }

  // ---------------- 精简模式：只留视频，把「网页壳」收起来 ----------------

  var hiddenEls = [];

  function hideEl(el, why) {
    if (!el || el.nodeType !== 1 || el.__dyHidden) return false;
    var r = el.getBoundingClientRect();
    el.__dyHidden = 1;
    el.style.setProperty('display', 'none', 'important');
    hiddenEls.push(el);
    LOG.info('FOCUS', '隐藏' + why + ' <' + tagOf(el) + '> '
      + Math.round(r.width) + 'x' + Math.round(r.height));
    return true;
  }

  /**
   * 左侧竖向导航（精选/AI抖音/关注/我的…）。
   * 不按 class 名找 —— 那几个名字是 hash 过的；只按「贴左、窄、高、里面有一排图标」
   * 这类几何特征匹配，改版也不会立刻失效。
   */
  function hideLeftRail() {
    if (document.querySelector('[data-dy-rail]')) return;
    // 分层往下找：导航栏可能挂在 body 下面第 2、3 层，不可能更深
    var cands = document.querySelectorAll('body > *, body > * > *, body > * > * > *, nav, aside');
    var limit = Math.min(cands.length, 200);
    var viewW = window.innerWidth || 0;
    var viewH = window.innerHeight || 0;
    for (var i = 0; i < limit; i++) {
      var el = cands[i];
      if (!el || el.__dyHidden) continue;
      var st = window.getComputedStyle(el);
      if (!st) continue;
      if (st.display === 'none' || st.visibility === 'hidden') continue;
      var r = el.getBoundingClientRect();
      if (r.width < 40 || r.width > viewW * 0.24) continue;
      if (r.left > 44) continue;
      if (r.height < viewH * 0.35) continue;
      // 静态定位的窄条很少见，只有确实是一排链接时才认
      var links = el.querySelectorAll('a').length;
      var icons = el.querySelectorAll('img,svg').length;
      if (links < 2 && icons < 4) continue;
      if (st.position === 'static' && links < 3) continue;
      el.setAttribute('data-dy-rail', '1');
      hideEl(el, '左侧导航');
      return;
    }
  }

  /**
   * 顶部搜索框。
   * 注意不隐藏整条 header —— 登录按钮在里面，藏了就再也登不上了。
   */
  function hideSearchBox() {
    var inputs = document.querySelectorAll('input:not([type=hidden])');
    var limit = Math.min(inputs.length, 20);
    var viewW = window.innerWidth || 0;
    for (var i = 0; i < limit; i++) {
      var inp = inputs[i];
      // 一个输入框只处理一次：藏掉外层之后它的 rect 会变 0，再扫下去会一层层
      // 往上吞，最后可能把整条 header（含登录按钮）一起吃掉
      if (!inp || inp.__dySearch) continue;
      var hint = (inp.getAttribute('placeholder') || '') + (inp.getAttribute('aria-label') || '');
      if (!/搜索|search/i.test(hint)) continue;
      var node = inp.parentElement;
      var guard = 0;
      // 从输入框往上找那个「只有搜索框那么大」的容器
      while (node && node !== document.body && guard++ < 5) {
        var r = node.getBoundingClientRect();
        if (r.width > 60 && r.width < viewW * 0.62 && r.height < 100) {
          inp.__dySearch = 1;
          hideEl(node, '搜索框');
          return;
        }
        node = node.parentElement;
      }
      inp.__dySearch = 1;
    }
  }

  /**
   * 页面顶部整条 header（搜索框 + 通知 / 消息 / 头像）。
   *
   * 推荐流里它是浮在最上面的「网页版外壳」：画面上方多出一条 56px 的黑带，
   * 手指想暂停时还容易先点到搜索框。精简模式下一律收掉。
   *
   * 按 <header> 标签找，不按 class —— 抖音改版改的是 hash 过的 class 名。
   * 藏掉它不影响登录：入口是自己的左上角状态条 + 点赞拉起的浮层，
   * 登录态判断以 cookie 为准（MainActivity.cookieSaysLoggedIn）。
   */
  function hidePageHeader() {
    var hds = document.querySelectorAll('header, [role="banner"]');
    var limit = Math.min(hds.length, 4);
    var viewW = window.innerWidth || 0;
    for (var i = 0; i < limit; i++) {
      var el = hds[i];
      if (!el || el.__dyHidden) continue;
      var r = el.getBoundingClientRect();
      if (r.top > 8) continue;                     // 只认贴顶那条
      if (r.height < 24 || r.height > 96) continue;
      if (r.width < viewW * 0.5) continue;
      hideEl(el, '顶栏');
      return;
    }
  }

  var focusTick = 0;

  /**
   * 把 header 留下的「空位」也收掉。
   *
   * header 自己 display:none 之后，顶部还留着两层东西：
   *   1) 一个 fixed 的外壳（高 56、底色 #161823），压在最上面画出一条黑带；
   *   2) 更外层的容器用 padding-top:56px 给 header 让位 —— header 没了就是纯留白。
   *
   * 两个都不按 class 找（hash 过的），按「header 的父级」和「padding-top 数值」判断。
   */
  var flatEls = [];

  function flattenHeader() {
    if (!CFG.fullBleed) return;
    var h = document.querySelector('header');
    if (!h || !h.__dyHidden) return;     // header 还在的时候别乱动布局

    var box = h.parentElement;
    if (box && box !== document.body && box.children.length === 1
      && !box.getAttribute('data-dy-flatbox')) {
      box.setAttribute('data-dy-flatbox', '1');
      box.style.setProperty('display', 'none', 'important');
      LOG.info('BLEED', '收掉顶栏外壳');
    }

    // 再往外一层还有个 position:fixed 的空壳（高 56、底色 #161823），
    // header 和紧邻它的 box 都藏了，这层还在，状态栏右侧就留一条黑带，
    // 而左侧 72px 露出视频画面 —— 两块颜色割裂，看着就是「状态栏一块黑的」。
    // 用 hideEl 走同一套隐藏记录，关精简模式时能被还原。
    var viewW = window.innerWidth || 0;
    var shell = box;
    for (var s = 0; s < 4 && shell && shell !== document.body; s++, shell = shell.parentElement) {
      var scs = window.getComputedStyle(shell);
      if (scs.position !== 'fixed') continue;
      var sr = shell.getBoundingClientRect();
      if (sr.top > 8 || sr.height < 24 || sr.height > 96) continue;
      if (sr.width < viewW * 0.5) continue;
      hideEl(shell, '顶栏固定壳');
      break;
    }

    var n = box;
    for (var i = 0; i < 3 && n && n !== document.body; i++, n = n.parentElement) {
      if (n.getAttribute('data-dy-flat') === '1') continue;
      var pt = parseFloat(window.getComputedStyle(n).paddingTop) || 0;
      if (pt <= 4) continue;
      n.setAttribute('data-dy-flat', '1');
      n.style.setProperty('padding-top', '0', 'important');
      LOG.info('BLEED', '顶部留白 ' + Math.round(pt) + 'px -> 0 <' + tagOf(n) + '>');
    }
  }

  /**
   * 「看相关」那一块。
   *
   * 它没有 data-e2e，只能按文案认：一个以「看相关」开头、带头像小图标的胶囊。
   * 上一级容器（图标 + 两行字，约 79x108）一起拿掉；只藏文字会留下一个空图标。
   */
  function hideRelatedEntry() {
    if (!CFG.hideExtras) return;
    var els = document.querySelectorAll('div, span, a');
    var limit = Math.min(els.length, 5000);
    var done = 0;
    for (var i = 0; i < limit && done < 4; i++) {
      var el = els[i];
      if (el.children.length > 4) continue;
      var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (t.indexOf('看相关') !== 0 || t.length > 12) continue;
      var r = el.getBoundingClientRect();
      // 已经藏掉的元素 rect 是 0，天然跳过；这里不再靠「有没有标记」早退 ——
      // 全屏 / 切清晰度之后播放器会整块重建，新元素没有任何标记，
      // 早退的话它会一直挂在右下角。
      if (r.width <= 0 || r.height <= 0) continue;
      // 往上找到「图标 + 文字」那一格：再往上就是整列按钮了，不能吞
      var box = el;
      for (var k = 0; k < 3 && box.parentElement; k++) {
        var br = box.getBoundingClientRect();
        if (br.height > 60 && br.height < 180 && br.width < 140) break;
        box = box.parentElement;
      }
      var bb = box.getBoundingClientRect();
      if (bb.width >= 140 || bb.height >= 180) continue;
      box.setAttribute('data-dy-gone', '1');
      box.style.setProperty('display', 'none', 'important');
      LOG.info('FOCUS', '去掉「看相关」 ' + Math.round(bb.width) + 'x' + Math.round(bb.height));
      done++;
    }
  }

  /**
   * 右侧竖列的「数字」标签。
   *
   * hideOwnRail 把 6 颗按钮 opacity:0 了，但每颗按钮下面还挂着数字
   * （「1.3万」「1425」「575」…）是兄弟节点，不在 opacity 范围内，
   * 于是屏幕上飘着一列孤零零的数字 —— 就是那个关不掉的「评论 ✕」标签。
   *
   * 只能藏数字节点本身，不能藏按钮块：按钮块还得被脚本合成触摸点到
   * （点赞 / 收藏 / 转发都靠它）。数字节点没有 data-e2e、没有 svg、
   * 高度不到 60px，用这三点就能和图标区分开。
   */
  function hideRailCounts() {
    if (!CFG.hideOwnRail) return;
    var sw = document.querySelector('[class*="immersive-player-switch-on-"]');
    if (!sw) return;
    var btns = sw.querySelectorAll('[data-e2e="video-player-digg"],'
      + '[data-e2e="feed-comment-icon"],[data-e2e="video-player-collect"],'
      + '[data-e2e="video-player-share"]');
    var hidden = 0;
    [].forEach.call(btns, function (btn) {
      [].forEach.call(btn.querySelectorAll('div,span'), function (el) {
        if (el.getAttribute('data-e2e') || el.getAttribute('data-dy-count') === '1') return;
        if (el.querySelector('svg,img')) return;   // 图标，留着
        var t = (el.textContent || '').replace(/\s+/g, '');
        if (!t || t.length > 12 || !/[0-9]/.test(t)) return;
        var r = el.getBoundingClientRect();
        if (r.width < 20 || r.height < 8 || r.height > 70) return;
        // 数字本身 opacity 跟随父按钮，视觉上本来就淡；这里彻底拿掉命中
        el.setAttribute('data-dy-count', '1');
        el.style.setProperty('display', 'none', 'important');
        hidden++;
      });
    });
    if (hidden > 0 && !hideRailCounts.logged) {
      hideRailCounts.logged = true;
      LOG.info('FOCUS', '藏掉右侧竖列的数字标签 ' + hidden + ' 处');
    }
  }

  function applyFocus(force) {
    if (!CFG.focusMode || document.hidden) return;
    // 页面播放时 DOM 一直在变，这里每 3 次调度（约 1.8s）扫一次就够了，
    // 没必要为了几个静态 shell 每个 tick 都做一遍 getComputedStyle
    if (!force && ++focusTick % 3 !== 0) return;
    hideLeftRail();
    hidePageHeader();
    flattenHeader();
    hideSearchBox();
    hideRelatedEntry();
    hideRailCounts();
  }

  function setFocus(on) {
    CFG.focusMode = !!on;
    if (CFG.focusMode) {
      applyFocus(true);
    } else {
      for (var i = 0; i < hiddenEls.length; i++) {
        var el = hiddenEls[i];
        if (!el || !el.style) continue;
        el.style.removeProperty('display');
        el.__dyHidden = 0;
      }
      hiddenEls.length = 0;
      var rails = document.querySelectorAll('[data-dy-rail]');
      for (var j = 0; j < rails.length; j++) rails[j].removeAttribute('data-dy-rail');
      // 顶部留白和「看相关」是另一套标记，一起还原
      var flats = document.querySelectorAll('[data-dy-flat],[data-dy-flatbox]');
      for (var f = 0; f < flats.length; f++) {
        var fe = flats[f];
        fe.style.removeProperty('padding-top');
        fe.style.removeProperty('display');
        fe.removeAttribute('data-dy-flat');
        fe.removeAttribute('data-dy-flatbox');
      }
      flatEls.length = 0;
      var gones = document.querySelectorAll('[data-dy-gone]');
      for (var g = 0; g < gones.length; g++) {
        gones[g].style.removeProperty('display');
        gones[g].removeAttribute('data-dy-gone');
      }
    }
    LOG.info('FOCUS', CFG.focusMode ? '精简模式开' : '精简模式关');
    return CFG.focusMode;
  }

  /* --------------------------------------------------- 8.1 播放器铺满 */
  // 桌面播放器固定 16:9（900x506），竖屏视口却有 2000 高，于是上下各空一大块。
  // 把视频的容器拉到 100vh：竖视频按宽度铺满后能占满整个屏幕高度，横视频也不裁切。
  // 只在「明显留白」时才动手，且必须验证改完之后真的变高了，否则退回上一层。

  var fillLogs = 0;

  function fillPlayer() {
    if (!CFG.fillVideo) return;
    var v = activeVideo();
    if (!v) return;

    var viewW = window.innerWidth || 0;
    var viewH = window.innerHeight || 0;
    // 评论抽屉开着的时候只铺上方 40%。这里必须跟着改，否则换片时 fillPlayer()
    // 会把 setSheetVideo() 设的高度又写回 100vh，抽屉重新压到画面上。
    var sheetOpen = CFG.sheetPushVideo
      && document.documentElement.classList.contains('dy-sheet-open');
    var targetH = sheetOpen ? (100 - SHEET_VH) + 'vh' : '100vh';
    var minRatio = sheetOpen ? 0.4 : 0.8;

    // 必须真的是播放器，而不是网格里的悬停预览：宽过半屏、高过两成屏
    var vr = v.getBoundingClientRect();
    if (vr.width < viewW * 0.5 || vr.height < viewH * 0.2) return;

    if (!v.__dyFit) {
      v.__dyFit = 1;
      try { v.style.setProperty('object-fit', 'contain', 'important'); } catch (e) {}
    }

    var node = v.parentElement;
    var guard = 0;
    while (node && node !== document.body && guard++ < 4) {
      if (node.__dyPBox) {
        // 同一个容器可能在外套着抽屉时被复用，状态变了就跟着改
        if (!!node.__dySheetFit !== sheetOpen) {
          try { node.style.setProperty('height', targetH, 'important'); } catch (e) {}
          node.__dySheetFit = sheetOpen;
        }
        return;
      }
      var r = node.getBoundingClientRect();
      // 宽度不够就是右栏/侧栏，往上走；已经够高说明铺过了
      if (r.width < viewW * 0.5 || r.height > viewH * 0.55) {
        node = node.parentElement;
        continue;
      }
      node.style.setProperty('height', targetH, 'important');
      node.style.setProperty('max-height', 'none', 'important');
      node.__dySheetFit = sheetOpen;
      var after = node.getBoundingClientRect();
      if (after.height >= viewH * minRatio) {
        node.__dyPBox = 1;
        // 打上标记类：抽屉开合时它的高度也要跟着做过渡（见 .dy-pbox 规则）
        node.classList.add('dy-pbox');
        // 每个视频换一个容器，只留前三条，避免刷屏
        if (fillLogs++ < 3) {
          LOG.info('FILL', '播放器容器 ' + Math.round(r.width) + 'x' + Math.round(r.height)
            + ' -> ' + Math.round(after.width) + 'x' + Math.round(after.height)
            + ' <' + tagOf(node) + '>');
        }
        return;
      }
      // 改完没变高（父级有 overflow 或 padding 撑开），撤掉再试上一层
      node.style.removeProperty('height');
      node.style.removeProperty('max-height');
      node = node.parentElement;
    }
  }

  /* ------------------------------------------------------- 8.1 遮挡层清理 */

  /**
   * 「滚动鼠标 / 键盘上下键查看更多推荐视频」这个新手引导遮罩。
   * 它是全屏 mask + 一个「我知道了」，第一次进沉浸页必现，不点掉会一直挡着。
   * 有稳定 id，不用靠猜。
   */
  function dismissGuide() {
    var mask = document.querySelector('#douyin-web-recommend-guide-mask');
    if (!mask || mask.__dyGone) return false;
    mask.__dyGone = 1;
    var btns = mask.querySelectorAll('div,button,span,a');
    var limit = Math.min(btns.length, 60);
    for (var i = 0; i < limit; i++) {
      if ((btns[i].textContent || '').trim() === '我知道了') {
        try { btns[i].click(); } catch (e) {}
        break;
      }
    }
    try { mask.style.setProperty('display', 'none', 'important'); } catch (e) {}
    LOG.info('OVERLAY', '已关闭「滑动切换视频」新手引导');
    return true;
  }

  /**
   * 弹幕层压在画面顶部一大片，只想要视频本身时是纯噪音。
   *
   * 注意不是 .danmakuContainer（那是评论区那个输入区的外壳，已隐藏），
   * 真正飘字的是播放器里的 .danmu 层——它有 pointer-events:none，
   * 所以用 elementFromPoint 反查时永远命中下面的 <video>，很容易误判成「弹幕烧进了视频」。
   *
   * 开关现在交给了原生 Dock：默认关，用户点「弹幕」再打开。
   */
  var danmuOn = false;

  function applyDanmu() {
    var nodes = document.querySelectorAll('[class~="danmu"]');
    var limit = Math.min(nodes.length, 8);
    for (var i = 0; i < limit; i++) {
      var el = nodes[i];
      try {
        if (danmuOn) el.style.removeProperty('display');
        else el.style.setProperty('display', 'none', 'important');
      } catch (e) {}
    }
    // 评论输入区外壳永远藏掉，它不是弹幕
    var shells = document.querySelectorAll('.danmakuContainer');
    for (var j = 0; j < shells.length && j < 8; j++) {
      try { shells[j].style.setProperty('display', 'none', 'important'); } catch (e) {}
    }
  }

  /** 原生 Dock 的「弹幕」开关。不传参数就取反。 */
  function setDanmu(on) {
    var next = (on === undefined) ? !danmuOn : !!on;
    var changed = next !== danmuOn;
    danmuOn = next;
    applyDanmu();
    if (changed) LOG.info('TOOL', '弹幕' + (danmuOn ? '开' : '关'));
    return danmuOn;
  }

  function sweepOverlays() {
    if (document.hidden) return;
    dismissGuide();
    applyDanmu();
    dismissLoginModal(false);
  }

  /* ------------------------------------------------------------- 8.2 调度 */

  var pending = 0;

  function work() {
    pending = 0;
    sweepPromos();
    sweepOverlays();
    tuneVideos();
    applyFocus();
    fillPlayer();
    trimPlayerChrome();
    keepRate();
    applyVolume();
    refreshCollection();
  }

  /**
   * 合集入口要不要点亮。
   *
   * 找它得扫几千个节点，每 600ms 扫一次太贵，所以按片缓存：
   * 换片时重扫；一旦找到就信它不再扫；一直没找到就最多试 6 轮
   * （信息区通常比 video 晚一点点渲染出来，等两秒基本都能等到）。
   */
  var collKey = '';
  var collTries = 0;
  var collTick = 0;

  function refreshCollection() {
    var v = activeVideo();
    var key = v ? (v.currentSrc || '') : '';
    if (key !== collKey) {
      collKey = key;
      collTries = 0;
      collTick = 0;
      hasColl = false;
    }
    collTick++;
    if (hasColl) {
      // 点亮之后也要隔几轮复核：打开/关闭选集面板会让信息区重新渲染，
      // 入口可能就没了，胶囊一直亮着会骗人。
      if (collTick % 8 !== 0) return;
      hasColl = !!collectionEntry();
      return;
    }
    if (collTries >= 6) return;
    collTries++;
    hasColl = !!collectionEntry();
  }

  function schedule() {
    if (pending) return;
    pending = setTimeout(work, 600);
  }

  function observe() {
    if (!window.MutationObserver) return;
    new MutationObserver(schedule).observe(document.documentElement, {
      childList: true,
      subtree: true
    });
    // 页面替换 <head> 里的 viewport 时重新校正
    new MutationObserver(function () { applyViewport(); }).observe(
      document.head || document.documentElement,
      { childList: true }
    );
  }

  function boot() {
    LOG.info('BOOT', 'readyState=' + document.readyState
      + ' title=' + (document.title || '(空)').slice(0, 40));
    applyViewport();
    applyFocus(true);
    sweepPromos();
    sweepOverlays();
    tuneVideos();
    fillPlayer();
    trimPlayerChrome();
    applyVolume();
    observe();
    if (CFG.blockGhostRail) {
      ['click', 'pointerdown', 'mouseover'].forEach(function (type) {
        document.addEventListener(type, blockGhostRail, true);
      });
    }
    if (CFG.sheetComments) watchSheetClose();
    window.addEventListener('resize', applyViewport, { passive: true });
    // 全屏切换 / 旋转都会改视口高度，播放器要重新铺
    window.addEventListener('resize', refreshLayout, { passive: true });
    // 底部原生进度条的数据源，页面这边只负责算秒数。
    // 250ms 一次：跨进程调用还不算贵，但足够让原生在两次真值之间做短外推；
    // 500ms 时外推要跑半秒，任何一次校正都会被眼睛抓到。
    setInterval(function () {
      // 翻页时 active 可能在套壳之后才切到下一条。pumpSheet 那时已经停了，
      // 标记若不跟着挪，CSS 的 :not(.dy-active-slide) 会把新条藏掉（黑屏）。
      // 挂在已有的 250ms 进度定时器上，壳在场时才做，代价可忽略。
      if (document.querySelector('.dy-sheet')) syncActiveSlide();
    }, 250);
    setInterval(pushProgress, 250);
    // 换片 / 页内点击都会把 volume 和 playbackRate 重置（静音的视频一点就自己开声），
    // 而 work() 只在 DOM 变动时才跑 —— 页面稳定播放时可能几秒都不动一次。
    // 这一条每秒兜一次底，代价只是两次属性比较。
    setInterval(function () {
      if (document.hidden) return;
      applyVolume();
      keepRate();
      patchCommentSheet();
    }, 1000);
    startFeedPoll();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }

  /* ------------------------------------------------------ 10. 原生 UI 接口 */
  // 控制条上的每个按钮最终都落到这里。原则：能让页面自己干的，就点它自己的按钮，
  // 不去猜接口、不伪造事件序列。

  /** 当前最可能「正在看」的视频：可见面积最大的那个。 */
  function activeVideo() {
    var vs = document.querySelectorAll('video');
    var best = null;
    var bestArea = 0;
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      if (r.bottom <= 0 || r.top >= window.innerHeight) continue;
      if (r.right <= 0 || r.left >= window.innerWidth) continue;
      var area = Math.max(0, r.width) * Math.max(0, r.height);
      if (area > bestArea) { bestArea = area; best = vs[i]; }
    }
    return best;
  }

  function togglePlay() {
    var v = activeVideo();
    if (!v) return 'none';
    try {
      if (v.paused) {
        var p = v.play();
        // 自动播放被拒时会 reject，这里吞掉，交给日志去体现
        if (p && typeof p.catch === 'function') p.catch(function () {});
        return 'playing';
      }
      v.pause();
      return 'paused';
    } catch (e) {
      LOG.err('togglePlay', e);
      return 'none';
    }
  }

  /** 点赞在网页版就是「双击播放器」，没有独立按钮可直接点。 */
  function like() {
    var v = activeVideo();
    if (!v) return false;
    var r = v.getBoundingClientRect();
    var x = Math.min(Math.max(r.left + r.width / 2, 8), window.innerWidth - 8);
    var y = Math.min(Math.max(r.top + r.height / 2, 8), window.innerHeight - 8);
    var el = document.elementFromPoint(x, y) || v;
    try {
      el.dispatchEvent(new MouseEvent('dblclick', {
        bubbles: true, cancelable: true, view: window, detail: 2,
        clientX: Math.round(x), clientY: Math.round(y)
      }));
      LOG.info('LIKE', '双击 ' + Math.round(x) + ',' + Math.round(y) + ' on <' + tagOf(el) + '>');
      return true;
    } catch (e) {
      LOG.err('like', e);
      return false;
    }
  }

  // 抖音网页版的按钮带 data-e2e 测试钩子，比 hash 过的 class 名稳
  var COMMENT_SELECTORS = [
    '[data-e2e="comment-icon"]',
    '[data-e2e="feed-comment-icon"]',
    '[aria-label*="评论"]',
    '[title*="评论"]'
  ];

  /**
   * 找「视口内」那个评论按钮。
   *
   * swiper 会同时挂好几屏，querySelector 拿到的第一个可能是屏外旧视频的按钮，
   * 点了没反应 —— 必须按坐标过滤。开和关走同一个查找，避免两处逻辑跑偏。
   */
  function findCommentToggle() {
    var vh = window.innerHeight;
    for (var i = 0; i < COMMENT_SELECTORS.length; i++) {
      var nodes = document.querySelectorAll(COMMENT_SELECTORS[i]);
      for (var j = 0; j < nodes.length; j++) {
        var el = pointTarget(nodes[j]);
        var r = el.getBoundingClientRect();
        if (r.width < 8 || r.bottom <= 8 || r.top >= vh - 8) continue;
        el.__dySel = COMMENT_SELECTORS[i];
        return el;
      }
    }
    return null;
  }

  function comments() {
    // 正在收起（抽屉已经离开原位、还在往下滑）：撤销收起，**不要**再点页面
    // 按钮 —— 页面那边其实还没关，再点一次 toggle 就把它真关了，动画会当场
    // 断在原地。高度会自动从当前值过渡回去，看起来就是「滑一半又弹回来」。
    //
    // 收尾那一小段（页面已经关了、只等拆壳）不能撤销：那时没有面板可复用，
    // 撤销只会留下一把没人管的空壳。这种情况直接走下面的正常打开流程。
    if (sheetTimer && !document.documentElement.classList.contains('dy-sheet-open')
        && pageSheetVisible()) {
      clearTimeout(sheetTimer);
      sheetTimer = 0;
      window.__DY_WANT_SHEET__ = true;
      window.__DY_OPEN_SEQ__ = (window.__DY_OPEN_SEQ__ || 0) + 1;
      startPinVideo();
      patchCommentSheet();
      LOG.info('COMMENT', '收起途中又点开：撤销收起');
      return 'cancel-close';
    }
    // 原生侧以此为准：只有显式点过「打开评论」才允许把抽屉套壳
    window.__DY_WANT_SHEET__ = true;
    // 单调递增的「打开序号」。延迟拆壳时靠它判断这段时间里有没有人又开了评论 ——
    // 不能用 __DY_WANT_SHEET__ 判断：点抽屉右上角 × 时它还是 true（那是打开时设的），
    // 拿它当守卫会让 × 永远拆不掉壳。
    window.__DY_OPEN_SEQ__ = (window.__DY_OPEN_SEQ__ || 0) + 1;
    document.querySelectorAll('[data-dy-closing]').forEach(function (el) {
      el.removeAttribute('data-dy-closing');
    });
    var el = findCommentToggle();
    if (!el) {
      // 没找到按钮就是这次打开失败了，把意图收回来 —— 否则 sheetOpen()
      // 会一直报「开着」，原生 Dock 的评论按钮就变成点不动的空转。
      window.__DY_WANT_SHEET__ = false;
      LOG.info('COMMENT', '没匹配到可见评论按钮');
      return null;
    }
    // 点击的同一帧就启动画面锁定：页面收到 click 会立刻开始压视频，
    // swiper 从这一帧起甩动。补偿器必须比它更早站岗，第一帧才不会漏。
    startPinVideo();
    try {
      el.click();
    } catch (e) {
      window.__DY_WANT_SHEET__ = false;
      LOG.err('comments', e);
      return null;
    }
    // click 是同步的：页面在这一行里就已经改完 DOM 并触发 swiper 重排。
    // 立刻补一次，抢在浏览器绘制之前把画面钉住 —— 等 rAF 就晚了一帧。
    pinVideoStep();
    LOG.info('COMMENT', '点开评论区 via ' + (el.__dySel || '?'));
    // 面板是 React 挂载的，挂出来那一刻就要接住，别等 1 秒的兜底轮询。
    // 同时先藏掉下一条视频，填上「页面已经在压缩、我们的壳还没到」那段空窗。
    hideNextSlide(true);
    pumpSheet();
    return el.__dySel || 'unknown';
  }

  /**
   * 给原生层报一个「可以真点一下」的坐标。
   *
   * 播放器右侧那几个按钮（点赞 / 评论）挂在 xgplayer 上，只认真实输入事件：
   * el.click() 派发出去的 isTrusted=false，点了完全没反应（翻页箭头就没事，
   * 那个是纯 onClick；这两类按钮实现方式不一样）。
   * 所以这里只负责算坐标，触摸由 MainActivity 用 dispatchTouchEvent 合成，
   * 走的是和手指按下去完全相同的链路。
   *
   * 返回值："x,y,innerWidth"（都是 CSS 像素），找不到返回 ''。
   */
  var POINT_SELECTORS = {
    like: ['[data-e2e="video-player-digg"]'],
    comment: ['[data-e2e="feed-comment-icon"]', '[data-e2e="comment-icon"]'],
    collect: ['[data-e2e="video-player-collect"]'],
    share: ['[data-e2e="video-player-share"]'],
    avatar: ['[data-e2e="video-avatar"]', '[data-e2e="live-avatar"]'],
    // 关注是头像底下那颗「+」，本身高度是 0（视觉是绝对定位的徽标），
    // 真正的点击目标是它内部那个 span —— 见 pointTo() 里的兜底
    follow: ['[data-e2e="feed-follow-icon"]']
  };

  /** 元素自身没有尺寸时，往下找一个有尺寸的后代当代点目标。 */
  function pointTarget(el) {
    var r = el.getBoundingClientRect();
    if (r.width >= 8 && r.height >= 8) return el;
    var kids = el.querySelectorAll('*');
    for (var i = 0; i < kids.length && i < 12; i++) {
      var kr = kids[i].getBoundingClientRect();
      if (kr.width >= 8 && kr.height >= 8) return kids[i];
    }
    return el;
  }

  function point(kind) {
    // 合集入口是内容匹配出来的，不是固定选择器，单独算
    if (kind === 'collection') {
      var entry = collectionEntry();
      if (!entry) return '';
      var er = entry.getBoundingClientRect();
      if (er.width < 8 || er.height < 8) return '';
      var ex = Math.round(er.left + er.width / 2);
      var ey = Math.round(er.top + er.height / 2);
      if (ey < 8 || ey > window.innerHeight - 8) return '';
      LOG.info('POINT', 'collection -> ' + ex + ',' + ey);
      return ex + ',' + ey + ',' + Math.round(window.innerWidth);
    }
    var sels = POINT_SELECTORS[kind];
    if (!sels) return '';
    var vw = window.innerWidth;
    var vh = window.innerHeight;
    for (var s = 0; s < sels.length; s++) {
      var nodes = document.querySelectorAll(sels[s]);
      var limit = Math.min(nodes.length, 8);
      for (var i = 0; i < limit; i++) {
        var el = pointTarget(nodes[i]);
        var r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 8) continue;
        // swiper 会同时挂好几屏，只有当前这屏在视口里
        if (r.bottom <= 8 || r.top >= vh - 8) continue;
        if (r.right <= 8 || r.left >= vw - 8) continue;
        var x = Math.round(r.left + r.width / 2);
        // 右侧那几颗外面还套着一层数字（收藏 2.1万 / 转发 2.6万），
        // 整块高 130：直接取中心会点到数字上，图标在上三成，往那里点
        var y = Math.round(r.top + Math.min(r.height / 2, 32));
        LOG.info('POINT', kind + ' -> ' + x + ',' + y + ' <' + tagOf(el) + '>');
        return x + ',' + y + ',' + Math.round(vw);
      }
    }
    LOG.info('POINT', kind + ' 没找到可见按钮');
    return '';
  }

  /** 跳转到某个时间点。 */
  function seekTo(sec) {
    var v = activeVideo();
    if (!v) return false;
    if (!isFinite(v.duration) || v.duration <= 0) return false;
    var t = Math.max(0, Math.min(Number(sec) || 0, v.duration - 0.15));
    try {
      v.currentTime = t;
      LOG.info('SEEK', '跳到 ' + t.toFixed(1) + 's / ' + v.duration.toFixed(1) + 's');
      return true;
    } catch (e) {
      LOG.err('seekTo', e);
      return false;
    }
  }

  function seekToFraction(f) {
    var v = activeVideo();
    if (!v) return false;
    return seekTo((Number(f) || 0) * (v.duration || 0));
  }

  /* ------------------------------------------------ 9.9 播放器设置（原生接管） */
  // 页面底部那排控件被整体 display:none 掉了，所以「弹幕 / 清晰度 / 倍速 / 合集」
  // 的入口改由原生 Dock 提供。这里的职责只有两件：
  //   1) 读出当前值给 Dock 显示；
  //   2) 替用户点页面里本来就存在的真按钮 / 改 video 的属性。
  // 不猜接口、不拼签名、不绕过登录限制（1080P 仍然要求登录）。

  var RATES = [0.5, 0.75, 1, 1.25, 1.5, 2];
  var userRate = 1;

  /** 当前可见面积最大的播放器容器（列表里同时挂着好几个）。 */
  function activePlayer() {
    var best = null, area = -1;
    var list = document.querySelectorAll('.xgplayer');
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      var a = r.width * r.height;
      if (a > area) { area = a; best = list[i]; }
    }
    return area > 0 ? best : null;
  }

  /** 清晰度选项就摆在 .gear .virtual 里（高亮那项带 .selected）。 */
  function clarityItems() {
    var p = activePlayer() || document;
    var items = p.querySelectorAll('.xgplayer-playclarity-setting .virtual .item');
    if (!items.length) {
      items = document.querySelectorAll('.xgplayer-playclarity-setting .virtual .item');
    }
    return items;
  }

  function qualityOptions() {
    var items = clarityItems();
    var out = [];
    for (var i = 0; i < items.length; i++) {
      out.push({
        text: (items[i].textContent || '').replace(/\s+/g, ' ').trim(),
        on: items[i].classList.contains('selected')
      });
    }
    return out;
  }

  /**
   * 把「高清 1080P登录即享」这种长文案压成能塞进 Dock 胶囊的短标签。
   * 优先取分辨率（720P / 1080P），没有就取第一个词（智能 / 标清）。
   */
  function shortQuality(text) {
    var t = (text || '').replace(/\s+/g, ' ').trim();
    if (!t) return '';
    var m = t.match(/(\d{3,4})\s*P/i);
    if (m) return m[1] + 'P';
    return t.split(' ')[0].slice(0, 4);
  }

  function qualityLabel() {
    var items = clarityItems();
    for (var i = 0; i < items.length; i++) {
      if (!items[i].classList.contains('selected')) continue;
      return shortQuality(items[i].textContent) || '智能';
    }
    return '智能';
  }

  function setQuality(i) {
    var items = clarityItems();
    var el = items[Number(i) || 0];
    if (!el) return false;
    try { el.click(); } catch (e) { return false; }
    LOG.info('TOOL', '清晰度 -> ' + (el.textContent || '').replace(/\s+/g, ' ').trim());
    return true;
  }

  /**
   * Dock 上的「清晰度」胶囊：点一下切到下一档，不弹菜单。
   *
   * 为什么用循环而不是列表：播放器底部那排控件已经被 display:none，
   * 挂在上面的下拉菜单也跟着没了（点不开）；而这些 .item 节点本身还在 DOM 里，
   * 程序化 click() 照样生效。循环是最省事又不丢功能的做法。
   *
   * 「1080P 登录即享」这种需要登录的档位直接跳过，否则一点就弹登录框。
   */
  function cycleQuality() {
    var items = clarityItems();
    if (!items.length) return '';
    var cur = -1;
    for (var i = 0; i < items.length; i++) {
      if (items[i].classList.contains('selected')) { cur = i; break; }
    }
    for (var step = 1; step <= items.length; step++) {
      var idx = (cur + step + items.length) % items.length;
      var t = (items[idx].textContent || '').replace(/\s+/g, ' ').trim();
      if (t.indexOf('登录') >= 0) continue;
      try { items[idx].click(); } catch (e) { return ''; }
      LOG.info('TOOL', '清晰度切换到 ' + t);
      return shortQuality(t);
    }
    return '';
  }

  function rateOptions() {
    return RATES.slice();
  }

  function setRate(r) {
    var next = Number(r);
    if (!isFinite(next) || next <= 0) next = 1;
    userRate = next;
    var v = activeVideo();
    if (v) { try { v.playbackRate = userRate; } catch (e) {} }
    LOG.info('TOOL', '倍速 -> ' + userRate + 'x');
    return userRate;
  }

  /** Dock 上的「倍速」胶囊：0.5 → 0.75 → 1 → 1.25 → 1.5 → 2 → 回到 0.5。 */
  function cycleRate() {
    var i = 0;
    for (var k = 0; k < RATES.length; k++) {
      if (Math.abs(RATES[k] - userRate) < 0.01) { i = k; break; }
    }
    return setRate(RATES[(i + 1) % RATES.length]);
  }

  /** 换片之后页面会把 playbackRate 重置回 1，这里每轮把它找回来。 */
  function keepRate() {
    if (userRate === 1) return;
    var v = activeVideo();
    if (!v) return;
    if (Math.abs(v.playbackRate - userRate) < 0.01) return;
    try { v.playbackRate = userRate; } catch (e) {}
  }

  /* --------------------------------------------------------- 9.10 音量 */
  // 音量条在原生 Dock 上，这里只负责把值写到 <video> 上。
  //
  // 为什么不碰系统媒体音量：AudioManager.setStreamVolume 改的是全局音乐音量，
  // 用户调一次视频音量，铃声/通知/别的 App 全跟着变，很难解释。
  // <video>.volume + muted 是元素级的，只影响这一路播放，而且默认静音之后
  // 自动播放也更稳（浏览器对 muted 的自动播放是放行的）。
  //
  // 页面会在换片时把 volume 重置回 0.5 并取消 muted，所以每轮 work() 都补一次。

  var userVol = CFG.startMuted ? 0 : 0.6;
  var lastVol = 0.6;

  function clampVol(v) {
    var n = Number(v);
    if (!isFinite(n)) return 0;
    return Math.max(0, Math.min(1, n));
  }

  function applyVolume() {
    var vs = document.querySelectorAll('video');
    var muted = userVol <= 0.001;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      if (v.muted !== muted) {
        try { v.muted = muted; } catch (e) {}
      }
      // 静音时不去动 volume：留着原值，取消静音时才有个合理起点
      if (!muted && Math.abs(v.volume - userVol) > 0.01) {
        try { v.volume = userVol; } catch (e) {}
      }
    }
  }

  function setVolume(level) {
    var next = clampVol(level);
    var changed = Math.abs(next - userVol) > 0.005;
    userVol = next;
    if (userVol > 0.001) lastVol = userVol;
    applyVolume();
    // 拖条时会连续调用，只在百分比真的变了才记日志
    if (changed) LOG.info('TOOL', '音量 -> ' + Math.round(userVol * 100) + '%');
    return userVol;
  }

  /** 原生 Dock 上的喇叭图标：有声就静音，静音就回到上次的音量。 */
  function toggleMute() {
    return setVolume(userVol > 0.001 ? 0 : lastVol);
  }

  /* ------------------------------------------------------ 9.11 全屏 */
  // 全屏真正的「变大」是原生做的：Dock 收起、系统栏隐藏，WebView 自己占满整屏。
  // 页面这边只需要两件事：把视频容器重新铺一次（视口高度变了），
  // 以及顺手把页面自己的全屏按钮状态同步一下（有就点它，没有就算了）。
  //
  // 不点 [data-e2e="xgplayer-page-full-screen"]：那是浏览器的 Fullscreen API，
  // 在 WebView 里会把 video 提成一个原生 customView，Dock 和手势全没了。

  function refreshLayout() {
    applyViewport();
    fillPlayer();
    applyVolume();
    // 全屏 / 退出全屏会换一套播放器外壳（页面自己排的布局），
    // 刚被藏掉的「看相关」「…」会跟着重建，这里立刻重扫一次
    applyFocus(true);
  }

  /**
   * 合集入口没有稳定的 data-e2e，只能按内容找：
   * 信息区里一个以「合集」开头的短胶囊（"合集 · xxx | 更新至第 N 集"）。
   */
  /** 当前播放的 video 所在的滑动卡片（swiper 会预挂三屏）。 */
  function activeSlide() {
    var v = activeVideo();
    var n = v;
    for (var i = 0; i < 9 && n; i++) {
      var c = (n.className || '').toString();
      if (c.indexOf('dySwiperSlide') >= 0 || c.indexOf('swiper-slide') >= 0) return n;
      n = n.parentElement;
    }
    return null;
  }

  /**
   * 合集入口没有稳定的 data-e2e，只能按内容找：信息区里一个
   * 以「合集」开头的短胶囊（"合集 · xxx | 更新至第 N 集"）。
   *
   * 两个坑：
   *   1) 描述文案里也可能出现「合集」二字，所以要求文本**以**合集开头；
   *   2) swiper 预挂了下面好几屏，那些卡片的信息区 y 坐标在视口外（实测 top=3000），
   *      拿去合成点击是点不中的。所以必须在视口内才认。
   */
  function collectionEntry() {
    var els = document.querySelectorAll('div, span, a');
    var slide = activeSlide();
    for (var i = 0; i < els.length && i < 6000; i++) {
      var el = els[i];
      if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE') continue;
      if (el.children.length > 6) continue;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0 || r.height > 44) continue;
      var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (t.indexOf('合集') !== 0 || t.length > 26) continue;
      var cy = r.top + r.height / 2;
      if (cy < 0 || cy > window.innerHeight) continue;
      // 优先认「和正在播的视频同一张卡片」里的那个
      if (slide && slide.contains(el)) return el;
      if (!slide) return el;
    }
    return null;
  }

  function openCollection() {
    var el = collectionEntry();
    if (!el) {
      LOG.info('TOOL', '当前视频没有合集入口');
      return false;
    }
    try { el.click(); } catch (e) { return false; }
    LOG.info('TOOL', '打开合集/选集');
    return true;
  }

  /* ---------------------------------------------- 10.1 进度上报给原生 */
  // 底部进度条是原生的，它需要知道播到哪了。只在秒数或播放状态真的变了时才过桥：
  // 500ms 一次、空闲不发，代价可以忽略（和日志共用同一个 JS 桥）。
  var lastProgress = { sec: -1, dur: -1, playing: -1, tools: '', author: '' };

  /** 从元素（含它自己）里捞一张图：<img> 优先，其次 background-image。 */
  function pickImageUrl(root) {
    if (!root) return '';
    var nodes = [root];
    var kids = root.querySelectorAll ? root.querySelectorAll('*') : [];
    for (var k = 0; k < kids.length && k < 12; k++) nodes.push(kids[k]);
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.tagName === 'IMG') {
        var s = n.currentSrc || n.src || n.getAttribute('data-src') || '';
        if (s) return s;
      }
      var bg = window.getComputedStyle(n).backgroundImage || '';
      var m = bg.match(/url\(["']?(.*?)["']?\)/);
      if (m && m[1] && m[1].indexOf('data:') !== 0) return m[1];
    }
    return '';
  }

  /**
   * 作者信息：头像 URL + 昵称。
   *
   * 头像不是 <img>：抖音用 span + background-image 画，所以得连背景图一起翻。
   *
   * 这里**不判断**「有没有关注」：网页版那颗 + 徽标是靠 hover 才渲染的
   * （元素常驻、内部 svg 带 hidden、高度恒为 0），从 DOM 上分不出已关注还是没关注。
   * 硬判的结果是「所有视频都显示已关注」，比不显示还糟。
   * 关注状态交给交互：点一下变「已关注」，翻到下一个视频时重置回「关注」。
   */
  function authorInfo() {
    var slide = activeSlide() || document;

    var nick = '';
    var nickEl = slide.querySelector('[data-e2e="feed-video-nickname"]');
    if (nickEl) {
      // 昵称后面可能跟着「认证徽章」的 aria 文本，只取纯文本节点
      var raw = '';
      for (var i = 0; i < nickEl.childNodes.length; i++) {
        if (nickEl.childNodes[i].nodeType === 3) raw += nickEl.childNodes[i].nodeValue;
      }
      // 认证作者的昵称后面挂着「认证徽章」的 aria 文本，去掉
      nick = (raw || nickEl.textContent || '').replace(/\s+/g, ' ').trim()
        .replace(/认证徽章|官方认证|企业认证|个人认证/g, '').trim();
    }

    // 直播卡片用的是 live-avatar，普通视频是 video-avatar，两个都试一下
    var src = pickImageUrl(slide.querySelector('[data-e2e="video-avatar"]'))
      || pickImageUrl(slide.querySelector('[data-e2e="live-avatar"]'));

    // 点赞和收藏是有权威状态的：按钮上直接挂着 data-e2e-state
    //   video-player-is-digged / video-player-no-digged
    //   video-player-is-collected
    // 翻页、取消点赞、收藏被对方删掉，这个属性都会跟着变，比自己记可靠。
    // 注意是 is-digged，不是 digged —— 猜错一个字，Dock 上的心就永远不亮。
    var digg = slide.querySelector('[data-e2e="video-player-digg"]');
    var coll = slide.querySelector('[data-e2e="video-player-collect"]');
    var liked = !!(digg && digg.getAttribute('data-e2e-state') === 'video-player-is-digged');
    var collected = !!(coll && coll.getAttribute('data-e2e-state') === 'video-player-is-collected');

    return { avatar: src || '', nick: nick, liked: liked, collected: collected };
  }

  /** 上报作者信息 + 点赞收藏状态。只在真的变了的时候过桥。 */
  function pushAuthor() {
    var b = window.__DY_NATIVE__;
    if (!b || typeof b.author !== 'function') return;
    var a = authorInfo();
    var sig = a.avatar + '|' + a.nick + '|' + (a.liked ? '1' : '0') + (a.collected ? '1' : '0');
    if (sig === lastProgress.author) return;
    lastProgress.author = sig;
    try {
      b.author(a.avatar, a.nick, a.liked ? 1 : 0, a.collected ? 1 : 0);
    } catch (e) {}
  }

  // 当前视频有没有合集入口。每轮 work() 重算一次缓存，
  // 不能放在 pushProgress 里算 —— 那玩意儿要扫两千多个节点，500ms 一次太重。
  var hasColl = false;

  function toolSignature() {
    return (danmuOn ? '1' : '0') + '|' + qualityLabel() + '|' + userRate
      + '|' + (hasColl ? '1' : '0');
  }

  function pushProgress() {
    var b = window.__DY_NATIVE__;
    if (!b || typeof b.progress !== 'function') return;
    if (document.hidden) return;
    pushAuthor();
    var tools = toolSignature();
    var label = qualityLabel();
    var dm = danmuOn ? 1 : 0;
    var coll = hasColl ? 1 : 0;
    var v = activeVideo();
    if (!v) {
      if (lastProgress.dur !== 0 || lastProgress.tools !== tools) {
        lastProgress.sec = 0;
        lastProgress.dur = 0;
        lastProgress.playing = 0;
        lastProgress.tools = tools;
        try { b.progress(0, 0, 0, label, userRate, dm, coll); } catch (e) {}
      }
      return;
    }
    var dur = v.duration;
    if (!isFinite(dur) || dur <= 0) return;
    // 别只报 0.1s：原生靠「这次真值 vs 上次外推」对齐，四舍五入到 0.1s
    // 会让真值经常比外推落后几十毫秒，每 250ms 就把进度往回拽一下。
    var sec = Math.round(v.currentTime * 1000) / 1000;
    // 缓冲/seek 时 readyState 会掉下来，这时不能继续按 1x 外推，否则条会
    // 跑到画面前面再被拽回来。
    var playing = (!v.paused && !v.ended && v.readyState >= 3) ? 1 : 0;
    if (sec === lastProgress.sec && playing === lastProgress.playing
      && Math.abs(dur - lastProgress.dur) < 0.5 && tools === lastProgress.tools) return;
    lastProgress.sec = sec;
    lastProgress.dur = dur;
    lastProgress.playing = playing;
    lastProgress.tools = tools;
    try { b.progress(sec, dur, playing, label, userRate, dm, coll); } catch (e) {}
  }

  /**
   * 打开抖音自己的登录框。
   *
   * 页头那个「登录」按钮只认真实点击（点它的各个父级也不行，实测 0 层到 3 层都没反应），
   * 而沉浸层又把页头整个盖住了——用户在沉浸视图里根本找不到登录入口。
   *
   * 借「点赞」当入口：未登录时点它会弹「登录后即可点赞」，不会真的点赞。
   * 这只在判定为未登录时调用（LoginGate 本来就只在未登录时出现），
   * 万一把「已登录」误判成未登录，代价也只是给某个视频点了个赞，可撤销。
   */
  function openLogin() {
    var digg = document.querySelector('[data-e2e="video-player-digg"]');
    if (!digg) {
      LOG.info('LOGIN', '当前页面没有点赞按钮，无法打开登录框');
      return false;
    }
    try {
      digg.click();
    } catch (e) {
      LOG.err('openLogin', e);
      return false;
    }
    LOG.info('LOGIN', '通过点赞入口打开登录框');
    return true;
  }

  /**
   * 登录态判断。宁可返回 unknown 也不要误报 in——误报会让用户以为登录成功，
   * 实际上后面点赞、评论全失败，比不提示更糟。
   *
   * 踩过的坑：
   *   1) 最早写 img[src*="aweme-avatar"] 当「已登录」，结果播放器里**视频作者**的
   *      头像也命中这条，一进页面就判成已登录；
   *   2) 后来按 rect 找顶栏那个「登录」按钮，可是精简模式会把整条 header
   *      display:none 掉，rect 全是 0，于是永远判成 unknown。
   *
   * 所以这里不看几何、只看 DOM：整条 header 被藏了，那个按钮也还在文档里。
   */
  function loginState() {
    // 顶栏自己的头像节点（作者头像用的是 video-avatar，不会撞）
    if (document.querySelector('[data-e2e="user-avatar"]')) return 'in';

    // 已登录时顶栏那个人菜单里一定有「退出登录」/「我的喜欢」，
    // 未登录时只有一个「登录」按钮 —— 这两组文案不会同时出现。
    var hdr = document.querySelector('header');
    var txt = hdr ? (hdr.textContent || '') : (document.body.textContent || '');
    if (txt.indexOf('退出登录') >= 0) return 'in';

    var nodes = (hdr || document).querySelectorAll('button, div, span, a');
    var limit = Math.min(nodes.length, 800);
    for (var i = 0; i < limit; i++) {
      var el = nodes[i];
      if (el.children.length > 2) continue;
      if ((el.textContent || '').trim() !== '登录') continue;
      if (el.closest && el.closest('.xgplayer')) continue;
      return 'out';
    }
    return 'unknown';
  }

  /* --------------------------------------------------------- 11. 显示模式 */
  // 网页模式 = 1280 设计宽度（完整桌面版，信息全但字小）
  // 沉浸模式 = 900（等效放大 1.4 倍，视频和字都大一圈）
  //
  // 只在「视口宽度」上做文章，不碰 DOM 结构 —— 页面改版也不会失效，
  // 而且随时可以一键切回去。真正的全屏裁切要做 transform，得先看到真机截图
  // 才能确定播放器容器的结构，先不冒这个险。
  var MODE_WIDTH = { web: WIDTH_WEB, immersive: WIDTH_IMMERSIVE };
  var mode = HAS_NATIVE ? 'immersive' : 'web';

  function setMode(next) {
    next = (next === 'immersive') ? 'immersive' : 'web';
    mode = next;
    CFG.designWidth = MODE_WIDTH[next];
    applyViewport();
    LOG.info('MODE', '切换到 ' + next + '，designWidth=' + CFG.designWidth);
    return next;
  }

  /* ------------------------------------------------------------- 9. 调试口 */
  // chrome://inspect 里可以直接调用：
  //   __DY.next() / __DY.prev()   翻页（和滑动走同一条路径）
  //   __DY.togglePlay()           播放 / 暂停
  //   __DY.like()                 双击点赞
  //   __DY.comments()             点开评论区
  //   __DY.setMode('immersive')   沉浸（900）/ 'web'（1280）
  //   __DY.enterFeed()            立刻回到推荐流（网格页里点开第一条）
  //   __DY.setFocus(false)        关掉精简模式，把网页版原样的导航/搜索都放回来
  //   __DY.fill(false)            不铺满播放器，恢复桌面比例
  //   __DY.wheel(1)               手动翻下一个
  window.__DY = {
    config: CFG,
    wheel: function (dir) { return dispatchWheel(dir || 1); },
    refit: applyViewport,
    sweep: sweepPromos,
    log: function (msg) { LOG.info('MANUAL', String(msg)); },

    // 控制条用的
    next: function () { return dispatchWheel(1); },
    prev: function () { return dispatchWheel(-1); },
    togglePlay: togglePlay,
    like: like,
    comments: comments,
    // 评论抽屉开合状态：原生层据此 toggle、侧滑返回、返回键优先关评论
    sheetOpen: function () {
      if (!CFG.sheetComments) return false;
      if (document.documentElement.classList.contains('dy-sheet-open')) return true;
      // 刚点开、壳还没套上的那几十毫秒也算「开着」—— 否则原生那边连点两下
      // 会判成「没开」，再调一次 comments() 把页面 toggle 关掉。
      // 但要同时确认面板真的挂出来了，不然一次失败的打开会让这个状态永远为真。
      return window.__DY_WANT_SHEET__ === true && !!commentFrame();
    },
    // 关掉评论区。
    //
    // 优先点页面自己的评论按钮（它是 toggle），这样页面内部状态和视觉一致。
    // 抽屉右上角的 × 也能关，而且实测页面会真的把面板从布局里摘掉（以前
    // 这里记的是「× 只做视觉淡出」—— 已经不成立）；但它不会经过我们，
    // 所以 × 那条路径靠 watchSheetClose() 同步拆壳。
    closeComments: function () {
      window.__DY_WANT_SHEET__ = false;
      // 页面面板已经不在了（用户先点了抽屉的 ×）：这时绝对不能再点评论按钮 ——
      // 它是 toggle，点一下会把刚关掉的评论重新打开。原生那边的 commentOpen
      // 是 1 秒轮询来的，点完 × 立刻按返回 / 点 Dock 就会撞上这个陈旧状态。
      if (!commentFrame()) {
        if (document.querySelector('.dy-sheet') || sheetApplied) {
          beginClose('页面已关闭，只同步壳');
        }
        LOG.info('SHEET', '评论区已关，跳过 toggle');
        return true;
      }
      var btn = findCommentToggle();
      // 页面按钮留到动画结束再点：面板要跟着抽屉一起滑下去。直接点的话页面
      // 会在同一帧卸掉面板，剩下的只是一块空壳自己往下走。
      var done = beginClose('Dock 评论按钮', function () {
        if (!btn) return;
        try { btn.click(); } catch (e) {}
        LOG.info('SHEET', '点评论按钮 toggle 关闭');
      });
      if (!done && btn) {
        try { btn.click(); } catch (e) {}
        LOG.info('SHEET', '没有可收的壳，直接点评论按钮关闭');
      }
      return true;
    },
    // 原生 UI 用：算坐标 → 自己合成真实触摸；进度条 → 直接改 currentTime
    point: point,
    author: authorInfo,
    // 关注/收藏点完之后让页面重推一次作者信息，用来校准「已关注」这类乐观态：
    // 未登录时点下去那个「+」根本不会消失，不校准的话按钮会一直骗人。
    pushAuthorNow: function () { lastProgress.author = ''; pushAuthor(); },
    seekTo: seekTo,
    seekToFraction: seekToFraction,
    progress: function () {
      var v = activeVideo();
      if (!v) return null;
      return { cur: v.currentTime, dur: v.duration, paused: v.paused };
    },
    setMode: setMode,
    loginState: loginState,
    openLogin: openLogin,

    // 原生 Dock 的工具行：弹幕 / 清晰度 / 倍速 / 合集
    toggleDanmu: function (on) { return setDanmu(on); },
    danmuOn: function () { return !!danmuOn; },
    qualityOptions: qualityOptions,
    qualityLabel: qualityLabel,
    setQuality: setQuality,
    cycleQuality: cycleQuality,
    rateOptions: rateOptions,
    setRate: setRate,
    cycleRate: cycleRate,
    rate: function () { return userRate; },
    openCollection: openCollection,
    hasCollection: function () { return !!collectionEntry(); },

    // 原生 Dock 的音量条 / 全屏
    setVolume: setVolume,
    toggleMute: toggleMute,
    volume: function () { return userVol; },
    refreshLayout: refreshLayout,
    // 原生点全屏时调：视频铺满整屏（网页版全屏只藏系统栏，画面还是 16:9 那块）
    setFullscreen: setFullscreen,
    // 调试用：立刻跑一次评论抽屉改版
    patchCommentSheet: patchCommentSheet,

    enterFeed: function () { return enterFeed(true); },
    setFocus: setFocus,
    isFocus: function () { return !!CFG.focusMode; },
    // 用户要在页面里登录时，原生层调这个暂停清理遮挡层，否则登录弹窗刚出来就被关
    pauseOverlaySweep: function (ms) { pauseOverlaySweep(ms); },
    sweepOverlays: sweepOverlays,
    // 手动触发一次「上一页/下一页」的原生滚轮，调试用
    scrollNative: function (dir) { return nativeScroll(dir || 1); },
    fill: function (on) {
      CFG.fillVideo = on !== false;
      if (CFG.fillVideo) fillPlayer();
      return CFG.fillVideo;
    },

    mode: function () { return mode; },
    video: function () {
      var v = activeVideo();
      if (!v) return null;
      var r = v.getBoundingClientRect();
      return { width: Math.round(r.width), height: Math.round(r.height), paused: v.paused };
    },
    state: function () {
      return {
        url: location.href,
        title: document.title,
        inner: window.innerWidth + 'x' + window.innerHeight,
        screen: window.screen.width + 'x' + window.screen.height,
        dpr: window.devicePixelRatio,
        mode: mode,
        feed: isFeedPage(),
        videos: document.querySelectorAll('video').length,
        config: CFG
      };
    }
  };
})();
