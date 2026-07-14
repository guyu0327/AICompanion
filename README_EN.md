# AI Companion - Minecraft AI Companion Mod

A Minecraft NeoForge mod powered by Large Language Models (LLM). It spawns an AI companion that looks identical to a player (Steve skin), capable of autonomously observing the environment, analyzing situations, and deciding on actions — chopping trees, mining, fighting, and chatting with you, like a true adventuring partner.

## Features

- **AI Autonomous Decision-Making**: The companion analyzes the surrounding environment in real-time via AI (blocks, entities, inventory, hunger, time/weather, etc.) and independently decides the next action
- **Complete Action System**: Supports 11 action types including movement, mining, attacking, chatting, eating, sleeping, dropping/using items, and placing blocks
- **Intelligent Combat**: Automatically selects the best weapon from inventory, uses bows with ballistic prediction at range, switches to swords/axes for melee; actively retreats against strong enemies
- **Auto Armor Equipment**: Automatically equips better armor from inventory, armor is visually displayed on the body (helmet, chestplate, leggings, boots)
- **Self-Preservation**: Automatically interrupts actions when standing on dangerous blocks (lava/cactus/fire); retreats when health is low
- **Hunger & Saturation System**: Mirrors player hunger mechanics (0-20) with saturation; movement, combat, and mining deplete saturation, then hunger when saturation is depleted
- **Natural Regeneration**: Automatically regenerates health when hunger >= 18 and saturation > 0 (same as player mechanics)
- **Auto-Eating**: Automatically takes food from inventory when hungry, with complete eating animation and chewing sound effects; asks the player for food when inventory is empty
- **Sleep Healing**: Restores health while sleeping
- **Auto Pickup**: Automatically picks up nearby dropped items and stores them in inventory
- **Death & Respawn**: Companion respawns near the player after 10 seconds upon death, with configurable item retention or dropping
- **Mod Item Compatibility**: Uses vanilla API and Tag system to determine tools/weapons/armor, automatically supporting items added by various mods

## Installation

### Requirements

- Minecraft 1.21.x
- NeoForge 26.1.2+
- Java 21+

### Installation Steps

1. Download the latest `aicompanion-x.x.x.jar`
2. Place it in your Minecraft `mods` folder
3. Launch the game and enter a world

### First Launch

When you first enter a game world, the companion will automatically spawn near you in **Follow Mode**. You can interact with it through chat or right-click to switch its behavior modes.

## Configuration

The mod configuration file is located at `config/aicompanion.toml`, containing three sections:

### API Connection

| Option | Description | Default |
|--------|-------------|---------|
| `apiUrl` | Base URL for the AI API | `https://api.openai.com` |
| `apiKey` | API authentication key (**required**) | Empty |
| `endpoint` | Chat completions endpoint path | `/v1/chat/completions` |
| `timeout` | HTTP request timeout in seconds | `30` |

Supports any service compatible with OpenAI Chat Completions API (OpenAI, DeepSeek, local Ollama, etc.).

### Model Parameters

| Option | Description | Default |
|--------|-------------|---------|
| `modelName` | Model identifier | `gpt-3.5-turbo` |
| `temperature` | Sampling temperature (0.0 ~ 2.0) | `0.7` |
| `maxTokens` | Maximum response tokens | `1024` |
| `enableJsonMode` | Enable JSON output mode (disable for APIs that don't support it) | `true` |

### Behavior Settings

| Option | Description | Default |
|--------|-------------|---------|
| `systemPrompt` | System prompt defining the companion's personality | `You are a friendly and helpful Minecraft AI companion.` |
| `dropItemsOnDeath` | Whether the companion drops inventory and held items on death | `false` |

## Interacting with the Companion

### Behavior Modes

**Right-click** the companion with an empty hand to cycle through three modes:

| Mode | Behavior |
|------|----------|
| **Follow** | Follows the player when distance > 4 blocks, AI autonomously decides when idle |
| **Stand** | Stays in place, skips AI decisions, but still auto-retaliates against enemies and picks up items |
| **Free** | AI fully autonomous, not constrained by player position |

### Inventory Management

**Shift + Right-click** the companion to open its 27-slot inventory GUI, where you can store or retrieve items. Mined blocks and picked up items are automatically stored in the inventory.

### Chat Interaction

Send messages in the chat box. If the message contains `@AI` or the companion's name, the companion will immediately receive and respond to it. The companion can also hear other chat messages as background information.

## Commands

All commands require admin privileges (op level 2).

### `/aicompanion spawn`

Manage the companion's presence:
- **No companion in the world** → Spawns a new companion at the player's location
- **Companion exists** → Teleports the companion to the player
- **Companion is respawning** → Shows remaining wait time

### `/aicompanion action`

Manually control the companion to execute specific actions (for debugging):

```
/aicompanion action <player> move <x> <y> <z>    Move to specified coordinates
/aicompanion action <player> move-here            Walk to the command executor's position
/aicompanion action <player> mine <x> <y> <z>     Mine block at specified coordinates
/aicompanion action <player> mine-below           Mine the block below the companion
/aicompanion action <player> attack <entity>      Attack entities of specified type
/aicompanion action <player> chat <message>       Make the companion speak
/aicompanion action <player> wait                 Wait in place
/aicompanion action <player> eat                  Eat food
/aicompanion action <player> sleep                Go to sleep
/aicompanion action <player> wake                 Wake up
/aicompanion action <player> drop                 Drop held item
/aicompanion action <player> cancel               Cancel current action
/aicompanion action <player> status               View current status
```

The `<player>` selector automatically finds the closest AI companion to the command executor.

## Game Mechanics

### Death & Respawn

- The companion respawns near the player **10 seconds** after death
- Players are notified at 5 seconds and 2 seconds remaining
- Health and hunger are fully restored upon respawn
- `dropItemsOnDeath = false` (default): Items are not dropped, restored after respawn
- `dropItemsOnDeath = true`: Items scatter on the ground, can be picked up within 5 minutes, inventory is empty after respawn

### Combat System

- Automatically detects hostile mobs within 16 blocks (zombies, skeletons, spiders, etc.), **does not attack friendly mobs** (villagers, pets, iron golems, etc.)
- Automatically switches weapons based on distance: bows/crossbows at range, swords/axes in melee
- Uses ballistic prediction for bow aiming (marksman-level accuracy)
- Weapon switching includes mode lock to prevent rapid toggling at distance thresholds
- Actively retreats when health drops below 30%
- Combat consumes saturation (hit +0.1, miss +0.3)

### Armor System

- Automatically evaluates and equips better armor from inventory (helmet, chestplate, leggings, boots)
- Armor is visibly displayed on the companion's body, matching player appearance
- Checks for armor upgrades every 10 ticks (0.5 seconds)
- Uses vanilla `ItemAttributeModifiers` to read armor attributes, compatible with modded armor

### Hunger & Eating System

- **Hunger** (0-20): Matches player mechanics, auto-eats when below 7, takes starvation damage when at 0
- **Saturation** (0-20): Restored by eating, consumed before hunger; enables natural regeneration when high
- **Exhaustion**: Movement, jumping, combat, and mining all increase exhaustion; 4 points of exhaustion deplete 1 point of saturation or hunger
- **Eating Animation**: Takes food from inventory to main hand, plays 1.6-second eating animation with chewing sounds (every 5 ticks)
- **Consecutive Eating**: If still hungry after eating, waits 1 second cooldown before eating again to prevent rapid-fire eating
- **Requesting Food**: When the inventory has no food and the companion is hungry, it will ask the player for food in chat

### Tool Selection

Automatically selects the most suitable tool from inventory:
- Uses vanilla `isCorrectToolForDrops` to determine the correct tool
- Uses vanilla `getDestroySpeed` to calculate mining speed
- Automatically supports all vanilla and modded tools

## Compatibility

- Uses vanilla API and Tag system to determine item properties, **automatically compatible with most mods**
- Supports any LLM service compatible with OpenAI Chat Completions API

## Tech Stack

- **NeoForge** 26.1.2 (Minecraft 1.21.x)
- **Java** 21
- **Gson** for JSON serialization
- **Java HttpClient** for asynchronous API requests

## Development

```bash
# Build
./gradlew build

# Run in-game environment
./gradlew runClient
```

## License

This project is for educational and non-commercial use only.