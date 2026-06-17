package com.guyu.aicompanion;

import org.slf4j.Logger;

import com.guyu.aicompanion.command.ActionCommand;
import com.guyu.aicompanion.command.SpawnCommand;
import com.guyu.aicompanion.entity.AICompanionEntity;
import com.guyu.aicompanion.event.AICompanionEventHandler;
import com.guyu.aicompanion.event.ChatHandler;
import com.mojang.logging.LogUtils;

import com.guyu.aicompanion.menu.CompanionInventoryMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// 此处的值必须与 META-INF/neoforge.mods.toml 中的条目匹配
@Mod(AICompanion.MODID)
public class AICompanion {
    // Mod ID，所有引用的公共位置
    public static final String MODID = "aicompanion";
    // slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // 方块延迟注册表，所有方块在 "aicompanion" 命名空间下注册
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // 物品延迟注册表，所有物品在 "aicompanion" 命名空间下注册
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // 创造模式标签页延迟注册表
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    // 实体类型延迟注册表
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    // 菜单类型延迟注册表
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);

    // AI 同伴实体类型注册
    public static final DeferredHolder<EntityType<?>, EntityType<AICompanionEntity>> COMPANION =
            ENTITY_TYPES.register("ai_companion",
                    () -> EntityType.Builder.of(AICompanionEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, 1.8F)
                            .eyeHeight(1.62F)
                            .clientTrackingRange(10)
                            .updateInterval(3)
                            .build(ResourceKey.create(Registries.ENTITY_TYPE,
                                    Identifier.fromNamespaceAndPath(MODID, "ai_companion")))
            );

    // AI 同伴背包菜单类型 — 使用 IMenuTypeExtension 支持向客户端传递实体 ID
    public static final DeferredHolder<MenuType<?>, MenuType<CompanionInventoryMenu>> COMPANION_INVENTORY =
            MENUS.register("companion_inventory",
                    () -> IMenuTypeExtension.create(CompanionInventoryMenu::new));

    // 创建方块 "aicompanion:example_block"
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", p -> p.mapColor(MapColor.STONE));
    // 创建对应的方块物品
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    // 创建食物物品 "aicompanion:example_item"，营养值 1，饱和度 2
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", p -> p.food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // 创建创造模式标签页 "aicompanion:example_tab"，位于战斗标签页之后
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.aicompanion")) // CreativeModeTab 标题的语言键
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(EXAMPLE_ITEM.get()); // 将示例物品添加到标签页。对于自定义标签页，此方法优于事件方式
            }).build());

    // Mod 类的构造函数是 mod 加载时最先运行的代码
    // FML 会自动识别 IEventBus 或 ModContainer 等参数类型并传入
    public AICompanion(IEventBus modEventBus, ModContainer modContainer) {
        // 注册 commonSetup 方法
        modEventBus.addListener(this::commonSetup);

        // 将各延迟注册表注册到 mod 事件总线
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        MENUS.register(modEventBus);

        // 注册实体属性
        modEventBus.addListener(this::registerEntityAttributes);

        // 将自身注册到 NeoForge 事件总线以响应服务器等游戏事件
        // 注意：仅当本类中有 @SubscribeEvent 注解的方法时才需要
        NeoForge.EVENT_BUS.register(this);

        // 注册事件处理器，使敌对生物会以 AI 同伴为目标
        NeoForge.EVENT_BUS.register(new AICompanionEventHandler());

        // 注册聊天处理器，用于玩家 ↔ AI 同伴通信
        NeoForge.EVENT_BUS.register(new ChatHandler());

        // 将物品添加到创造模式标签页
        modEventBus.addListener(this::addCreative);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
    }

    // 将示例方块物品添加到建筑方块标签页
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
    }

    // 使用 SubscribeEvent 让事件总线自动发现要调用的方法
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
    }

    // 实体设置时注册实体属性
    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(COMPANION.get(),
                Mob.createMobAttributes()
                        .add(Attributes.MAX_HEALTH, 40.0)
                        .add(Attributes.MOVEMENT_SPEED, 0.3)
                        .add(Attributes.ATTACK_DAMAGE, 4.0)
                        .build()
        );
    }

    // 在 NeoForge 事件总线上注册命令
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SpawnCommand.register(event.getDispatcher());
        ActionCommand.register(event.getDispatcher());
    }
}
