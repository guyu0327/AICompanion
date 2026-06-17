package com.guyu.aicompanion.menu;

import com.guyu.aicompanion.AICompanion;
import com.guyu.aicompanion.entity.AICompanionEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * AI 同伴背包菜单 — 类似原版箱子 GUI。
 * <p>
 * 服务端通过 {@link AICompanionEntity} 的 {@code getInventory()} 直接获取背包引用；
 * 客户端通过同步的实体 ID 从世界中查找实体来获取背包。
 */
public class CompanionInventoryMenu extends AbstractContainerMenu {

    /** 同伴背包格数（3 行 × 9 列 = 27） */
    private static final int COMPANION_SLOTS = 27;
    /** 同伴背包格在 slots 列表中的起始索引 */
    private static final int COMPANION_SLOT_START = 0;
    /** 玩家背包格在 slots 列表中的起始索引 */
    private static final int PLAYER_SLOT_START = COMPANION_SLOTS;

    private final Container companionInventory;
    private final AICompanionEntity companion;

    /**
     * 服务端构造器 — 直接持有同伴实体和背包引用。
     */
    public CompanionInventoryMenu(int containerId, Inventory playerInv,
                                  AICompanionEntity companion) {
        this(containerId, playerInv, companion, companion.getInventory(), null);
    }

    /**
     * 客户端构造器 — 从网络缓冲区读取实体 ID，在世界中查找实体。
     */
    public CompanionInventoryMenu(int containerId, Inventory playerInv,
                                  RegistryFriendlyByteBuf data) {
        this(containerId, playerInv, resolveClientEntity(playerInv, data), null, data);
    }

    /**
     * 主构造器 — 服务端和客户端共用。
     */
    private CompanionInventoryMenu(int containerId, Inventory playerInv,
                                   AICompanionEntity companion,
                                   Container companionInv,
                                   RegistryFriendlyByteBuf data) {
        super(AICompanion.COMPANION_INVENTORY.get(), containerId);

        // 客户端侧：从 buffer 中获取背包
        Container resolvedInv = companionInv;
        if (resolvedInv == null && companion != null) {
            resolvedInv = companion.getInventory();
        }
        if (resolvedInv == null) {
            // 极端情况：找不到实体，用空容器代替避免 NPE
            resolvedInv = new SimpleContainer(COMPANION_SLOTS);
        }

        this.companion = companion;
        this.companionInventory = resolvedInv;

        // 添加同伴背包槽位（3 行 × 9 列）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(resolvedInv, col + row * 9,
                        8 + col * 18, 18 + row * 18));
            }
        }

        // 添加玩家背包 + 快捷栏
        this.addStandardInventorySlots(playerInv, 8, 85);

        // 通知容器开始打开
        resolvedInv.startOpen(playerInv.player);
    }

    /**
     * 客户端侧：从 buffer 读取实体 ID，在世界中查找同伴实体。
     */
    private static AICompanionEntity resolveClientEntity(Inventory playerInv,
                                                         RegistryFriendlyByteBuf data) {
        if (data == null) return null;
        int entityId = data.readVarInt();
        Level level = playerInv.player.level();
        var entity = level.getEntity(entityId);
        if (entity instanceof AICompanionEntity ace) {
            return ace;
        }
        return null;
    }

    @Override
    public boolean stillValid(Player player) {
        // 同伴必须存活且玩家在 8 格内
        if (companion != null && !companion.isAlive()) return false;
        if (companion != null && companion.distanceToSqr(player) > 64.0) return false;
        return companionInventory.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);

        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();

            if (slotIndex < COMPANION_SLOTS) {
                // 从同伴背包 → 玩家背包
                if (!this.moveItemStackTo(stack, PLAYER_SLOT_START, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 从玩家背包 → 同伴背包
                if (!this.moveItemStackTo(stack, COMPANION_SLOT_START, COMPANION_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }

        return result;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        companionInventory.stopOpen(player);
    }

    /** 获取同伴实体引用（可能为 null） */
    public AICompanionEntity getCompanion() {
        return companion;
    }
}
