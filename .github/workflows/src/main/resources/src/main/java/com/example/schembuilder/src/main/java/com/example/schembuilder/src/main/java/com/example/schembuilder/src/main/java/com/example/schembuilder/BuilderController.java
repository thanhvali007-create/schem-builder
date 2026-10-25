package com.example.schembuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class BuilderController {
    private static final double VANILLA_REACH_SQ = 4.5 * 4.5;

    public enum State {
        IDLE, BUILDING, PAUSED
    }

    private final ModConfig config;
    private SchematicData currentSchematic;
    private BlockPos origin;
    private State state = State.IDLE;
    private int tickCounter = 0;
    private boolean notifiedOutOfReach = false;

    public BuilderController(ModConfig config) {
        this.config = config;
    }

    public void setSchematic(SchematicData schematic) {
        this.currentSchematic = schematic;
        this.state = State.IDLE;
    }

    public SchematicData getSchematic() {
        return currentSchematic;
    }

    public void setOrigin(BlockPos origin) {
        this.origin = origin;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public State getState() {
        return state;
    }

    public ModConfig getConfig() {
        return config;
    }

    public boolean start(ClientPlayerEntity player) {
        if (currentSchematic == null) {
            chat(player, "§c[Schem] Chưa tải file schematic! Hãy dùng /schem load <tên>");
            return false;
        }
        if (origin == null) {
            origin = player.getBlockPos();
            chat(player, "§e[Schem] Chưa đặt tâm, tự động lấy vị trí hiện tại làm gốc: " + formatPos(origin));
        }
        state = State.BUILDING;
        notifiedOutOfReach = false;
        tickCounter = 0;
        chat(player, "§a[Schem] Bắt đầu xây dựng: " + currentSchematic.fileName);
        return true;
    }

    public void pause(ClientPlayerEntity player) {
        if (state == State.BUILDING) {
            state = State.PAUSED;
            chat(player, "§e[Schem] Đã tạm dừng xây dựng.");
        }
    }

    public void stop(ClientPlayerEntity player) {
        state = State.IDLE;
        notifiedOutOfReach = false;
        chat(player, "§c[Schem] Đã dừng xây dựng.");
    }

    public void onTick(MinecraftClient client) {
        if (state != State.BUILDING || currentSchematic == null || origin == null) return;
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) return;

        tickCounter++;
        if (tickCounter < config.ticksPerBlock) {
            return;
        }
        tickCounter = 0;

        int total = currentSchematic.entries.size();
        int doneCount = 0;
        Integer activeLayerY = null;
        SchematicData.Entry candidate = null;
        BlockHitResult candidateHit = null;
        boolean hasUnplacedInActiveLayer = false;

        Vec3d eyePos = player.getEyePos();
        Box playerBox = player.getBoundingBox();

        for (SchematicData.Entry entry : currentSchematic.entries) {
            BlockPos worldPos = origin.add(entry.relativePos());
            BlockState currentWorldState = world.getBlockState(worldPos);

            if (isBlockAlreadyMatching(currentWorldState, entry.state())) {
                doneCount++;
                continue;
            }

            int layerY = entry.relativePos().getY();
            if (activeLayerY == null) {
                activeLayerY = layerY;
            }

            // Chỉ xây từng lớp từ dưới lên: hoàn thành lớp Y hiện tại trước khi lên lớp Y+1
            if (layerY > activeLayerY && hasUnplacedInActiveLayer) {
                break;
            }
            hasUnplacedInActiveLayer = true;

            // Kiểm tra tầm với Vanilla (4.5 block) và không đặt đè vào người chơi
            Vec3d center = Vec3d.ofCenter(worldPos);
            if (eyePos.squaredDistanceTo(center) > VANILLA_REACH_SQ) {
                continue;
            }
            if (playerBox.intersects(new Box(worldPos))) {
                continue;
            }

            BlockHitResult hit = findValidNeighborHit(world, worldPos, entry.state());
            if (hit != null) {
                candidate = entry;
                candidateHit = hit;
                break;
            }
        }

        if (doneCount >= total) {
            state = State.IDLE;
            actionBar(client, "§a[Schem] Hoàn thành 100% (" + total + "/" + total + " block)!");
            chat(player, "§a[Schem] Công trình đã xây dựng hoàn tất!");
            return;
        }

        int percent = (int) ((doneCount * 100L) / Math.max(1, total));
        int currentY = activeLayerY != null ? activeLayerY : 0;

        if (candidate == null) {
            actionBar(client, "§e[Schem] Tiến độ: " + doneCount + "/" + total + " (" + percent + "%) | Lớp Y=" + currentY + " | §cNgoài tầm với!");
            if (!notifiedOutOfReach) {
                chat(player, "§e[Schem] Hết block có thể đặt trong tầm với ở lớp Y=" + currentY + ". Hãy di chuyển lại gần vị trí cần xây!");
                notifiedOutOfReach = true;
            }
            return;
        }

        notifiedOutOfReach = false;

        // Kiểm tra và lấy block từ Hotbar / Túi đồ
        Item neededItem = candidate.state().getBlock().asItem();
        if (neededItem == Items.AIR) {
            return;
        }

        if (!equipItem(client, player, neededItem)) {
            state = State.PAUSED;
            String blockName = candidate.state().getBlock().getName().getString();
            chat(player, "§c[Schem] Tạm dừng do thiếu vật liệu: §f" + blockName + " §7(" + neededItem.toString() + ")");
            actionBar(client, "§c[Schem] Tạm dừng: Thiếu " + blockName);
            return;
        }

        // Xoay hướng nhìn nếu block yêu cầu hướng từ người chơi (Stairs, Observer, Piston, Furnace, Chest...)
        applyPlayerRotationForState(player, candidate.state());

        // Đặt block vào mặt kề bên
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, candidateHit);
        player.swingHand(Hand.MAIN_HAND);

        actionBar(client, "§a[Schem] Đang xây: " + (doneCount + 1) + "/" + total + " (" + percent + "%) | Lớp Y=" + currentY);
    }

    private boolean isBlockAlreadyMatching(BlockState worldState, BlockState targetState) {
        if (worldState.getBlock() != targetState.getBlock()) {
            return false;
        }
        // Với Slab đôi, cần kiểm tra đã đủ 2 nửa chưa
        if (targetState.contains(Properties.SLAB_TYPE) && worldState.contains(Properties.SLAB_TYPE)) {
            if (targetState.get(Properties.SLAB_TYPE) == SlabType.DOUBLE
                    && worldState.get(Properties.SLAB_TYPE) != SlabType.DOUBLE) {
                return false;
            }
        }
        return true;
    }

    private BlockHitResult findValidNeighborHit(ClientWorld world, BlockPos targetPos, BlockState targetState) {
        Block currentBlock = world.getBlockState(targetPos).getBlock();
        // Trường hợp hoàn thiện Slab đôi từ Slab đơn có sẵn tại targetPos
        if (targetState.getBlock() instanceof SlabBlock
                && targetState.get(Properties.SLAB_TYPE) == SlabType.DOUBLE
                && currentBlock == targetState.getBlock()) {
            SlabType curType = world.getBlockState(targetPos).get(Properties.SLAB_TYPE);
            Direction clickSide = (curType == SlabType.BOTTOM) ? Direction.UP : Direction.DOWN;
            Vec3d hitVec = Vec3d.ofCenter(targetPos);
            return new BlockHitResult(hitVec, clickSide, targetPos, false);
        }

        // Ưu tiên mặt đặt của Hopper hoặc Block có trục Axis (Gỗ, Quartz Pillar...)
        Direction preferredSide = getPreferredClickSide(targetState);
        if (preferredSide != null) {
            BlockPos neighbor = targetPos.offset(preferredSide.getOpposite());
            BlockState neighborState = world.getBlockState(neighbor);
            if (!neighborState.isAir() && neighborState.getFluidState().isEmpty()) {
                Vec3d hitVec = computeHitVec(neighbor, preferredSide, targetState);
                return new BlockHitResult(hitVec, preferredSide, neighbor, false);
            }
        }

        // Duyệt 6 mặt kề bên (ưu tiên mặt UP của block bên dưới để xây từ dưới lên tự nhiên)
        Direction[] order = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
        for (Direction sideToClick : order) {
            BlockPos neighbor = targetPos.offset(sideToClick.getOpposite());
            BlockState neighborState = world.getBlockState(neighbor);
            if (!neighborState.isAir() && neighborState.getFluidState().isEmpty()) {
                Vec3d hitVec = computeHitVec(neighbor, sideToClick, targetState);
                return new BlockHitResult(hitVec, sideToClick, neighbor, false);
            }
        }
        return null;
    }

    private Direction getPreferredClickSide(BlockState targetState) {
        // Hopper trong vanilla nhận hướng đối diện với mặt bị click (facing = side.getOpposite())
        if (targetState.getBlock() instanceof HopperBlock && targetState.contains(Properties.HOPPER_FACING)) {
            Direction hopperFacing = targetState.get(Properties.HOPPER_FACING);
            return hopperFacing.getOpposite();
        }
        // Log / Pillar nhận trục theo mặt click
        if (targetState.contains(Properties.AXIS)) {
            return switch (targetState.get(Properties.AXIS)) {
                case Y -> Direction.UP;
                case Z -> Direction.SOUTH;
                case X -> Direction.EAST;
            };
        }
        return null;
    }

    private Vec3d computeHitVec(BlockPos neighbor, Direction sideClicked, BlockState targetState) {
        double x = neighbor.getX() + 0.5 + sideClicked.getOffsetX() * 0.5;
        double y = neighbor.getY() + 0.5 + sideClicked.getOffsetY() * 0.5;
        double z = neighbor.getZ() + 0.5 + sideClicked.getOffsetZ() * 0.5;

        // Điều chỉnh cao độ điểm chạm Y cho Cầu thang (Stairs), Phiến (Slab), Cửa sập (Trapdoor) khi click vào mặt bên
        if (sideClicked.getAxis().isHorizontal()) {
            if (targetState.contains(Properties.BLOCK_HALF)) {
                BlockHalf half = targetState.get(Properties.BLOCK_HALF);
                y = neighbor.getY() + (half == BlockHalf.TOP ? 0.85 : 0.15);
            } else if (targetState.contains(Properties.SLAB_TYPE)) {
                SlabType slabType = targetState.get(Properties.SLAB_TYPE);
                y = neighbor.getY() + (slabType == SlabType.TOP ? 0.85 : 0.15);
            }
        }
        return new Vec3d(x, y, z);
    }

    private void applyPlayerRotationForState(ClientPlayerEntity player, BlockState targetState) {
        Block block = targetState.getBlock();
        if (targetState.contains(Properties.HORIZONTAL_FACING)) {
            Direction dir = targetState.get(Properties.HORIZONTAL_FACING);
            // Cầu thang (Stairs) đặt cùng hướng nhìn của người chơi; Lò nung/Rương/Cửa sập đặt ngược hướng nhìn
            Direction lookDir = (block instanceof StairsBlock) ? dir : dir.getOpposite();
            player.setYaw(directionToYaw(lookDir));
        } else if (targetState.contains(Properties.FACING)) {
            Direction dir = targetState.get(Properties.FACING);
            // Observer đặt cùng hướng nhìn; Piston/Dispenser/Dropper đặt ngược hướng nhìn
            Direction lookDir = (block instanceof ObserverBlock) ? dir : dir.getOpposite();
            if (lookDir == Direction.UP) {
                player.setPitch(-90.0f);
            } else if (lookDir == Direction.DOWN) {
                player.setPitch(90.0f);
            } else {
                player.setYaw(directionToYaw(lookDir));
                player.setPitch(0.0f);
            }
        }
    }

    private float directionToYaw(Direction dir) {
        return switch (dir) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> -90.0f;
            default -> 0.0f;
        };
    }

    private boolean equipItem(MinecraftClient client, ClientPlayerEntity player, Item item) {
        PlayerInventory inv = player.getInventory();

        // 1. Tìm trong Hotbar (0..8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                setSelectedHotbarSlot(client, inv, i);
                return true;
            }
        }

        // 2. Tìm trong Túi đồ chính (9..35) và đổi xuống Hotbar
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                int targetHotbar = getSelectedHotbarSlot(inv);
                for (int h = 0; h < 9; h++) {
                    if (inv.getStack(h).isEmpty()) {
                        targetHotbar = h;
                        break;
                    }
                }
                client.interactionManager.clickSlot(
                        player.playerScreenHandler.syncId,
                        i,
                        targetHotbar,
                        SlotActionType.SWAP,
                        player
                );
                setSelectedHotbarSlot(client, inv, targetHotbar);
                return true;
            }
        }
        return false;
    }

    /**
     * Tương thích an toàn với cả getSelectedSlot()/setSelectedSlot(int) (1.21.5 - 1.21.11)
     * lẫn trường selectedSlot cũ nếu có.
     */
    private int getSelectedHotbarSlot(PlayerInventory inv) {
        try {
            Method m = PlayerInventory.class.getMethod("getSelectedSlot");
            return (int) m.invoke(inv);
        } catch (Exception e) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
                f.setAccessible(true);
                return f.getInt(inv);
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    private void setSelectedHotbarSlot(MinecraftClient client, PlayerInventory inv, int slot) {
        int current = getSelectedHotbarSlot(inv);
        if (current == slot) return;
        try {
            Method m = PlayerInventory.class.getMethod("setSelectedSlot", int.class);
            m.invoke(inv, slot);
        } catch (Exception e) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
                f.setAccessible(true);
                f.setInt(inv, slot);
            } catch (Exception ignored) {
            }
        }
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    public String getStatusText(ClientWorld world) {
        if (currentSchematic == null) {
            return "§e[Schem] Chưa tải file schematic nào.";
        }
        int total = currentSchematic.entries.size();
        int done = 0;
        if (origin != null && world != null) {
            for (SchematicData.Entry e : currentSchematic.entries) {
                if (isBlockAlreadyMatching(world.getBlockState(origin.add(e.relativePos())), e.state())) {
                    done++;
                }
            }
        }
        int remaining = Math.max(0, total - done);
        return String.format(
                "§b[Schem] File: §f%s §7(%dx%dx%d)\n§b- Trạng thái: §f%s §7| §bTốc độ: §f1 block / %d tick\n§b- Gốc (Origin): §f%s\n§b- Tiến độ: §a%d§7/§f%d §7(Còn lại: §e%d§7)",
                currentSchematic.fileName,
                currentSchematic.sizeX, currentSchematic.sizeY, currentSchematic.sizeZ,
                state.name(),
                config.ticksPerBlock,
                origin != null ? formatPos(origin) : "Chưa đặt",
                done, total, remaining
        );
    }

    private String formatPos(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    private void chat(ClientPlayerEntity player, String msg) {
        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(msg));
    }

    private void actionBar(MinecraftClient client, String msg) {
        client.inGameHud.setOverlayMessage(Text.literal(msg), false);
    }
}
