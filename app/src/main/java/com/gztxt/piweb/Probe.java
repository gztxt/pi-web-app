package com.gztxt.piweb;

import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;

/**
 * 运行状态探针(v3.2，2026-11-21 用户要求「详细运行日志，包括所有运行状态」)。
 *
 * 为什么放 JS：卡死/模型错误发生在页面渲染层，Java 侧只能看到导航生命周期，
 * 看不到「哪些容器没有溢出、ResizeObserver 报了几次、fetch 挂了哪一条、长任务多少次」。
 * 这些指标必须在页面上下文里取，取回后经 AppLog 落盘并回传 piweb-sync，事后可查。
 *
 * 采集项：readyState / 视口 / dvh 支持 / RO 垫片是否装上 / 文档高度 / body overflow /
 * RO loop 次数 / 长任务次数 / JS 堆 / JS 异常与 Promise 拒绝 / 非 2xx 或 >3s 的
 * fetch 与 XHR / 页面上「可滚动容器」实测尺寸（判断能否滑动）。
 *
 * 只读：不改页面任何样式或状态，仅包装 fetch/XHR 记录、注册 error 监听。
 */
public final class Probe {

    /** 采样回调（主线程调用）。 */
    public interface Cb {
        void onSample(String tag, String json);
    }

    private static final Handler H = new Handler(Looper.getMainLooper());
    /** 页面稳定后的采样时刻（毫秒）：早采样抓加载期，晚采样抓「用一会儿才卡」。 */
    private static final long[] DELAYS = {800, 2000, 4000, 8000, 15000, 30000, 60000};

    private Probe() {
    }

    /** 注入探针并按固定时刻采样；tag 用来说明是哪个视图（main / child）。 */
    public static void attach(final WebView wv, final String where, final Cb cb) {
        if (wv == null) return;
        wv.evaluateJavascript(JS, null);
        for (final long d : DELAYS) {
            H.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sample(wv, where + "+" + (d / 1000) + "s", cb);
                }
            }, d);
        }
    }

    /** 立即采一次（手动触发或补采）。 */
    public static void sample(WebView wv, final String tag, final Cb cb) {
        if (wv == null) return;
        try {
            wv.evaluateJavascript("(window.__hubProbe?__hubProbe():null)", new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    if (cb != null) cb.onSample(tag, v);
                }
            });
        } catch (Exception e) {
            if (cb != null) cb.onSample(tag, "probe error: " + e);
        }
    }

    /**
     * 探针本体。全部单引号，避免 Java 源码里的转义地狱；不用箭头函数（保守兼容老 WebView）。
     */
    private static final String JS =
        "(function(){"
        + "if(window.__hubDiag){return;}"
        + "var D={ro:0,err:[],rej:[],http:[],lt:0,start:Date.now()};"
        + "window.__hubDiag=D;"
        + "try{"
        + " window.addEventListener('error',function(e){"
        + "  var m=(e&&e.message)||'';"
        + "  if(m.indexOf('ResizeObserver')>=0){D.ro++;}"
        + "  else if(D.err.length<8){D.err.push(String(m).slice(0,140));}"
        + " },true);"
        + " window.addEventListener('unhandledrejection',function(e){"
        + "  var r=e&&e.reason;var m=String((r&&r.message)||r||'');"
        + "  if(D.rej.length<8){D.rej.push(m.slice(0,140));}"
        + " },true);"
        + " try{"
        + "  if(window.PerformanceObserver){"
        + "   new PerformanceObserver(function(l){D.lt+=l.getEntries().length;}).observe({entryTypes:['longtask']});"
        + "  }"
        + " }catch(e1){}"
        + " try{"
        + "  var of=window.fetch;"
        + "  if(of){window.fetch=function(){"
        + "   var a=arguments,u=a[0],t=Date.now();"
        + "   var url=(typeof u==='string')?u:((u&&u.url)||'');"
        + "   return of.apply(this,a).then(function(r){"
        + "    var dt=Date.now()-t;"
        + "    if(!r.ok||dt>3000){if(D.http.length<12){D.http.push({u:url.slice(-70),s:r.status,ms:dt});}}"
        + "    return r;"
        + "   },function(err){"
        + "    if(D.http.length<12){D.http.push({u:url.slice(-70),s:'ERR',ms:Date.now()-t,m:String((err&&err.message)||err).slice(0,70)});}"
        + "    throw err;"
        + "   });"
        + "  };} "
        + " }catch(e2){}"
        + " try{"
        + "  var xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send;"
        + "  XMLHttpRequest.prototype.open=function(m,u){this.__hubU=u;return xo.apply(this,arguments);};"
        + "  XMLHttpRequest.prototype.send=function(){"
        + "   var self=this,t=Date.now();"
        + "   this.addEventListener('loadend',function(){"
        + "    var dt=Date.now()-t;"
        + "    if(!(self.status>=200&&self.status<300)||dt>3000){"
        + "     if(D.http.length<12){D.http.push({u:String(self.__hubU).slice(-70),s:self.status,ms:dt});}"
        + "    }"
        + "   });"
        + "   return xs.apply(this,arguments);"
        + "  };"
        + " }catch(e3){}"
+ " try{"
        + "  var WO=window.WebSocket;"
        + "  if(WO){"
        + "   window.WebSocket=function(u,p){"
        + "    var ws=p?new WO(u,p):new WO(u);"
        + "    D.ws=D.ws||{n:0,open:0,close:0,err:0,last:-1};"
        + "    D.ws.n++;"
        + "    ws.addEventListener('open',function(){D.ws.open++;});"
        + "    ws.addEventListener('close',function(e){D.ws.close++;D.ws.last=e.code;});"
        + "    ws.addEventListener('error',function(){D.ws.err++;});"
        + "    return ws;"
        + "   };"
        + "   window.WebSocket.prototype=WO.prototype;"
        + "  }"
        + " }catch(ew){}"
        + "}catch(e0){}"
        + "window.__hubProbe=function(){"
        + " var doc=document.documentElement;"
        + " var over=[];"
        + " try{"
        + "  var all=document.querySelectorAll('*');"
        + "  for(var i=0;i<all.length&&over.length<4;i++){"
        + "   var el=all[i];"
        + "   if(el.scrollHeight>el.clientHeight+24&&el.clientHeight>80){"
        + "    over.push({n:(el.tagName||'')+'.'+String(el.className||'').slice(0,20),cw:el.clientWidth,ch:el.clientHeight,sh:el.scrollHeight,oy:getComputedStyle(el).overflowY});"
        + "   }"
        + "  }"
        + " }catch(e4){}"
        + " var mem=null;"
        + " try{if(performance.memory){mem=Math.round(performance.memory.usedJSHeapSize/1048576);}}catch(e5){}"
        + " var dvh=null;"
        + " try{dvh=(window.CSS&&CSS.supports)?CSS.supports('height','100dvh'):null;}catch(e6){}"
        + " var bodyOv='';"
        + " try{bodyOv=getComputedStyle(document.body).overflow;}catch(e7){}"
        + " return JSON.stringify({"
        + "  t:Date.now()-D.start,rs:document.readyState,"
        + "  vw:window.innerWidth,vh:window.innerHeight,"
        + "  dvh:dvh,shim:(window.__hubRoShim?(window.__roShimmed?'on':'partial'):'off'),"
        + "  docH:doc?doc.scrollHeight:-1,bodyOv:bodyOv,"
        + "  ro:D.ro,lt:D.lt,mem:mem,err:D.err,rej:D.rej,http:D.http,over:over,ws:(D.ws||null)"
        + " });"
        + "};"
        + "})();";
}
