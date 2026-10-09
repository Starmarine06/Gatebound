package dev.gatebound.grave;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? if >=26.1 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
*///?}

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** What a dead player left behind: their items, their experience, who they were and when they fell. */
public final class GraveBlockEntity extends BlockEntity {
    public final List<ItemStack> items = new ArrayList<>();
    public int xp;
    public UUID owner;
    public String ownerName = "";
    public long created;

    public GraveBlockEntity(BlockPos pos, BlockState state) {
        super(GraveRegistries.GRAVE_ENTITY.get(), pos, state);
    }

    //? if >=26.1 {
    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.store("items", ItemStack.CODEC.listOf(), items.stream().filter(s -> !s.isEmpty()).toList());
        out.putInt("xp", xp);
        if (owner != null) {
            out.store("owner", UUIDUtil.CODEC, owner);
        }
        out.putString("owner_name", ownerName);
        out.putLong("created", created);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        items.clear();
        in.read("items", ItemStack.CODEC.listOf()).ifPresent(items::addAll);
        xp = in.getIntOr("xp", 0);
        owner = in.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = in.getStringOr("owner_name", "");
        created = in.getLongOr("created", 0L);
    }
    //?} else {
    /*@Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        tag.put("items", ItemStack.CODEC.listOf().encodeStart(ops, items.stream().filter(s -> !s.isEmpty()).toList()).getOrThrow());
        tag.putInt("xp", xp);
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
        tag.putString("owner_name", ownerName);
        tag.putLong("created", created);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        if (tag.contains("items")) {
            ItemStack.CODEC.listOf().parse(ops, tag.get("items")).result().ifPresent(items::addAll);
        }
        xp = tag.getInt("xp");
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = tag.getString("owner_name");
        created = tag.getLong("created");
    }
    *///?}
}
