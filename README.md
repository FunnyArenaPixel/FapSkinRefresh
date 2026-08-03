# FapSkinRefresh

> **FAPIXEL 玩家隐身修复插件** — 针对网易中国版 3.9（V860 协议）客户端的玩家隐身问题

---

## 一、问题背景

网易我的世界中国版 3.9 客户端更新（V860 协议）后，玩家**偶尔完全隐身**——皮肤、头顶 nametag、手持物品全部不显示，只剩影子。具体现象：

- 默认皮肤（史蒂夫）高概率触发
- 部分玩家看得到、部分看不到（视角相关）
- 有时自己看自己也隐身（第三人称 / 暂停页面）
- 可能自动恢复，然后又隐身
- 在多服切换（WaterdogPE 代理跳转）后尤其频繁

### 已有的修复手段

| 插件 | 所在层 | 作用 | 局限 |
|---|---|---|---|
| **NetEasePlayerListFix** | WaterdogPE 代理层 | 拦截 `PlayerListPacket(ADD)`，对 V860 客户端做三态去重（ADD / SUPPRESS / REPLACE），避免重复 ADD 触发 V860 渲染 bug | 只拦截 `PlayerListPacket`；当客户端内部状态已损坏，代理会因皮肤相同而**抑制修复包** |
| **SkinValidationFix** | Nukkit 后端 | 修复 NukkitMaster 皮肤验证请求中 `geo_list` 字段导致验证失败的问题 | 只解决验证阶段，不解决渲染阶段 |

**核心矛盾**：当 V860 客户端的 PlayerList 条目因内部 bug 失效后，服务端尝试通过 `PlayerListPacket(ADD)` 修复，但代理层发现皮肤相同 → 抑制 ADD → 客户端永远收不到修复包 → 玩家隐形，直到客户端自己偶然恢复。

---

## 二、解决原理

利用 **WaterdogPE 代理层只拦截 `PlayerListPacket`** 这一特性，改用代理**不拦截**的数据包类型来强制刷新客户端状态：

```
代理层拦截范围（NetEasePlayerListFix）
    └── PlayerListPacket ✗ 被拦截去重

以下数据包代理均不拦截：
    ├── PlayerSkinPacket  ✓ 直接到达客户端
    ├── RemoveEntityPacket ✓ 直接到达客户端
    └── AddPlayerPacket   ✓ 直接到达客户端
```

### 三道防线

| 防线 | 机制 | 默认间隔 | 修复场景 | 副作用 |
|---|---|---|---|---|
| **① 皮肤数据刷新** | 向所有在线玩家互发 `PlayerSkinPacket` | 60 秒 | 条目存在但皮肤数据失效 | 无 |
| **② 实体重置** | `despawnFromAll()` + 延迟 `spawnToAll()` | 180 秒 | 实体渲染完全失败、引用失效 | 极短暂闪烁（约 1 tick） |
| **③ 进服延迟刷新** | 玩家进服 5 秒后向所有人发皮肤包 | 事件触发 | 进服时初始皮肤未同步 | 无 |

### 客户端请求刷新（可选增强）

插件注册了 PyRpc 事件 `RequestSkinRefreshEvent`。客户端可通过 `self.NotifyToServer("RequestSkinRefreshEvent", {})` 主动请求，服务端会**只刷新请求者附近 128 格内**的玩家（精准刷新，避免全服广播）。

> 目前无需修改客户端行为包即可工作。客户端主动请求是后续锦上添花的增强项。

---

## 三、安装与配置

### 环境要求

- 服务端核心：**Nukkit-MOT**
- 依赖插件：**NukkitMaster**（提供 PyRpc 通信能力）

### 安装

将 `FapSkinRefresh.jar` 放入 Nukkit 子服的 `plugins/` 目录，重启服务端。

### 配置文件

配置位于 `plugins/FapSkinRefresh/config.yml`，首次启动自动生成：

```yaml
# 定期皮肤刷新
refresh:
  enabled: true
  interval: 60        # 刷新间隔（秒）
  batch_size: 10      # 每批处理的玩家对数量

# 全量实体刷新（despawn + spawn，有极短闪烁）
full_refresh:
  enabled: true
  interval: 180       # 刷新间隔（秒）
  batch_size: 5       # 每轮处理的玩家数量
  spawn_delay: 1      # despawn 和 spawn 之间的延迟（tick）

# 客户端请求刷新
client_request:
  enabled: true
  cooldown: 15        # 请求冷却（秒）
  radius: 128         # 刷新半径（格）

# 进服延迟刷新
join_refresh:
  enabled: true
  delay: 5            # 进服后延迟（秒）

debug: false          # 诊断日志
```

**调优建议**：
- 如果 180 秒的实体重置闪烁明显 → `full_refresh.enabled: false`
- 如果隐身问题严重 → 降低 `refresh.interval` 到 30 秒
- 排查问题 → `debug: true` 观察控制台日志

---

## 四、管理命令

```
/fapskin                     查看运行状态
/fapskin refresh <玩家名>     手动刷新指定玩家的皮肤
/fapskin refresh all         手动触发全量刷新（皮肤 + 实体）
/fapskin reload              重载配置文件
```

需要权限节点 `fapskin.admin`（默认 OP）。

---

## 五、后续开发方向

本项目目前是**纯服务端被动防御**方案。以下方向按优先级排列：

### 1. 客户端主动监测（推荐下一步）

当前依赖服务端定时广播，无法在玩家隐身的**瞬间**立即修复。计划在 FapModMain 客户端行为包中增加：

- 定期（如每 10 秒）检测附近玩家的渲染状态
- 发现隐身玩家时立即通过 PyRpc 请求 `RequestSkinRefreshEvent`
- 配合服务端的精准刷新（半径过滤），实现**即时修复 + 极低开销**

### 2. 自适应刷新策略

当前定时任务是固定间隔。可增强为：

- 记录每次刷新后客户端的反馈（是否有玩家重新出现）
- 对频繁出问题的时段 / 玩家提高刷新频率
- 对稳定时段降低频率，减少不必要的数据包开销

### 3. 代理层增强（WaterdogPE 插件）

目前利用的是"代理不拦截非 PlayerListPacket"这个事实。更彻底的方案是在代理层增加：

- 对 `PlayerSkinPacket` 做健康检查（皮肤数据是否完整）
- 主动注入修复包，而不是依赖后端插件

### 4. 多服去重

当前每个子服各自运行刷新任务。在跨服跳转场景下，可考虑：

- 在代理层（或独立服务）统一管理刷新状态
- 避免玩家刚跳转就被两个子服同时刷新造成冲突

### 5. 数据指标

- 记录隐身发生的频率、恢复时间
- 为后续优化提供数据支撑

---

## 六、技术栈

- **语言**：Java 21
- **服务端**：Nukkit-MOT（基岩版第三方服务端）
- **依赖**：NukkitMaster（PyRpc 通信框架）
- **通信协议**：基岩版协议（PlayerSkinPacket / AddPlayerPacket / RemoveEntityPacket）

---

## 七、项目结构

```
FapSkinRefresh/
├── src/main/java/cn/fapixel/fapskinrefresh/
│   └── FapSkinRefresh.java        # 主类（监听器 + 定时任务 + 命令）
├── src/main/resources/
│   ├── plugin.yml                 # 插件描述
│   └── config.yml                 # 配置文件
├── libs/                          # 依赖 jar（gitignore，不入库）
├── build.py                       # 编译 + 打包 + 部署脚本
└── README.md
```

---

*FAPIXEL 小游戏服务器 · FunnyArenaPixel*
