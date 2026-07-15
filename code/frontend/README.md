# 元作 AI 前端

基于 Flutter 3.24.5 / Dart 3.5.4 的 Android 前端项目。

## 目录

- `lib/app/models`：功能目录与任务模型
- `lib/app/network`：后端 API、文件选择和任务结果协议
- `lib/app/pages`：首页、功能、我的三个 Tab
- `lib/app/widgets`：公共导航、任务面板和基础组件
- `lib/app/theme`：颜色与文字规范

## 运行

使用 Android Studio 打开本目录，选择 Android 设备后运行 `lib/main.dart`。

真机联调默认让电脑和手机连接同一个 Wi-Fi。执行 `ipconfig` 找到电脑 `WLAN` 网卡的 IPv4，写入
`lib/app/network/api_config.dart`，格式为 `http://<电脑WLAN IPv4>:8080/api/v1`。不要使用 VMware、Clash、
蓝牙等虚拟网卡地址，也不要给真机配置 `localhost`。路由器重新分配地址后需要同步更新该配置。

Android 官方模拟器访问电脑后端时使用 `http://10.0.2.2:8080/api/v1`。

A few resources to get you started if this is your first Flutter project:

- [Lab: Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Cookbook: Useful Flutter samples](https://docs.flutter.dev/cookbook)

For help getting started with Flutter development, view the
[online documentation](https://docs.flutter.dev/), which offers tutorials,
samples, guidance on mobile development, and a full API reference.
