# Pi-Web Android App

WebView 壳 APP，封装 pi-web 为安卓原生应用。

## 功能
- 全屏 WebView 加载 pi-web (`http://100.117.232.62:30141`)
- 沉浸式状态栏，深色主题
- 加载进度条
- 断线错误提示 + 点击重试
- 支持缩放、前进后退

## 构建
本地无 Android SDK，通过 GitHub Actions 云端编译：
- 推送代码 → 自动触发 `.github/workflows/build.yml`
- 产物：`app-debug.apk`（Actions → Artifacts 下载）

## 安装
1. 下载 APK 到手机
2. 允许"安装未知来源应用"
3. 安装后桌面出现 π 图标
4. 确保手机与服务器同网络（局域网 / Tailscale）

## 修改服务地址
编辑 `app/src/main/java/com/gztxt/piweb/MainActivity.java`：
```java
private static final String PI_WEB_URL = "http://你的IP:30141";
```
