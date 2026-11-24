# 长虹电视 + 小米盒子:开机直达 HDMI,用小米遥控器关机

[English](README.md)

一台长虹"酒店系列"安卓电视,通过 HDMI 连接一台小米盒子。目标是只用小米遥控器:开机后直接是盒子的画面,
关盒子时电视也跟着关。本仓库记录了这台电视的实际行为,并提供一个小型安卓应用(`tv-app`)来绕过它的限制。
盒子上不需要安装任何东西。

**这是针对具体设备的取巧方案,只在一台电视、一个盒子、一个固件版本上测试过。动手之前请先读[风险](#风险)。**

## 测试环境

- 电视:长虹 50J5500UH,Android 9(SDK 28),MediaTek/MStar 平台,酒店系列桌面 `com.changhong.hotelv3`。
  打开 USB 调试后 `adbd` 以 root 运行,SELinux 为关闭状态。
- 盒子:小米电视盒子 `MiTV-AZFU0`,Android 14,Amlogic。盒子上没有留下任何改动。

## 当前状态

| 功能 | 状态 |
| --- | --- |
| 小米遥控器开机时电视自动开机(HDMI-CEC) | 自带可用 |
| 用电视遥控器关电视时盒子一起关 | 自带可用 |
| 开机后电视显示盒子画面,而不是长虹桌面 | 可用(`tv-app`) |
| 小米遥控器的电源键同时关闭电视 | 可用(`tv-app`):盒子关闭后几秒内电视跟着关 |

"可用"指在这一套设备上通过观察电视和读取应用日志确认过。

电视会忽略盒子发出的 CEC `<Standby>` 待机指令,不论是广播还是直接发给电视(见
[docs/findings.zh-CN.md](docs/findings.zh-CN.md))。所以关机功能不走 CEC。

## 原理

`tv-app` 是一个无障碍服务。系统每次开机都会自动绑定已启用的无障碍服务,在这个固件上它是可靠的开机触发器。

1. **开机。** 盒子通过 CEC 唤醒电视,电视冷启动并在内部选好 HDMI,随后酒店桌面启动,盖在画面上面。开机后 90 秒内,
   只要桌面窗口出现,应用就启动 `com.changhong.tvap.atv.activity/.AtvMainActivity`,把画面调回最前面。
   画面连续在前台停留 6 秒后任务就算完成,应用不再干预,之后你可以正常回到电视桌面。
2. **关机。** 盒子休眠后,电视自己的 `ITVPlayer.isSignalStable()` 会在约 1 秒内变成 false。监视线程轮询它:
   如果曾经稳定的信号源丢失信号超过 3 秒,应用就通过长虹框架客户端调用电视自带的 `SystemManager.systemStandby(false)`,
   效果等同于电源键。它只监视 HDMI 信号源。在电视桌面上,电视的当前信号源是 `E_INPUT_SOURCE_OTHER`,监视器处于空闲,所以在桌面时小米遥控器关不掉电视,
   照常用电视遥控器即可。信号丢失如果发生在前台窗口变化后的 3 秒内会被忽略,所以在画面和电视桌面之间切换不会让电视关机。

## 目录结构

```
tv-app/    电视端应用(包名 local.hdmiboot)
scripts/   build.sh,不依赖 Gradle 构建应用
docs/      排查结论(固件行为及证据)
```

## 构建

需要 JDK 17、Android build-tools(使用的是 r34)和 API 28 或更新的 `android.jar`。

```sh
export JAVA_HOME=/path/to/jdk17
export BUILD_TOOLS=/path/to/android-sdk/build-tools/34.0.0
export ANDROID_JAR=/path/to/android-sdk/platforms/android-28/android.jar
scripts/build.sh            # build/HdmiBoot.apk
```

第一次运行会生成签名用的 `debug.keystore`,不要提交到仓库。

## 安装

`$TV` 是电视的地址。先在电视上打开开发者选项和 USB 调试,并确认授权提示。这台电视重启或待机后,网络 ADB 端口会重新关闭,
需要把 USB 调试关了再开。

```sh
adb connect $TV:5555
adb -s $TV:5555 install -r build/HdmiBoot.apk
adb -s $TV:5555 shell am start -n local.hdmiboot/.MainActivity   # 让应用脱离"已停止"状态
```

固件会强制停止后台的第三方应用,并把它们记录在 `/data/chuserdata/killbackground`。把包名加入厂商白名单可以让应用存活。
这一步需要 root 和可写的 `/chperm`:

```sh
adb -s $TV:5555 pull /chperm/preapp/killbackgroundwhitelist .        # 先备份
adb -s $TV:5555 shell 'mount -o remount,rw /chperm &&
  echo local.hdmiboot >> /chperm/preapp/killbackgroundwhitelist; sync; mount -o remount,ro /chperm'
```

重启电视,重新打开 ADB,再执行一遍上面的 `am start`,并确认 `dumpsys package local.hdmiboot | grep stopped` 显示 `stopped=false`。

开启无障碍服务:在现有值后面追加,先读出并保留原值:

```sh
adb -s $TV:5555 shell settings get secure enabled_accessibility_services   # 先备份
adb -s $TV:5555 shell "settings put secure enabled_accessibility_services '<原值>:local.hdmiboot/local.hdmiboot.HdmiAccessibilityService'"
adb -s $TV:5555 shell settings get secure enabled_accessibility_services   # 读回核对
```

拼接 shell 字符串时请写成 `${OLD}:local...`,带花括号。在 zsh 里 `$OLD:local` 会被当成 `:l`(转小写)修饰符,悄悄把值改坏。

应用的日志在 `/data/data/local.hdmiboot/files/log.txt`,关注 `armed`、`signal lost` 和 `standby (HDMI signal lost)`。
`am startservice -n local.hdmiboot/.LaunchService --es cmd probe` 会记录一次对框架客户端的只读检查。

## 风险

- **不要用猜出来的 binder 事务号去调用电视的系统服务。** 探索时有一次这样的调用让运行中的服务崩溃,电视自己重启了。
  应用只使用框架自带的客户端类。
- `/chperm` 存放电视的厂商配置。把它重新挂载为可写并修改,一旦出错可能让电视出问题。请先备份要改的文件,改完恢复只读挂载。
- 启用无障碍服务会修改系统的 secure 设置,务必保留原值以便恢复。
- 关机规则可能误关电视:盒子信号丢失超过 3 秒(重启、长时间切换分辨率、拔掉线)电视就会待机。
- 以上全部来自一个固件版本的观察。换电视、换固件或换盒子,行为可能完全不同。
- 不要用它绕过任何激活、授权或访问控制。这台电视上的酒店配置应用需要激活码,我们有意没有去碰。

## 撤销

卸载 `local.hdmiboot`,把 `enabled_accessibility_services` 还原成备份的值,并把 `killbackgroundwhitelist` 还原成原件。

## 许可证

MIT,见 [LICENSE](LICENSE)。长虹和小米是各自所有者的商标,本项目与它们无关,不包含任何厂商代码或固件。
