# 影视盒子 - TVBox Android 版

基于 Maccms10 API 的视频聚合应用，WebView 套壳方案。

## 功能

- 站点管理：添加/删除 maccms10 站点，只需填域名
- 分类浏览：自动加载站点分类
- 视频列表：封面+名称+备注，网格布局
- 搜索：关键词搜索
- 详情页：影片信息+简介+播放源选择
- 播放：支持 m3u8/mp4 直链播放
- 解析线路：内置10条解析线路，支持爱奇艺/优酷/腾讯等，可自定义添加
- 收藏：本地收藏管理
- 历史：自动记录播放历史

## 编译方法

### 方法一：Android Studio（推荐）

1. 安装 [Android Studio](https://developer.android.com/studio)
2. 打开本项目文件夹
3. 等待 Gradle 同步完成
4. Build → Build Bundle(s) / APK(s) → Build APK(s)
5. APK 输出在 `app/build/outputs/apk/debug/`

### 方法二：命令行

```bash
# 确保已安装 JDK 17+ 和 Android SDK
export ANDROID_HOME=/path/to/android-sdk
chmod +x gradlew
./gradlew assembleDebug
```

## 使用说明

1. 安装 APK
2. 首次打开，点击右上角 📡 图标
3. 添加你的 maccms10 站点域名
4. 返回首页，选择站点和分类开始观影

## 技术栈

- Java 17
- Android SDK 34
- WebView + H5
- 最低支持 Android 5.0 (API 21)
