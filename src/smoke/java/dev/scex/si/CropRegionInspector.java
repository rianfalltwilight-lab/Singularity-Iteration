// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.zip.InflaterInputStream;
import java.util.zip.GZIPInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

/** Read-only bounded diagnostic for one block entity in a frozen region file. */
public final class CropRegionInspector {
    private static final long NBT_LIMIT = 64L * 1024 * 1024;

    private CropRegionInspector() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("world x y z");
        Path world = Path.of(args[0]);
        BlockPos target = new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        ChunkPos chunkPos = new ChunkPos(target);
        Path region = world.resolve("region/r." + (chunkPos.x >> 5) + "." + (chunkPos.z >> 5) + ".mca");
        CompoundTag chunk;
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
            file.seek(4L * ((chunkPos.x & 31) + (chunkPos.z & 31) * 32));
            int location = file.readInt(), sector = location >>> 8, sectors = location & 255;
            if (sector < 2 || sectors == 0) throw new IllegalStateException("Missing chunk entry");
            file.seek(sector * 4096L);
            int length = file.readInt(), compression = file.readUnsignedByte();
            if (length < 1 || length > sectors * 4096 - 4 || sector * 4096L + 4 + length > file.length()) {
                throw new IllegalStateException("Invalid bounded region entry");
            }
            byte[] bytes = new byte[length - 1];
            file.readFully(bytes);
            InputStream raw = new ByteArrayInputStream(bytes);
            InputStream decoded = switch (compression) {
                case 1 -> new GZIPInputStream(raw);
                case 2 -> new InflaterInputStream(raw);
                case 3 -> raw;
                default -> throw new IllegalStateException("Unsupported compression " + compression);
            };
            try (var data = new DataInputStream(decoded)) {
                chunk = NbtIo.read(data, NbtAccounter.create(NBT_LIMIT));
            }
        }
        CompoundTag found = null;
        for (Tag value : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
            CompoundTag entity = (CompoundTag)value;
            if (entity.getInt("x") == target.getX() && entity.getInt("y") == target.getY()
                    && entity.getInt("z") == target.getZ()) {
                if (found != null) throw new IllegalStateException("Duplicate block entity");
                found = entity;
            }
        }
        if (found == null) throw new IllegalStateException("Block entity absent");
        System.out.println("SCEX_CROP_REGION_NBT FertilizerCooldown=" + found.getInt("FertilizerCooldown")
            + " GrowthStage=" + found.getInt("GrowthStage") + " Progress=" + found.getInt("Progress")
            + " CropTicker=" + found.getInt("CropTicker") + " PlantId=" + found.getString("PlantId"));
    }
}
