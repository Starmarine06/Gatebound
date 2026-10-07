package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: open the C wheel for the soul body or echo the player is looking at. body = true lists the four
 * groups to bind into; otherwise it lists orders for one echo. current is the highlighted entry, or -1.
 */
public record EchoWheelPayload(int entityId, boolean body, String title, List<String> labels, int current)
        implements CustomPacketPayload {
    public static final Type<EchoWheelPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Gatebound.MOD_ID, "echo_wheel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EchoWheelPayload> CODEC = new StreamCodec<>() {
        @Override
        public EchoWheelPayload decode(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            boolean body = buf.readBoolean();
            String title = buf.readUtf();
            int n = buf.readVarInt();
            List<String> labels = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                labels.add(buf.readUtf());
            }
            return new EchoWheelPayload(id, body, title, labels, buf.readVarInt() - 1);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, EchoWheelPayload p) {
            buf.writeVarInt(p.entityId);
            buf.writeBoolean(p.body);
            buf.writeUtf(p.title);
            buf.writeVarInt(p.labels.size());
            for (String l : p.labels) {
                buf.writeUtf(l);
            }
            buf.writeVarInt(p.current + 1);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
