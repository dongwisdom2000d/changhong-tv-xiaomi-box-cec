# 排查结论

以下内容来自一台长虹 50J5500UH(Android 9)和一台小米 `MiTV-AZFU0` 盒子(Android 14)。"观察到"表示在设备上实际看到;
"推断"表示来自阅读固件代码,或是符合证据的猜测。能自己验证的地方给出了命令。

## 1. 小米遥控器开机时发生了什么

- 观察到:电视不是从浅睡眠唤醒。`settings get global boot_count` 加 1,电视关机和启动期间在网络上不可达,开机后 `adb` 端口又被关闭。
- 观察到:盒子通过 CEC 发出 `<Text View On>` 和 `<Active Source>`。电视起来后 `getprop switch.in.tv` 是 `E_INPUT_SOURCE_HDMI`,说明电视确实选了 HDMI。
- 观察到:此时前台是酒店桌面 `com.changhong.hotelv3/com.changhong.MainActivity`,不是 HDMI 播放界面
  `com.changhong.tvap.atv.activity/.AtvMainActivity`。桌面把画面盖住了。
- 观察到:`am start -n com.changhong.tvap.atv.activity/.AtvMainActivity` 可以把盒子画面调回来。
- 观察到:`AtvMainActivity` 没有 LAUNCHER 或 LEANBACK 分类,所以"开机启动应用"类软件选不到它。

## 2. HDMI-CEC 行为

- 电视的 CEC 页面(长虹自己的设置,不是 Android TV 设置)有 4 个开关:HDMI CEC、电视自动开机、设备自动关机、ARC。全部是开。盒子只有一个"CEC控制"开关。
- 观察到:用电视遥控器关电视时盒子一起关。所以电视的"设备自动关机"是"电视到设备"这个方向。
- 观察到:用小米遥控器关盒子时,盒子发出 `<Standby>`,电视在链路层确认收到,但不关机。在盒子上执行 `dumpsys hdmi_control` 能看到 CEC 历史:
  - 默认 `power_control_mode = broadcast`:`[S] <Standby> 4F:36 (ACK)`
  - 执行 `cmd hdmi_control cec_setting set power_control_mode to_tv` 之后:`[S] <Standby> 40:36 (ACK)`
  电视对两种都没有反应。事后这个设置已改回 `broadcast`。
- 推断:电视 CEC 代码的 Java 层只向上报两种回调(信号源切换、设备名变化),待机处理在原生层,电视不处理来自播放设备的待机请求。
- 推断(来自电视自带系统应用的代码,不是运行时追踪):电视处于睡眠(`sys.pm.sleep.flag=1`)时,只有开启了 CEC"电视自动开机",CEC 信号源切换才会被执行。

## 3. 固件对后台应用的策略

- 观察到:第三方应用启动后很快被强制停止。`dumpsys package <包名>` 显示 `stopped=true`,`dumpsys activity broadcasts` 里有
  `system_server` 发出的 `PACKAGE_RESTARTED`。
- 观察到:启动过的应用会被追加到 `/data/chuserdata/killbackground`。
- 推断(来自 `services.jar` 里的字符串):不是系统应用、也不在 `/chperm/preapp/killbackgroundwhitelist` 里的包会被当作
  "非保护应用",被杀并阻止其广播接收。把包名加进白名单并重启后,进程能一直存活,`stopped=false`。
- 观察到但没有查清原因:即便如此,清单里注册的广播接收器也从未被调用。给接收器发显式广播(包名在白名单与不在白名单两种情况都试过),
  以及系统自己发的 `MY_PACKAGE_REPLACED`,都没有进入 `onReceive`。这些广播记录在 `dumpsys activity broadcasts` 里
  `dispatchClockTime=1970-01-01`,表示没有解析到任何接收器。因此没有依赖 `BOOT_COMPLETED` 接收器。
- 没有测试过:不加白名单的情况下,无障碍触发是否同样有效。

## 4. 为什么用无障碍服务做开机触发

- 观察到:无障碍服务写入 `enabled_accessibility_services` 后,系统每次开机都会自动绑定它。日志显示它在开机后约 4 秒连上,约 1 秒后收到桌面窗口事件。
  `tv-app` 就是对这个事件做出反应。
- 观察到:开机后几秒内桌面窗口可能出现好几次(一份日志里 5 秒内出现 5 次),时间也不固定。固定时间表的重试不够用:它会在大约 50 秒内把用户从电视桌面拉回画面。
  所以应用改为监听窗口事件,HDMI 播放界面连续在前台 6 秒后就停止。
- 用同样方式装在小米盒子(Android 14)上的无障碍服务,盒子休眠或重启后会把它从 `enabled_accessibility_services` 里移除,在那里不是可靠的触发器。

## 5. 检测盒子关机,并让电视待机

- 观察到:盒子休眠后约 1 秒内,`ITVPlayer.isSignalStable()` 从 `true`(状态 1)变为 `false`(状态 0)。
  `TVManager.getSourceManager().getCurInputSourceAndScence()` 返回当前信号源名称(`E_INPUT_SOURCE_HDMI`)。
- 这两个调用都经过长虹框架客户端 `com.changhong.tvos`,它在这个固件的 boot class path 上(`echo $BOOTCLASSPATH`)。应用通过反射访问:
  `TVManager.getInstance(ctx).getSystemManager().systemStandby(false)`。通过同一个客户端做只读探测,拿到了真实的产品名(`50J5500UH`),说明 binder 往返是通的。
- 观察到:`systemStandby(false)` 让电视待机,效果与电源键一致。应用记录了 `signal lost on E_INPUT_SOURCE_HDMI, waiting 8000 ms`(首次测试的等待是 8 秒,现在是 3 秒),7 秒后记录
  `standby (HDMI signal lost)`,电视随即关机。
- 观察到:开机过程中当前信号源依次是 `E_INPUT_SOURCE_OTHER`(电视自己的桌面,无信号)-> `E_INPUT_SOURCE_HDMI`(还没有信号)-> `E_INPUT_SOURCE_HDMI`(稳定)。
  用户回到电视桌面时,信号源变成 `E_INPUT_SOURCE_OTHER`,`isSignalStable()` 为 false。所以只有 HDMI 是当前信号源时,监视器才看得到盒子;
  在桌面上关盒子,用这种方式检测不到。
- 推断(来自固件):`systemStandby(false)` 最终走到 `sendPowerKey()`,`systemStandby(true)` 调用 `enterPCSleep()`。
- 观察到,也是一个警告:电视上其他系统应用内置了同一个接口的客户端副本,事务号各不相同(例如 `chtvCommonApi` 在一个里是 162,另一个里是 102)。
  用这些副本里的号码手动对运行中的服务执行 `service call TVService <code>`,返回 `UNKNOWN_TRANSACTION`,其中一次还让服务崩溃,电视随后重启。
  请使用框架自带的客户端,不要手写事务号。

## 6. 没有成功或有意没碰的

- 盒子发出的 CEC `<Standby>`(广播和直接发给电视两种):电视忽略。
- 清单里的 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 接收器:在这个固件上收不到。
- 在盒子上装一个无障碍应用,监听息屏后通过局域网请求电视关机:放弃,因为盒子会把它从启用列表里移除。
- 电视的酒店配置应用(`com.changhong.hotel.config`)有开机信源等选项,但打开后是激活码页面。没有使用,也没有绕过激活。
- 没有尝试用无障碍的全局动作(电源菜单、锁屏)来关机。

## 7. 常用命令

```sh
# 电视
adb -s $TV:5555 shell settings get global boot_count
adb -s $TV:5555 shell getprop switch.in.tv
adb -s $TV:5555 shell dumpsys activity activities | grep mResumedActivity
adb -s $TV:5555 shell cat /data/chuserdata/killbackground
adb -s $TV:5555 shell cat /data/data/local.hdmiboot/files/log.txt
adb -s $TV:5555 shell am startservice -n local.hdmiboot/.LaunchService --es cmd probe
# 盒子(Android 14)
adb -s $BOX:5555 shell dumpsys hdmi_control          # CEC 设置和消息历史
adb -s $BOX:5555 shell cmd hdmi_control cec_setting get power_control_mode
```
