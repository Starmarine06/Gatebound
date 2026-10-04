package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Server to client: the owner's echo roster with each echo's order, and whether to open the Echoes screen. */
public record EchoRosterPayload(boolean open, boolean summoner, List<Row> rows) implements CustomPacketPayload {
    public record Row(String name, String type, int mode, boolean active, boolean unlocked) {
    }

    public static final Type<EchoRosterPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_roster"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EchoRosterPayload> CODEC = new StreamCodec<>() {
        @Override
        public EchoRosterPayload decode(RegistryFriendlyByteBuf buf) {
            boolean open = buf.readBoolean();
            boolean summoner = buf.readBoolean();
            int n = buf.readVarInt();
            List<Row> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rows.add(new Row(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));
            }
            return new EchoRosterPayload(open, summoner, rows);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, EchoRosterPayload p) {
            buf.writeBoolean(p.open);
            buf.writeBoolean(p.summoner);
            buf.writeVarInt(p.rows.size());
            for (Row r : p.rows) {
                buf.writeUtf(r.name);
                buf.writeUtf(r.type);
                buf.writeVarInt(r.mode);
                buf.writeBoolean(r.active);
                buf.writeBoolean(r.unlocked);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
