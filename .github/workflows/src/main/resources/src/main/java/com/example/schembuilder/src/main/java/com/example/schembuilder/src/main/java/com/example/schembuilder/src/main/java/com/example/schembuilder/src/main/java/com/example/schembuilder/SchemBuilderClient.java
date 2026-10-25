package com.example.schembuilder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.Locale;

public class SchemBuilderClient implements ClientModInitializer {
    private BuilderController controller;

    @Override
    public void onInitializeClient() {
        ModConfig config = ModConfig.load();
        controller = new BuilderController(config);

        ClientTickEvents.END_CLIENT_TICK.register(client -> controller.onTick(client));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("schem")
                    .then(ClientCommandManager.literal("list")
                            .executes(ctx -> {
                                File dir = getSchematicsDir();
                                File[] files = dir.listFiles((d, name) -> {
                                    String low = name.toLowerCase(Locale.ROOT);
                                    return low.endsWith(".litematic") || low.endsWith(".schem") || low.endsWith(".nbt");
                                });
                                if (files == null || files.length == 0) {
                                    ctx.getSource().sendFeedback(Text.literal("§e[Schem] Thư mục .minecraft/schematics/ đang trống."));
                                    return 0;
                                }
                                StringBuilder sb = new StringBuilder("§a[Schem] Danh sách file (" + files.length + "): ");
                                for (int i = 0; i < files.length; i++) {
                                    if (i > 0) sb.append("§7, ");
                                    sb.append("§f").append(files[i].getName());
                                }
                                ctx.getSource().sendFeedback(Text.literal(sb.toString()));
                                return files.length;
                            }))
                    .then(ClientCommandManager.literal("load")
                            .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "name").trim();
                                        File file = resolveSchematicFile(name);
                                        if (file == null || !file.exists()) {
                                            ctx.getSource().sendError(Text.literal("§c[Schem] Không tìm thấy file: " + name + " trong .minecraft/schematics/"));
                                            return 0;
                                        }
                                        try {
                                            SchematicData data = SchematicData.load(file);
                                            controller.setSchematic(data);
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    "§a[Schem] Đã tải: §f" + data.fileName +
                                                    " §7(Kích thước: " + data.sizeX + "x" + data.sizeY + "x" + data.sizeZ +
                                                    ", Tổng: " + data.entries.size() + " block)"
                                            ));
                                            return 1;
                                        } catch (Exception e) {
                                            ctx.getSource().sendError(Text.literal("§c[Schem] Lỗi đọc file: " + e.getMessage()));
                                            return 0;
                                        }
                                    })))
                    .then(ClientCommandManager.literal("origin")
                            .executes(ctx -> {
                                BlockPos pos = ctx.getSource().getPlayer().getBlockPos();
                                controller.setOrigin(pos);
                                ctx.getSource().sendFeedback(Text.literal(
                                        "§a[Schem] Đã đặt gốc (Origin) tại: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                ));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("start")
                            .executes(ctx -> controller.start(ctx.getSource().getPlayer()) ? 1 : 0))
                    .then(ClientCommandManager.literal("pause")
                            .executes(ctx -> {
                                controller.pause(ctx.getSource().getPlayer());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("stop")
                            .executes(ctx -> {
                                controller.stop(ctx.getSource().getPlayer());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("status")
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(Text.literal(controller.getStatusText(ctx.getSource().getWorld())));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("speed")
                            .then(ClientCommandManager.argument("tick", IntegerArgumentType.integer(1, 200))
                                    .executes(ctx -> {
                                        int ticks = IntegerArgumentType.getInteger(ctx, "tick");
                                        controller.getConfig().ticksPerBlock = ticks;
                                        controller.getConfig().save();
                                        ctx.getSource().sendFeedback(Text.literal("§a[Schem] Đã lưu tốc độ: §f1 block / " + ticks + " tick."));
                                        return 1;
                                    })))
            );
        });
    }

    private File getSchematicsDir() {
        File dir = new File(MinecraftClient.getInstance().runDirectory, "schematics");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private File resolveSchematicFile(String input) {
        File dir = getSchematicsDir();
        File exact = new File(dir, input);
        if (exact.exists() && exact.isFile()) return exact;

        String[] exts = {".litematic", ".schem", ".nbt"};
        for (String ext : exts) {
            File f = new File(dir, input + ext);
            if (f.exists() && f.isFile()) return f;
        }
        return null;
    }
}
