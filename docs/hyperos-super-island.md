# HyperOS 超级岛适配

生成服务将超级岛参数附加到同一条前台通知（ID 31001），不额外发送通知。
系统的 `notification_focus_protocol` 至少为 3 时启用扩展；其他系统保留普通通知。
无超级岛权限时，`filterWhenNoPermission=false` 允许系统退回普通通知。

摘要态显示“生成中”或“保存中”和聊天图标，展开态显示完整状态、会话数量和停止按钮。
保存阶段不显示停止按钮。停止操作复用原通知的 PendingIntent，绑定本次生成的 attempt ID，
旧通知不能停止后来的重试。全部任务结束后，服务移除通知，超级岛随通知移除。
通知不包含提问、回答、会话标题或供应商信息，不估算生成百分比。

## 配置和联调

1. 在小米开发者平台为应用开通超级岛服务，提交后台生成场景，登记包名和签名证书。
2. 获得 App ID 后，在本机 Gradle 用户配置中设置 `xiaomiSuperIslandAppId=实际AppId`，
   或构建时传入 `-PxiaomiSuperIslandAppId=实际AppId`。
   未配置时 ID 为空，不能保证获得超级岛展示权限。
3. Manifest 的 `com.xiaomi.xms.APP_ID` 使用字符串资源，避免数字 ID 被解析成数字类型。
   `com.xiaomi.xms.BUILD_TYPE_DEBUG` 在 debug 为 true、release 为 false，需匹配平台登记的签名。
4. 使用平台的设备白名单在支持超级岛的 HyperOS 3 真机联调，正式上线还需通过平台审核。
   本地客户端实现不需要接入 MiPush。

真机检查：生成时切后台、展开岛返回应用、多个会话数量变化、保存阶段按钮消失、停止和自然完成后移除、
立即发起下一轮重新展示、关闭焦点通知权限后普通通知仍可停止生成。

单元测试验证 JSON 模板、图标和动作引用、保存阶段和 3072 字节限制。
Android 仪器测试通过临时设置协议版本验证扩展、PendingIntent 和普通通知回退，并恢复原设置；
模拟器不包含小米 SystemUI，不能验证实际岛样式或平台鉴权。

## 官方依据

- [开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)
- 该指南链接的《小米超级岛模板库》2026-01-29 版，第 95–96 页使用 `textInfo` 字段；网页示例中有字段拼写异常，以模板库为准。
- [接入流程及 App ID、签名配置](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)
- [常见问题](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146)
