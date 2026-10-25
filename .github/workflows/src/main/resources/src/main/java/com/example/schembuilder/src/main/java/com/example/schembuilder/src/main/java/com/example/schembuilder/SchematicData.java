package com.example.schembuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class SchematicData {

    public record Entry(BlockPos relativePos, BlockState state) {}

    public final String fileName;
    public final List<Entry> entries; // Đã sắp xếp theo Y tăng dần (từ dưới lên)
    public final int sizeX, sizeY, sizeZ;

    public SchematicData(String fileName, Map<BlockPos, BlockState> rawBlocks) {
        this.fileName = fileName;
        if (rawBlocks.isEmpty()) {
            this.entries = Collections.emptyList();
            this.sizeX = this.sizeY = this.sizeZ = 0;
            return;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos p : rawBlocks.keySet()) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }

        this.sizeX = maxX - minX + 1;
        this.sizeY = maxY - minY + 1;
        this.sizeZ = maxZ - minZ + 1;

        List<Entry> list = new ArrayList<>(rawBlocks.size());
        for (Map.Entry<BlockPos, BlockState> e : rawBlocks.entrySet()) {
            BlockState st = e.getValue();
            if (st == null || st.isAir() || st.getBlock() == Blocks.STRUCTURE_VOID) continue;
            BlockPos norm = new BlockPos(
                    e.getKey().getX() - minX,
                    e.getKey().getY() - minY,
                    e.getKey().getZ() - minZ
            );
            list.add(new Entry(norm, st));
        }

        list.sort(Comparator
                .comparingInt((Entry e) -> e.relativePos().getY())
                .thenComparingInt(e -> e.relativePos().getZ())
                .thenComparingInt(e -> e.relativePos().getX()));

        this.entries = Collections.unmodifiableList(list);
    }

    public static SchematicData load(File file) throws IOException {
        SimpleNbt.TagCompound root = SimpleNbt.readCompressedOrRaw(file);
        String lower = file.getName().toLowerCase(Locale.ROOT);

        if (lower.endsWith(".litematic") || root.contains("Regions")) {
            return parseLitematic(file.getName(), root);
        } else if (lower.endsWith(".schem") || root.contains("BlockData") || root.contains("Schematic") || root.contains("Blocks")) {
            return parseSpongeSchem(file.getName(), root);
        } else if (lower.endsWith(".nbt") || (root.contains("blocks") && root.contains("palette"))) {
            return parseVanillaNbt(file.getName(), root);
        }
        throw new IOException("Định dạng schematic không nhận diện được: " + file.getName());
    }

    private static SchematicData parseLitematic(String name, SimpleNbt.TagCompound root) {
        SimpleNbt.TagCompound regions = root.getCompound("Regions");
        Map<BlockPos, BlockState> blocks = new HashMap<>();

        for (Object regObj : regions.rawMap().values()) {
            if (!(regObj instanceof SimpleNbt.TagCompound reg)) continue;

            SimpleNbt.TagCompound posTag = reg.getCompound("Position");
            SimpleNbt.TagCompound sizeTag = reg.getCompound("Size");
            int posX = posTag.getInt("x", 0), posY = posTag.getInt("y", 0), posZ = posTag.getInt("z", 0);
            int sx = sizeTag.getInt("x", 0), sy = sizeTag.getInt("y", 0), sz = sizeTag.getInt("z", 0);

            int absX = Math.abs(sx), absY = Math.abs(sy), absZ = Math.abs(sz);
            int minX = sx < 0 ? posX + sx + 1 : posX;
            int minY = sy < 0 ? posY + sy + 1 : posY;
            int minZ = sz < 0 ? posZ + sz + 1 : posZ;

            List<?> paletteList = reg.getList("BlockStatePalette");
            List<BlockState> palette = new ArrayList<>(paletteList.size());
            for (Object pObj : paletteList) {
                if (pObj instanceof SimpleNbt.TagCompound pComp) {
                    palette.add(parseCompoundBlockState(pComp));
                } else {
                    palette.add(Blocks.AIR.getDefaultState());
                }
            }

            long[] bitArray = reg.getLongArray("BlockStates");
            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            long maxEntryValue = (1L << bits) - 1L;
            long volume = (long) absX * absY * absZ;

            for (long idx = 0; idx < volume; idx++) {
                int paletteIdx = getLitematicaBitValue(bitArray, idx, bits, maxEntryValue);
                if (paletteIdx <= 0 || paletteIdx >= palette.size()) continue;
                BlockState state = palette.get(paletteIdx);
                if (state.isAir()) continue;

                int x = (int) (idx % absX);
                int z = (int) ((idx / absX) % absZ);
                int y = (int) (idx / ((long) absX * absZ));

                blocks.put(new BlockPos(minX + x, minY + y, minZ + z), state);
            }
        }
        return new SchematicData(name, blocks);
    }

    private static int getLitematicaBitValue(long[] data, long index, int bits, long mask) {
        long startOffset = index * (long) bits;
        int startArrIndex = (int) (startOffset >>> 6);
        int endArrIndex = (int) (((index + 1L) * (long) bits - 1L) >>> 6);
        int startBitOffset = (int) (startOffset & 63L);

        if (startArrIndex >= data.length) return 0;
        if (startArrIndex == endArrIndex) {
            return (int) ((data[startArrIndex] >>> startBitOffset) & mask);
        } else {
            if (endArrIndex >= data.length) return 0;
            int endOffset = 64 - startBitOffset;
            return (int) (((data[startArrIndex] >>> startBitOffset) | (data[endArrIndex] << endOffset)) & mask);
        }
    }

    private static SchematicData parseSpongeSchem(String name, SimpleNbt.TagCompound root) {
        SimpleNbt.TagCompound schem = root.contains("Schematic") ? root.getCompound("Schematic") : root;
        int width = schem.getInt("Width", 0);
        int height = schem.getInt("Height", 0);
        int length = schem.getInt("Length", 0);

        SimpleNbt.TagCompound paletteTag;
        byte[] blockData;

        // Hỗ trợ cả Sponge v2 (Palette + BlockData) và Sponge v3 (Blocks.Palette + Blocks.Data)
        if (schem.contains("Blocks") && schem.getCompound("Blocks").contains("Data")) {
            SimpleNbt.TagCompound blocksComp = schem.getCompound("Blocks");
            paletteTag = blocksComp.getCompound("Palette");
            blockData = blocksComp.getByteArray("Data");
        } else {
            paletteTag = schem.getCompound("Palette");
            blockData = schem.getByteArray("BlockData");
        }

        Map<Integer, BlockState> idToState = new HashMap<>();
        for (Map.Entry<String, Object> e : paletteTag.rawMap().entrySet()) {
            if (e.getValue() instanceof Number num) {
                idToState.put(num.intValue(), parseStateString(e.getKey()));
            }
        }

        Map<BlockPos, BlockState> blocks = new HashMap<>();
        int index = 0;
        int i = 0;
        int total = width * height * length;

        while (i < blockData.length && index < total) {
            int value = 0;
            int varintLen = 0;
            while (true) {
                byte b = blockData[i++];
                value |= (b & 0x7F) << (varintLen++ * 7);
                if (varintLen > 5) throw new RuntimeException("VarInt quá dài trong file .schem");
                if ((b & 0x80) == 0) break;
            }

            BlockState state = idToState.getOrDefault(value, Blocks.AIR.getDefaultState());
            if (!state.isAir()) {
                int y = index / (width * length);
                int rem = index % (width * length);
                int z = rem / width;
                int x = rem % width;
                blocks.put(new BlockPos(x, y, z), state);
            }
            index++;
        }
        return new SchematicData(name, blocks);
    }

    private static SchematicData parseVanillaNbt(String name, SimpleNbt.TagCompound root) {
        List<?> paletteList = root.getList("palette");
        List<BlockState> palette = new ArrayList<>(paletteList.size());
        for (Object pObj : paletteList) {
            if (pObj instanceof SimpleNbt.TagCompound pComp) {
                palette.add(parseCompoundBlockState(pComp));
            } else {
                palette.add(Blocks.AIR.getDefaultState());
            }
        }

        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (Object bObj : root.getList("blocks")) {
            if (!(bObj instanceof SimpleNbt.TagCompound bComp)) continue;
            int stateIdx = bComp.getInt("state", 0);
            if (stateIdx < 0 || stateIdx >= palette.size()) continue;
            BlockState st = palette.get(stateIdx);
            if (st.isAir()) continue;

            List<?> posList = bComp.getList("pos");
            if (posList.size() >= 3) {
                int x = ((Number) posList.get(0)).intValue();
                int y = ((Number) posList.get(1)).intValue();
                int z = ((Number) posList.get(2)).intValue();
                blocks.put(new BlockPos(x, y, z), st);
            }
        }
        return new SchematicData(name, blocks);
    }

    private static BlockState parseCompoundBlockState(SimpleNbt.TagCompound comp) {
        String name = comp.getString("Name", "minecraft:air");
        Map<String, String> props = new LinkedHashMap<>();
        if (comp.contains("Properties")) {
            SimpleNbt.TagCompound pComp = comp.getCompound("Properties");
            for (Map.Entry<String, Object> e : pComp.rawMap().entrySet()) {
                props.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return createBlockState(name, props);
    }

    public static BlockState parseStateString(String spec) {
        String name = spec;
        Map<String, String> props = new LinkedHashMap<>();
        int bracket = spec.indexOf('[');
        if (bracket != -1 && spec.endsWith("]")) {
            name = spec.substring(0, bracket);
            String propStr = spec.substring(bracket + 1, spec.length() - 1);
            if (!propStr.isEmpty()) {
                for (String pair : propStr.split(",")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        props.put(kv[0].trim(), kv[1].trim());
                    }
                }
            }
        }
        return createBlockState(name, props);
    }

    private static BlockState createBlockState(String blockIdStr, Map<String, String> props) {
        Identifier id = Identifier.tryParse(blockIdStr);
        if (id == null) return Blocks.AIR.getDefaultState();
        Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();

        for (Property<?> prop : block.getStateManager().getProperties()) {
            String valStr = props.get(prop.getName());
            if (valStr != null) {
                state = applyProperty(state, prop, valStr);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> prop, String valStr) {
        Optional<T> parsed = prop.parse(valStr);
        return parsed.map(t -> state.with(prop, t)).orElse(state);
    }
}
