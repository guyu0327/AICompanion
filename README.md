# AI Companion - Minecraft AI 同伴模组

一个基于大语言模型（LLM）的 Minecraft NeoForge 模组。它会生成一个外观与玩家相同的 AI 同伴，能够自主观察环境、分析情况并决定行动 —— 砍树、挖矿、战斗、与玩家聊天，仿佛一个真正和你一起冒险的伙伴。

## 功能特性

- **AI 自主决策**：同伴通过 AI 实时分析周围环境（方块、实体、背包、饥饿值、时间天气等），自主决定下一步行动
- **完整动作系统**：支持移动、挖掘、攻击、聊天、进食、睡觉、丢弃 / 使用物品、放置方块等 11 种动作
- **智能战斗**：自动从背包中选择最佳武器，远距离使用弓箭（含弹道预测），近距离切换剑 / 斧近战；遇到强敌会主动撤退
- **自我保护**：脚下有岩浆 / 仙人掌 / 火等危险方块时自动中断动作；血量过低时向反方向撤退
- **饥饿系统**：同伴拥有独立的饥饿值，随时间下降；饥饿时会主动进食，饥饿归零会受伤
- **睡眠回血**：同伴睡觉时每小时回复生命值
- **自动拾取**：自动拾取附近掉落的物品存入背包
- **死亡重生**：同伴死亡后 10 秒在玩家附近重生，根据配置保留或掉落物品
- **兼容 Mod 物品**：使用原版 API 和 Tag 系统判断工具 / 武器，自动支持各类 Mod 添加的物品

## 安装

### 环境要求

- Minecraft 1.21.x
- NeoForge 26.1.2+
- Java 21+

### 安装步骤

1. 下载最新的 `aicompanion-x.x.x.jar`
2. 放入 Minecraft 的 `mods` 文件夹
3. 启动游戏，进入游戏世界

### 首次启动

首次进入游戏时，同伴会自动生成在你附近，并进入 **跟随模式**。你可以通过聊天与它交流，或右键切换它的行为模式。

## 配置

模组的配置文件位于 `config/aicompanion.toml`，包含三个部分：

### API 连接

| 配置项 | 说明 | 默认值 |
|---|---|---|
| `apiUrl` | AI API 的基础 URL | `https://api.openai.com` |
| `apiKey` | API 认证密钥（**必填**） | 空 |
| `endpoint` | Chat completions 接口路径 | `/v1/chat/completions` |
| `timeout` | HTTP 请求超时时间（秒） | `30` |

支持任何兼容 OpenAI Chat Completions API 的服务（OpenAI、DeepSeek、本地 Ollama 等）。

### 模型参数

| 配置项 | 说明 | 默认值 |
|---|---|---|
| `modelName` | 模型标识符 | `gpt-3.5-turbo` |
| `temperature` | 采样温度（0.0 ~ 2.0） | `0.7` |
| `maxTokens` | 响应最大 token 数 | `1024` |
| `enableJsonMode` | 启用 JSON 输出模式（部分 API 不支持需关闭） | `true` |

### 行为设置

| 配置项 | 说明 | 默认值 |
|---|---|---|
| `systemPrompt` | 定义同伴个性的 system prompt | `你是一个友好且乐于助人的 Minecraft AI 同伴。` |
| `dropItemsOnDeath` | 同伴死亡时是否掉落背包和手持物品 | `false` |

## 与同伴互动

### 行为模式

空手 **右键** 同伴可在三种模式间切换：

| 模式 | 行为 |
|---|---|
| **跟随** | 跟随玩家移动（距离 > 4 格时触发），空闲时由 AI 自主决策 |
| **待命** | 原地不动，不执行 AI 决策，但仍会自动反击敌人和拾取物品 |
| **自由** | AI 完全自主行动，不受玩家位置约束 |

### 背包管理

**Shift + 右键** 同伴可打开它的 27 格背包 GUI，可以存放或拿取物品。同伴挖掘的方块、拾取的物品都会自动存入背包。

### 聊天互动

在聊天框中发送消息，如果消息包含 `@AI` 或同伴的名字，同伴会立即收到并回应。同伴也能听到其他聊天内容作为背景信息。

## 命令

所有命令需要管理员权限（op 等级 2）。

### `/aicompanion spawn`

管理同伴的存在：
- **世界中没有同伴** → 在玩家位置生成新同伴
- **同伴已存在** → 将同伴传送到玩家身边
- **同伴重生中** → 显示剩余等待时间

### `/aicompanion action`

手动控制同伴执行指定动作（调试用）：

```
/aicompanion action <player> move <x> <y> <z>    移动到指定位置
/aicompanion action <player> move-here            走到命令执行者脚下
/aicompanion action <player> mine <x> <y> <z>     挖掘指定位置的方块
/aicompanion action <player> mine-below           挖同伴脚下的方块
/aicompanion action <player> attack <entity>      攻击指定类型的实体
/aicompanion action <player> chat <message>       让同伴说话
/aicompanion action <player> wait                 原地等待
/aicompanion action <player> eat                  进食
/aicompanion action <player> sleep                睡觉
/aicompanion action <player> wake                 醒来
/aicompanion action <player> drop                 丢弃手持物品
/aicompanion action <player> cancel               取消当前动作
/aicompanion action <player> status               查看当前状态
```

`<player>` 选择器会自动找到距离命令执行者最近的 AI 同伴。

## 游戏机制

### 死亡与重生

- 同伴死亡后 **10 秒**在玩家附近重生
- 倒计时 5 秒和 2 秒时会通知玩家
- 重生后生命值和饥饿值回满
- `dropItemsOnDeath = false`（默认）：物品不掉落，重生后恢复
- `dropItemsOnDeath = true`：物品散落在地上，5 分钟内可拾取，重生后背包为空

### 战斗系统

- 自动检测 16 格内的敌对生物（僵尸、骷髅、蜘蛛等），**不会攻击友好生物**（村民、宠物、铁傀儡等）
- 根据距离自动切换武器：远距离弓箭 / 弩，近距离剑 / 斧
- 弓箭采用弹道预测瞄准（神射手精度）
- 武器切换带模式锁，避免在距离阈值附近来回切换
- 血量低于 30% 时主动撤退

### 工具选择

自动从背包中选择最合适的工具：
- 使用原版 `isCorrectToolForDrops` 判断正确工具
- 使用原版 `getDestroySpeed` 计算挖掘速度
- 自动支持所有原版和 Mod 添加的工具

## 兼容性

- 使用原版 API 和 Tag 系统判断物品属性，**自动兼容绝大多数 Mod**
- 支持任何兼容 OpenAI Chat Completions API 的 LLM 服务

## 技术栈

- **NeoForge** 26.1.2 (Minecraft 1.21.x)
- **Java** 21
- **Gson** 用于 JSON 序列化
- **Java HttpClient** 用于异步 API 请求

## 开发

```bash
# 编译
./gradlew build

# 在游戏环境中运行
./gradlew runClient
```

## 许可证

本项目仅供学习和非商业用途。
