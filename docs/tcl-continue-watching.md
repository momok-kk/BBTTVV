# TCL / Lumen 桌面继续播放（待真机验证）

目标：在 BBTTVV 观看后，从 TCL 原厂桌面或 Lumen 的历史卡片回到同一视频、分 P / 剧集和进度。

## 已知协议与兼容边界

参考 [Lumen TclHistory.kt](https://github.com/SiaBuilt/lumen-tv/blob/91cf8496cf0d3d7348ef78c335903436be0cad00/app/src/main/java/app/orchard/tv/history/TclHistory.kt)：

- 历史 URI：`content://com.tcl.cyberui.historyVideofavourites/HistoryTableName`。
- Lumen 从 `packageName`、`srcApp`、`videoId`、`episodeId`、`videoName`、`episodeName`、`videoImgUrl`、`currentPosition`、`duration`、`receivedtime` 读取展示及回跳数据。
- 回跳为指定包名的 Activity Intent，action 取 `srcApp`，视频/分 P 标识为字符串，进度和时长为毫秒整数。
- 新增记录使用[爱奇艺 OTT 公开的 TV Launcher 广播协议](https://dev.tv.iqiyi.com/third-route-api/broadcast/)：`<本应用包名>.action.ACTION_ADD_PLAYRECORD`，发送标题/封面、视频 ID、以秒表示的当前位置及时长，以及包含毫秒续播点的 `cmdInfo` 给 `com.tcl.cyberui`；完成或重置进度使用文档中的 `ACTION_DELETE_PALYRECORD`。
- **Lumen 源码证明历史读取和回跳方式，公开协议证明广播字段；没有证据证明每版 TCL 固件的 cyberui 都接收第三方应用的该广播或在桌面展示它。** 需要在目标固件验证，不能声称所有 TCL / 雷鸟已支持。

## 本项目行为

- 在 `system` 层按公开 Launcher 广播协议发送记录，目标限定为 `com.tcl.cyberui`；不直接读写其数据库，不申请额外权限。
- 复用播放心跳时机捕获元数据和进度；Home 后立即异步刷新，Back、切视频和结束时异步刷新。桌面同步与服务器上报相互独立，不等待网络，不阻塞播放器释放。
- 未实际播放、互动视频（缺少分支恢复状态）不发布。少于 10 秒、剩余不超过 10 秒、达到 97% 或播放结束时删除本应用对应记录。
- 普通视频和番剧沿既有播放解析链路恢复。外部输入只接受有效的 BV / ep / ss 标识、正 cid、有限毫秒进度，不接受任意播放 URL、组件名或命令。
- 桌面“继续播放”视为用户已确认续播，复用现有确认后的恢复逻辑。Back 沿播放器既有控制层语义返回应用首页；首页保留原有 D-Pad / 二次 Back 退出规则。没有新增焦点控件。
- 不申请额外系统权限，不依赖无障碍服务或 root。没有原厂桌面接收方时系统会忽略广播，播放继续。
- 同步内容包含标题、公开封面、视频标识及观看进度，桌面及有该 Provider 读取能力的应用可见；不写入账号、Cookie、token 或临时播放地址。

## 真机验收

1. 安装目标版本，分别测试游客和登录状态。正常进入首页时默认焦点、TopBar/内容方向导航不变。
2. 观看普通视频超过 18 秒，按 Home；确认 Lumen / 原厂桌面出现 BBTTVV 标题、封面、进度。检查记录只属于 `com.bbttvv.app`（Debug 为 `com.bbttvv.app.debug`），官方视频 App 的记录保持不变。
3. 确认键点击卡片，验证冷启动和后台返回均进入正确视频/分 P、从记录进度播放；关闭“自动续播”设置后，桌面主动续播仍无需二次确认。重复点击、进程重建不叠加旧播放器。
4. 切分 P、切集、连续换视频、seek 前后、断网返回及播放完毕，检查标题/进度不会串片，已看完的卡片消失，网络错误仍提供既有重试路径。
5. 验证播放器控制层、弹层、Back 返回首页及二次退出，检查播放器释放和焦点恢复；直播、互动视频、字幕、弹幕继续使用原链路。
6. 在没有 TCL Launcher 的设备上确认播放正常。

可单独测试回跳（此命令不验证 TCL 写入成功；将示例 BV/cid 换成可播放且匹配的内容）：

```powershell
adb shell am start -a com.bbttvv.app.debug.action.RESUME_PLAYBACK -p com.bbttvv.app.debug --es videoId BV1xx411c7mD --es episodeId 22 --ei currentPosition 45000 --ei duration 120000
```

若没有卡片，需要在目标电视检查 Launcher 广播接收情况、Provider schema 和原厂桌面的启动规则；不要通过伪装官方 App 包名或改写其他 App 历史绕过限制。
