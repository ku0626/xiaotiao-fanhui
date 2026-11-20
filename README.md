# 小条返回 PillBar

小米 15 / 澎湃OS 4（HyperOS）底部小白条增强工具。官方横条被隐藏后，由本 App 自绘一条自适应颜色的横条：轻点返回、长按/双击可配置动作，滑动时把手指轨迹实时回放给系统，官方手势（回桌面、多任务、切应用）全部保真可用。

纯 Java、无 Gradle、单个 build.sh 直接出 APK，适合作为手势增强类小工具的参考实现。

> 当前版本 v1.24 / versionCode 25，在小米 15（澎湃OS 4）上日常使用打磨而成。

## 功能

| 动作 | 结果 | 处理方 |
| --- | --- | --- |
| 轻点横条 | 返回上一级（可改为回桌面 / 多任务） | App |
| 长按横条 | 执行配置动作（小爱 / 锁屏 / 支付宝付款码 / 微信扫一扫 / 弹菜单） | App |
| 双击横条 | 可选（锁屏 / 小爱 / 付款码 / 扫一扫 / 不响应） | App |
| 上滑 | 回到桌面 | 系统（轨迹回放） |
| 上滑停顿 | 最近任务 | 系统（轨迹回放） |
| 上滑后左右滑 | 按最近使用顺序切换应用 | 系统（轨迹回放） |

特性一览：

- **自动取色**：截屏采样横条上方画面，白底深条、深底白条；每个 App 的取色结果有缓存，转场时直接用缓存色不跳变；防截屏 App（银行/支付类）自动降级为深浅模式跟随。
- **桌面隐藏**：进入桌面横条整体移除，系统手势 100% 原生零干扰；判定只认「全局解析出的默认桌面包」，免疫 MIUI FallbackHome 等干扰源。
- **键盘隐藏**：键盘弹出时横条自动消失。
- **手势回放**：触控窗口罩住手势区时，检测到滑动就用 `dispatchGesture` 把手指轨迹实时回放给系统，等于把这场滑动"转交"给系统原生处理——这是窗口遮挡手势区与官方手势共存的关键技巧。
- **防误挂自愈**：挂条后 100-300ms 快速复查桌面态，迟到事件造成的误挂自动纠正。
- 不出现在最近任务列表；横条宽度/厚度/位置/透明度可调。

## 系统要求

- Android 8.0（API 26）及以上，针对小米澎湃OS / MIUI 调试，其他 ROM 未验证
- 无障碍服务权限（侧载 App 首次开启会提示「受限设置」：设置 → 应用管理 → 小条返回 → 右上角 ⋮ → 允许受限设置）

## 自己编译

无需 Gradle。需要：

1. **JDK 17**（项目用 Azul Zulu 17，任何 JDK 17 发行版均可）
2. **Android SDK**：build-tools 34.0.0（aapt2 / d8 / zipalign / apksigner）+ platforms/android-34 的 android.jar
3. Windows + Git Bash（脚本用了 MSYS 风格路径处理）

步骤：

```bash
# 1. 把仓库里的 app/ 放到工作目录，修改 build.sh 顶部的路径变量：
#    JAVA_HOME / SDK（build-tools、platform android.jar 的位置）/ APP / OUT
# 2. 运行：
bash build.sh
# 产物：$OUT/pillbar.apk（自动生成 debug.keystore 并签名）
```

安装：`adb install -r pillbar.apk`，或把 APK 传到手机直接安装。

## 项目结构

```
app/
├── AndroidManifest.xml
├── res/
│   └── xml/accessibility_service_config.xml   # 无障碍服务声明（canRetrieveWindowContent 必须为 true）
└── src/com/dapiao/pillbar/
    ├── PillService.java      # 无障碍服务主体：悬浮窗、桌面/键盘判定、手势回放、取色调度、动作执行
    ├── PillBarView.java      # 自绘横条：点按/长按/双击判定、滑动轨迹记录
    ├── ColorSampler.java     # AccessibilityService.takeScreenshot 截屏取色
    ├── MainActivity.java     # 设置页（纯代码构建 UI，无 XML 布局）
    └── Prefs.java            # SharedPreferences 封装
```

## 实现要点（踩坑记录）

- **手势区遮挡**：悬浮窗罩住底部手势区后，系统收不到从这片区域起始的任何手势。解法是滑动时把窗口临时 `FLAG_NOT_TOUCHABLE`，并用 `GestureDescription.StrokeDescription.continueStroke` 分段实时回放轨迹；首次注入延迟 40ms 等 flag 生效，否则注入事件会被自己的窗口吃掉（概率性失败约 50% 的根源）。
- **桌面判定**：不能信无障碍事件的包名（迟到/乱序会翻转状态）；要遍历 `getWindows()` 取前台 `TYPE_APPLICATION` 窗口的根包名。HOME 判定必须**全局解析默认桌面后做包名全等比较**——按"包内有无 HOME Activity"判定会被 MIUI 设置包里的 `FallbackHome`（priority=-1000）污染。
- **无障碍服务配置**：任何依赖 `getRoot()` 的逻辑，先确认 `canRetrieveWindowContent="true"`，否则全部静默返回 null。
- **侧载无障碍**：澎湃OS 对侧载 App 的无障碍服务加锁，需要用户手动「允许受限设置」。

## 免责声明

本工具依赖 Android 无障碍服务，仅用于读取前台窗口信息和执行返回/锁屏等全局动作，不收集、不上传任何数据。使用产生的任何问题由使用者自行承担。

## 许可

MIT License，欢迎 fork / 改进 / 二次发布。
