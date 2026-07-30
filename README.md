# Pi Web 安卓客户端

WebView 壳 APP,把 [pi-web](https://github.com/agegr/pi-web)(pi coding agent 的 Web 工作台)封装为安卓原生应用。
v2.0 基于 pi-web 最新源码的官方图标与暗色配色(`#1a1a1a` / `#60a5fa`)重构。

## v2.4 更新

> 按用户决策调整顺序: 精准诊断优先,多服务器地址簿顺延至 v2.5。

**新增**
- 🔍 **分层网络诊断**: 网络状态 → DNS → TCP 端口 → HTTP 逐层探测,每层带耗时,
  失败即停并给出“哪一层 + 什么原因 + 查什么”的人话结论:
  - DNS 失败 → 域名/DDNS/Tailscale MagicDNS 问题
  - TCP 拒绝 → 主机在线但 pi-web 没起;TCP 超时 → 防火墙/主机离线
  - HTTP 5xx → 服务异常;SSL 错误 → 证书问题
  - 全链路正常 → 应用层问题(重试/桌面模式/清缓存)
- 错误页自动诊断 + 实时显示逐层结果,支持「重新诊断」按钮
- π 菜单 → 网络诊断: 任何时候可手动诊断(实时结果对话框)
- 多 IP 优先 IPv4(移动网络下 IPv6 常不通)

**完善**(v2.2 日志实战审查发现的缺口)
- 网络断开/恢复都记事件行(含网络类型 WiFi/MOBILE),不再只有恢复行
- 加载日志带触发源: `trigger=launch/auto/net/manual/mode/config`,
  可区分用户操作与程序行为

## v2.2 更新

**新增**
- 📝 **运行日志**:启动/加载/错误/重试/网络变化/配置变更/文件选择全链路记录
  - π 菜单 → 运行日志:查看最近 400 行 / 分享文本 / 清空
  - 持久化 `files/piweb.log`(256KB 自动轮转),镜像 logcat(`adb logcat -s PiWeb`)
  - 全局崩溃捕获:闪退/黑屏先落盘堆栈再退出,事后可查

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
