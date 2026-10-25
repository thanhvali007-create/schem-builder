package com.example.schembuilder;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class SimpleNbt {

    public static TagCompound readCompressedOrRaw(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            bis.mark(4);
            int b1 = bis.read();
            int b2 = bis.read();
            bis.reset();

            InputStream in = (b1 == 0x1F && b2 == 0x8B) ? new GZIPInputStream(bis) : bis;
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(in))) {
                byte type = dis.readByte();
                if (type != 10) {
                    throw new IOException("Root NBT tag must be TAG_Compound (10), got: " + type);
                }
                dis.readUTF(); // Root name
                return readCompound(dis);
            }
        }
    }

    private static Object readPayload(byte type, DataInput in) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int len = in.readInt();
                byte[] arr = new byte[len];
                in.readFully(arr);
                yield arr;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                byte elemType = in.readByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, len));
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(elemType, in));
                }
                yield list;
            }
            case 10 -> readCompound(in);
            case 11 -> {
                int len = in.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                yield arr;
            }
            case 12 -> {
                int len = in.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                yield arr;
            }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        };
    }

    private static TagCompound readCompound(DataInput in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == 0) break;
            String name = in.readUTF();
            map.put(name, readPayload(type, in));
        }
        return new TagCompound(map);
    }

    public static class TagCompound {
        private final Map<String, Object> map;

        public TagCompound(Map<String, Object> map) {
            this.map = map;
        }

        public boolean contains(String key) {
            return map.containsKey(key);
        }

        public Map<String, Object> rawMap() {
            return map;
        }

        public int getInt(String key, int def) {
            Object v = map.get(key);
            return (v instanceof Number n) ? n.intValue() : def;
        }

        public String getString(String key, String def) {
            Object v = map.get(key);
            return (v instanceof String s) ? s : def;
        }

        public TagCompound getCompound(String key) {
            Object v = map.get(key);
            return (v instanceof TagCompound c) ? c : new TagCompound(Collections.emptyMap());
        }

        public List<?> getList(String key) {
            Object v = map.get(key);
            return (v instanceof List<?> l) ? l : Collections.emptyList();
        }

        public byte[] getByteArray(String key) {
            Object v = map.get(key);
            return (v instanceof byte[] b) ? b : new byte[0];
        }

        public long[] getLongArray(String key) {
            Object v = map.get(key);
            return (v instanceof long[] l) ? l : new long[0];
        }
    }
}
