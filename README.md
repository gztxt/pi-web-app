# Pi Web 安卓客户端

WebView 壳 APP,把 [pi-web](https://github.com/agegr/pi-web)(pi coding agent 的 Web 工作台)封装为安卓原生应用。
v2.0 基于 pi-web 最新源码的官方图标与暗色配色(`#1a1a1a` / `#60a5fa`)重构。

## v2.1 更新

**修复**
- 🔄 **移除下拉刷新**:与页面滚动冲突(聊天记录上滑看历史时到顶被刷新劫持)。
  刷新改为仅通过 π 悬浮菜单 → 刷新页面

## v2.0 更新

**修复**
- 🔴 **安装后黑屏无法打开**:v1 的错误处理存在竞态 —— 连接失败显示错误页后加载 `about:blank`,
  其 `onPageFinished` 回调立即把错误页隐藏,导致永久黑屏无任何提示。
  v2 改为错误页直接渲染在 WebView 内(自定义 HTML),并用 `pageError` 状态机隔离,彻底修复。

**新增**
- 🌐 **自定义服务器地址**:支持 IPv4 / IPv6(方括号 `http://[2001:db8::1]:30141`)/ 域名 / Tailscale MagicDNS,
  可省略 `http://`,应用内随时修改,SharedPreferences 持久化
- ⚡ 连接失败自动重试(8 秒倒计时)+ 网络恢复自动重连
- 🖼️ 启动 Splash 页(π 图标),首屏加载不再是黑屏
- 📎 图片上传(pi-web 聊天附件 `<input type="file" accept="image/*">`)
- 🖥️ 桌面/移动模式切换(移动版按 pi-web 响应式布局渲染)
- π 悬浮菜单按钮(可拖动):刷新 / 改地址 / 桌面模式 / 外部浏览器 / 关于
- 官方 π 图标(自适应图标,全密度)+ pi-web 暗色主题配色
- 刘海屏适配、横竖屏自由旋转

## 构建

本地无 Android SDK,通过 GitHub Actions 云端编译(Gradle Wrapper 8.5,构建可复现):

- 推送代码 → 自动触发 `.github/workflows/build.yml`
- 产物:`Actions → Artifacts → pi-web-apk-v2` 下载 `app-debug.apk`

## 安装

1. 下载 APK 到手机,允许"安装未知来源应用"
2. 首次打开显示 Splash 后自动连接默认地址 `http://100.117.232.62:30141`(Tailscale)
3. 连不上会进入错误页:点"修改地址"换成你的局域网 IP / IPv6 / 域名即可

## 默认地址

`app/src/main/java/com/gztxt/piweb/MainActivity.java`:

```java
private static final String DEFAULT_URL = "http://100.117.232.62:30141";
```

运行后也可在应用内修改(π 按钮 → 修改服务器地址),无需重新编译。
