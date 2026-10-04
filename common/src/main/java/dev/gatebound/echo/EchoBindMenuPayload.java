package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Server to client: open the bind menu for a soul body (name, free echo slots, each group's order and size). */
public record EchoBindMenuPayload(int entityId, String name, int free, List<Integer> modes,
                                  List<Integer> counts) implements CustomPacketPayload {
    public static final Type<EchoBindMenuPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_bind_menu"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EchoBindMenuPayload> CODEC = new StreamCodec<>() {
        @Override
        public EchoBindMenuPayload decode(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            String name = buf.readUtf();
            int free = buf.readVarInt();
            int n = buf.readVarInt();
            List<Integer> modes = new ArrayList<>(n);
            List<Integer> counts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                modes.add(buf.readVarInt());
                counts.add(buf.readVarInt());
            }
            return new EchoBindMenuPayload(id, name, free, modes, counts);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, EchoBindMenuPayload p) {
            buf.writeVarInt(p.entityId);
            buf.writeUtf(p.name);
            buf.writeVarInt(p.free);
            buf.writeVarInt(p.modes.size());
            for (int i = 0; i < p.modes.size(); i++) {
                buf.writeVarInt(p.modes.get(i));
                buf.writeVarInt(p.counts.get(i));
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
