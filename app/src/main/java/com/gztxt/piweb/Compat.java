package com.gztxt.piweb;

import android.webkit.ValueCallback;
import android.webkit.WebView;

/**
 * 页面兼容注入（v3.3，2026-11-21）。
 *
 * 为什么放在 APP 里：补丁原先只在 agenthub 桥的 HTML 里注入（embed_proxy.py 的
 * VIEWPORT_CSS + COMPAT_JS）。实测（2026-11-21 23:38:53 那条日志）切址回来那次导航
 * **根本没向桥取文档**（hub 侧零 `[embed] 文档注入` 记录），页面照样渲染 ⇒ 补丁随文档
 * 一起丢了，端侧立刻回到「划不动 + 模型错误」。结论：补丁不能只跟着 HTML 走。
 *
 * 注入内容三项，全部幂等（重复调用无副作用）：
 *   1) 视口高度变量：pi-web 写 `height:100dvh; height:var(--app-viewport-height,100dvh)`，
 *      老引擎不认 dvh ⇒ 两条声明都失效 ⇒ html 高度 auto、整页 overflow:hidden 卡死。
 *      这里按真实 innerHeight 用 px 补上 `--app-viewport-height`，并在 resize/旋转后重设。
 *   2) CSS 兜底：`CSS.supports('height','100dvh')` 为 false 时插入 `html{height:100%}`。
 *   3) ResizeObserver 垫片：pi-web 会话树在 RO 回调里改样式并读尺寸，老引擎抛
 *      `ResizeObserver loop limit exceeded` 并**中断回调** ⇒ 会话树高度算不完（划不动）
 *      、模型下拉同族回调被中断（模型错误）。包装成 rAF 延迟 + 同帧合并，打断同步递归；
 *      置 `window.__hubRoShim=1`，与 Probe 的 `shim` 字段同一判据，别再各看各的。
 *
 * 只在页面 JS 里做，不改 DOM 结构、不发请求、不碰 Cookie。
 */
public final class Compat {

    private static final String JS =
        "(function(){try{"
      +   "var d=document.documentElement;if(!d)return;"
      +   "var setVh=function(){try{"
      +     "var h=(window.innerHeight||d.clientHeight||0);if(!h)return;"
      +     "if(window.__piwebVh===h)return;window.__piwebVh=h;"
      +     "d.style.setProperty('--app-viewport-height',h+'px');"
      +   "}catch(e){}};"
      +   "setVh();"
      +   "if(!window.__piwebCss){"
      +     "var ok=false;try{ok=window.CSS&&CSS.supports&&CSS.supports('height','100dvh');}catch(e){ok=false;}"
      +     "if(!ok){window.__piwebCss=1;var st=document.createElement('style');"
      +       "st.id='piweb-vh-fallback';st.textContent='html{height:100%!important}';"
      +       "(document.head||d).appendChild(st);}"
      +   "}"
      +   "if(typeof ResizeObserver!=='undefined'&&!window.__roShimmed){"
      +     "var N=window.ResizeObserver;"
      +     "var S=function(cb){var raf=0;return new N(function(en,ob){"
      +       "if(raf)return;raf=requestAnimationFrame(function(){raf=0;try{cb(en,ob);}catch(e){}});});};"
      +     "S.prototype=N.prototype;window.ResizeObserver=S;"
      +     "window.__roShimmed=1;window.__hubRoShim=1;"
      +   "}"
      +   "if(!window.__piwebVhHook){window.__piwebVhHook=1;"
      +     "window.addEventListener('resize',setVh);"
      +     "window.addEventListener('orientationchange',function(){setTimeout(setVh,150);});"
      +     "if(window.visualViewport)window.visualViewport.addEventListener('resize',setVh);"
      +   "}"
      + "}catch(e){}})();";

    private Compat() {
    }

    /** 页面加载完成后注入；幂等，可每页重复调用。 */
    public static void apply(WebView w, final String where) {
        if (w == null) return;
        try {
            w.evaluateJavascript(JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    AppLog.i("Compat", "兼容注入完成(" + where + ")");
                }
            });
        } catch (Exception e) {
            AppLog.w("Compat", "兼容注入失败(" + where + "): " + e);
        }
    }
}
