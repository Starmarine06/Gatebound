package dev.gatebound.echo;

import dev.gatebound.Gatebound;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the owner's echoes with their groups, each group's order, and the gates echoes have found.
 * open = true also opens the Echoes screen.
 */
public record EchoRosterPayload(boolean open, boolean summoner, List<Row> rows, List<Integer> groupModes,
                                List<GateRow> gates, String viewing) implements CustomPacketPayload {
    /** selected: this is the echo picked with the select key; personal: its own order (-1 = follows its group). */
    public record Row(String name, String type, int group, boolean active, boolean unlocked, boolean selected, int personal) {
    }

    /** distance is in blocks, or -1 when the gate is in another dimension. */
    public record GateRow(String label, String by, int distance, boolean selected) {
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
                rows.add(new Row(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                        buf.readBoolean(), buf.readVarInt() - 1));
            }
            int m = buf.readVarInt();
            List<Integer> modes = new ArrayList<>(m);
            for (int i = 0; i < m; i++) {
                modes.add(buf.readVarInt());
            }
            int g = buf.readVarInt();
            List<GateRow> gates = new ArrayList<>(g);
            for (int i = 0; i < g; i++) {
                gates.add(new GateRow(buf.readUtf(), buf.readUtf(), buf.readVarInt() - 1, buf.readBoolean()));
            }
            String viewing = buf.readUtf();
            return new EchoRosterPayload(open, summoner, rows, modes, gates, viewing);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, EchoRosterPayload p) {
            buf.writeBoolean(p.open);
            buf.writeBoolean(p.summoner);
            buf.writeVarInt(p.rows.size());
            for (Row r : p.rows) {
                buf.writeUtf(r.name);
                buf.writeUtf(r.type);
                buf.writeVarInt(r.group);
                buf.writeBoolean(r.active);
                buf.writeBoolean(r.unlocked);
                buf.writeBoolean(r.selected);
                buf.writeVarInt(r.personal + 1);
            }
            buf.writeVarInt(p.groupModes.size());
            for (int m : p.groupModes) {
                buf.writeVarInt(m);
            }
            buf.writeVarInt(p.gates.size());
            for (GateRow g : p.gates) {
                buf.writeUtf(g.label);
                buf.writeUtf(g.by);
                buf.writeVarInt(g.distance + 1);
                buf.writeBoolean(g.selected);
            }
            buf.writeUtf(p.viewing);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
