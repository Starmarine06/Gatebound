package dev.gatebound.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic description of one floor, generated from (world seed, floor index) only.
 * Contains no world access, so it can be built on demand, cached and re-derived to clear or rebuild a floor.
 * The layout is a block-resolution grid: h[idx] is the interior height of an open cell (0 = solid),
 * wh[idx] the wall column height of a solid cell next to open space. Everything else is decoration data.
 */
public final class FloorPlan {
    public record Chest(BlockPos pos, Direction facing) {
    }

    private record Room(int x, int z, int w, int d, int h, boolean arena) {
        int cx() {
            return x + w / 2;
        }

        int cz() {
            return z + d / 2;
        }
    }

    private static final int MAX_SPOTS = 110;

    public final int floor;
    public final int total;
    public final int size;
    public final int ox;
    public final Spire.Kind kind;
    public final FloorTheme theme;
    public final int band;
    public final long seed;
    public final byte[] h;
    public final byte[] wh;
    /** Extra blocks keyed by (y, j, i); later puts override earlier ones. */
    public final Map<Long, BlockState> decos = new LinkedHashMap<>();

    public BlockPos downPortal;
    public BlockPos downArrive;
    public BlockPos upPortal;
    public BlockPos upArrive;
    public BlockPos boss;
    public BlockPos center;
    public final List<BlockPos> riftPortals = new ArrayList<>();
    public final List<BlockPos> riftArrive = new ArrayList<>();
    public final List<BlockPos> mobSpots = new ArrayList<>();
    public final List<Chest> chests = new ArrayList<>();
    public int arenaCx;
    public int arenaCz;
    public int arenaR;
    public boolean reachable;

    private final RandomSource rng;
    private final boolean forceRooms;
    private final List<Room> rooms = new ArrayList<>();
    private final List<int[]> keep = new ArrayList<>();
    private final boolean[] blocked;
    private final List<List<Integer>> poolSets = new ArrayList<>();
    private int startCx;
    private int startCz;

    private FloorPlan(long worldSeed, int floor, int total, int attempt, boolean forceRooms) {
        this.floor = floor;
        this.total = total;
        this.size = Spire.size();
        this.ox = Spire.originX(floor);
        this.kind = Spire.kind(floor, total);
        this.band = Spire.band(floor, total);
        this.theme = Spire.theme(floor, total);
        this.seed = Spire.mix(worldSeed, floor, 0x51A1EL);
        this.rng = RandomSource.create(Spire.mix(worldSeed, floor, attempt + 1L));
        this.forceRooms = forceRooms;
        this.h = new byte[size * size];
        this.wh = new byte[size * size];
        this.blocked = new boolean[size * size];
    }

    public static FloorPlan generate(long worldSeed, int floor, int total) {
        for (int attempt = 0; attempt < 8; attempt++) {
            FloorPlan p = new FloorPlan(worldSeed, floor, total, attempt, attempt >= 4);
            if (p.build()) {
                return p;
            }
        }
        // Practically unreachable; keep the last attempt even if a check failed so the floor still exists.
        FloorPlan p = new FloorPlan(worldSeed, floor, total, 99, true);
        p.build();
        return p;
    }

    // ------------------------------------------------------------------ build

    private boolean build() {
        boolean ok = switch (kind) {
            case REST -> layoutRest();
            case BOSS, FINAL -> layoutRooms(true);
            default -> theme.cave() && !forceRooms ? layoutCave() : layoutRooms(false);
        };
        if (!ok) {
            return false;
        }
        planPools();
        pickRifts();
        pickMobSpots();
        pickChests();
        computeWalls();
        decorate();
        int[] dist = bfs(startCx, startCz);
        reachable = dist[cell(upArrive)] >= 0;
        for (BlockPos r : riftArrive) {
            reachable &= dist[cell(r)] >= 0;
        }
        return reachable;
    }

    private int cell(BlockPos p) {
        return (p.getZ()) * size + (p.getX() - ox);
    }

    private int idx(int i, int j) {
        return j * size + i;
    }

    private boolean open(int i, int j) {
        return i >= 0 && j >= 0 && i < size && j < size && h[j * size + i] > 0;
    }

    private int rnd(int a, int b) {
        return a + rng.nextInt(b - a + 1);
    }

    private int hash(int i, int j, int salt) {
        return (int) (Spire.mix(seed ^ (salt * 0x9E37L), i, j) >>> 33);
    }

    private void carveRect(int x1, int z1, int x2, int z2, int hh) {
        for (int j = Math.max(2, z1); j <= Math.min(size - 3, z2); j++) {
            for (int i = Math.max(2, x1); i <= Math.min(size - 3, x2); i++) {
                int k = idx(i, j);
                if (h[k] < hh) {
                    h[k] = (byte) hh;
                }
            }
        }
    }

    private void carveCircle(int cx, int cz, int r, int hh) {
        for (int j = cz - r; j <= cz + r; j++) {
            for (int i = cx - r; i <= cx + r; i++) {
                if ((i - cx) * (i - cx) + (j - cz) * (j - cz) <= r * r && i >= 2 && j >= 2 && i < size - 2 && j < size - 2) {
                    int k = idx(i, j);
                    if (h[k] < hh) {
                        h[k] = (byte) hh;
                    }
                }
            }
        }
    }

    private void corridor(Room a, Room b, int cw) {
        int hh = cw >= 5 ? 6 : 5;
        int half = cw / 2;
        int ax = a.cx();
        int az = a.cz();
        int bx = b.cx();
        int bz = b.cz();
        if (rng.nextBoolean()) {
            carveRect(Math.min(ax, bx) - half, az - half, Math.max(ax, bx) + half, az + half, hh);
            carveRect(bx - half, Math.min(az, bz) - half, bx + half, Math.max(az, bz) + half, hh);
        } else {
            carveRect(ax - half, Math.min(az, bz) - half, ax + half, Math.max(az, bz) + half, hh);
            carveRect(Math.min(ax, bx) - half, bz - half, Math.max(ax, bx) + half, bz + half, hh);
        }
    }

    private int[] bfs(int sx, int sz) {
        int[] dist = new int[size * size];
        java.util.Arrays.fill(dist, -1);
        if (!open(sx, sz)) {
            return dist;
        }
        ArrayDeque<Integer> q = new ArrayDeque<>();
        dist[idx(sx, sz)] = 0;
        q.add(idx(sx, sz));
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        while (!q.isEmpty()) {
            int k = q.poll();
            int i = k % size;
            int j = k / size;
            for (int d = 0; d < 4; d++) {
                int ni = i + dx[d];
                int nj = j + dz[d];
                if (open(ni, nj) && dist[idx(ni, nj)] < 0) {
                    dist[idx(ni, nj)] = dist[k] + 1;
                    q.add(idx(ni, nj));
                }
            }
        }
        return dist;
    }

    private void addKeep(int x, int z, int r) {
        keep.add(new int[]{x, z, r});
    }

    private boolean nearKeep(int i, int j, int extra) {
        for (int[] k : keep) {
            int dx = i - k[0];
            int dz = j - k[1];
            int r = k[2] + extra;
            if (dx * dx + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    private boolean inArena(int i, int j, int extra) {
        if (arenaR <= 0) {
            return false;
        }
        int dx = i - arenaCx;
        int dz = j - arenaCz;
        int r = arenaR + extra;
        return dx * dx + dz * dz <= r * r;
    }

    // ------------------------------------------------------------------ layouts

    private boolean layoutRooms(boolean arena) {
        int cw = theme.corridorWidth();
        int count = theme.roomCount();
        if (arena) {
            arenaR = kind == Spire.Kind.FINAL ? 22 : 18;
            arenaCx = size - arenaR - 10 - rng.nextInt(6);
            arenaCz = size / 2 + rng.nextInt(21) - 10;
            int hh = kind == Spire.Kind.FINAL ? 17 : 14;
            carveCircle(arenaCx, arenaCz, arenaR, hh);
            rooms.add(new Room(arenaCx - arenaR, arenaCz - arenaR, arenaR * 2, arenaR * 2, hh, true));
            count = Math.min(count, 14);
        }
        for (int t = 0; t < 3000 && rooms.size() < count + (arena ? 1 : 0); t++) {
            int w = rnd(theme.roomMin(), theme.roomMax());
            int d = rnd(theme.roomMin(), theme.roomMax());
            int x = 5 + rng.nextInt(Math.max(1, size - w - 10));
            int z = 5 + rng.nextInt(Math.max(1, size - d - 10));
            if (arena) {
                int nx = Math.max(x, Math.min(arenaCx, x + w));
                int nz = Math.max(z, Math.min(arenaCz, z + d));
                int dx = nx - arenaCx;
                int dz = nz - arenaCz;
                if (dx * dx + dz * dz < (arenaR + 6) * (arenaR + 6)) {
                    continue;
                }
            }
            boolean overlap = false;
            for (Room r : rooms) {
                if (!r.arena() && x < r.x() + r.w() + 4 && x + w + 4 > r.x() && z < r.z() + r.d() + 4 && z + d + 4 > r.z()) {
                    overlap = true;
                    break;
                }
            }
            if (overlap) {
                continue;
            }
            int hh = rnd(theme.heightMin(), theme.heightMax());
            carveRect(x, z, x + w - 1, z + d - 1, hh);
            rooms.add(new Room(x, z, w, d, hh, false));
        }
        if (rooms.size() < 5) {
            return false;
        }
        for (int i = 1; i < rooms.size(); i++) {
            int best = 0;
            long bestD = Long.MAX_VALUE;
            for (int j = 0; j < i; j++) {
                long dx = rooms.get(i).cx() - rooms.get(j).cx();
                long dz = rooms.get(i).cz() - rooms.get(j).cz();
                long dd = dx * dx + dz * dz;
                if (dd < bestD) {
                    bestD = dd;
                    best = j;
                }
            }
            corridor(rooms.get(i), rooms.get(best), cw);
        }
        for (int k = 0; k < rooms.size() / 4; k++) {
            int a = rng.nextInt(rooms.size());
            int b = rng.nextInt(rooms.size());
            if (a != b) {
                corridor(rooms.get(a), rooms.get(b), cw);
            }
        }

        // Start room: the westernmost regular room.
        Room start = null;
        for (Room r : rooms) {
            if (!r.arena() && (start == null || r.cx() < start.cx())) {
                start = r;
            }
        }
        if (start == null) {
            return false;
        }
        placeStart(start.cx(), start.cz());
        if (arena) {
            int px = arenaCx + arenaR - 6;
            placeExit(px, arenaCz, px - 2, arenaCz);
            boss = Spire.abs(floor, arenaCx - 4, Spire.FY + 1, arenaCz);
        } else {
            int[] dist = bfs(startCx, startCz);
            Room far = null;
            int bestDist = -1;
            for (Room r : rooms) {
                if (r == start) {
                    continue;
                }
                int d = dist[idx(r.cx(), r.cz())];
                if (d > bestDist) {
                    bestDist = d;
                    far = r;
                }
            }
            if (far == null || bestDist < 0) {
                return false;
            }
            placeExit(far.cx(), far.cz(), far.cx() - 2, far.cz());
        }
        return true;
    }

    private void placeStart(int cx, int cz) {
        carveCircle(cx, cz, 3, 6);
        startCx = cx + 2;
        startCz = cz;
        downPortal = Spire.abs(floor, cx, Spire.FY + 1, cz);
        downArrive = Spire.abs(floor, cx + 2, Spire.FY + 1, cz);
        addKeep(cx, cz, 4);
    }

    private void placeExit(int px, int pz, int ax, int az) {
        carveCircle(px, pz, 3, 6);
        carveCircle(ax, az, 1, 6);
        upPortal = Spire.abs(floor, px, Spire.FY + 1, pz);
        upArrive = Spire.abs(floor, ax, Spire.FY + 1, az);
        addKeep(px, pz, 4);
    }

    private boolean layoutCave() {
        int n = size * size;
        boolean[] wall = new boolean[n];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                boolean border = i < 5 || j < 5 || i >= size - 5 || j >= size - 5;
                wall[idx(i, j)] = border || rng.nextFloat() < 0.46f;
            }
        }
        for (int iter = 0; iter < 5; iter++) {
            boolean[] next = new boolean[n];
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    int walls = 0;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            int ni = i + di;
                            int nj = j + dj;
                            if (ni < 0 || nj < 0 || ni >= size || nj >= size || wall[idx(ni, nj)]) {
                                walls++;
                            }
                        }
                    }
                    boolean border = i < 5 || j < 5 || i >= size - 5 || j >= size - 5;
                    next[idx(i, j)] = border || walls >= 5;
                }
            }
            wall = next;
        }
        // Largest connected region only.
        int[] label = new int[n];
        int best = 0;
        int bestSize = 0;
        int labels = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < n; s++) {
            if (wall[s] || label[s] != 0) {
                continue;
            }
            labels++;
            int count = 0;
            label[s] = labels;
            q.add(s);
            while (!q.isEmpty()) {
                int k = q.poll();
                count++;
                int i = k % size;
                int j = k / size;
                int[][] nb = {{i + 1, j}, {i - 1, j}, {i, j + 1}, {i, j - 1}};
                for (int[] c : nb) {
                    if (c[0] >= 0 && c[1] >= 0 && c[0] < size && c[1] < size) {
                        int kk = idx(c[0], c[1]);
                        if (!wall[kk] && label[kk] == 0) {
                            label[kk] = labels;
                            q.add(kk);
                        }
                    }
                }
            }
            if (count > bestSize) {
                bestSize = count;
                best = labels;
            }
        }
        if (bestSize < n * 0.2) {
            return false;
        }
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                if (label[idx(i, j)] == best) {
                    double nz = noise(i / 10.0, j / 10.0);
                    h[idx(i, j)] = (byte) (theme.heightMin() + (int) Math.round(nz * (theme.heightMax() - theme.heightMin() + 1)));
                    if (h[idx(i, j)] < 5) {
                        h[idx(i, j)] = 5;
                    }
                }
            }
        }
        // Start: an open cell in the western third.
        int sx = -1;
        int sz = -1;
        for (int t = 0; t < 4000 && sx < 0; t++) {
            int i = 6 + rng.nextInt(size / 3);
            int j = 6 + rng.nextInt(size - 12);
            if (open(i, j)) {
                sx = i;
                sz = j;
            }
        }
        if (sx < 0) {
            return false;
        }
        placeStart(sx, sz);
        int[] dist = bfs(startCx, startCz);
        int far = -1;
        int farD = -1;
        for (int k = 0; k < n; k++) {
            if (dist[k] > farD) {
                farD = dist[k];
                far = k;
            }
        }
        if (far < 0 || farD < size / 2) {
            return false;
        }
        placeExit(far % size, far / size, far % size - 2, far / size);
        // A few round chambers for variety.
        for (int c = 0; c < 6; c++) {
            for (int t = 0; t < 50; t++) {
                int i = 12 + rng.nextInt(size - 24);
                int j = 12 + rng.nextInt(size - 24);
                if (open(i, j)) {
                    carveCircle(i, j, rnd(4, 8), 9);
                    break;
                }
            }
        }
        return true;
    }

    private double noise(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = (hash(x0, z0, 77) & 1023) / 1023.0;
        double b = (hash(x0 + 1, z0, 77) & 1023) / 1023.0;
        double c = (hash(x0, z0 + 1, 77) & 1023) / 1023.0;
        double d = (hash(x0 + 1, z0 + 1, 77) & 1023) / 1023.0;
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
    }

    private boolean layoutRest() {
        int cx = size / 2;
        int cz = size / 2;
        double a = 28;
        double b = 22;
        center = Spire.abs(floor, cx, Spire.FY + 1, cz);
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double dx = (i - cx) / a;
                double dz = (j - cz) / b;
                double rr = dx * dx + dz * dz;
                if (rr <= 1.0) {
                    h[idx(i, j)] = (byte) (9 + Math.round(3 * (1 - rr)));
                }
            }
        }
        placeStart(cx - 22, cz);
        placeExit(cx + 22, cz, cx + 20, cz);
        addKeep(cx, cz, 5);
        return true;
    }

    // ------------------------------------------------------------------ features

    private void planPools() {
        if (theme.fluid() == null) {
            return;
        }
        if (kind == Spire.Kind.REST) {
            int px = size / 2 - 12;
            int pz = size / 2 - 9;
            List<Integer> cells = new ArrayList<>();
            for (int j = pz - 4; j <= pz + 4; j++) {
                for (int i = px - 4; i <= px + 4; i++) {
                    if ((i - px) * (i - px) + (j - pz) * (j - pz) <= 16) {
                        cells.add(idx(i, j));
                    }
                }
            }
            poolSets.add(cells);
        } else if (theme.cave()) {
            for (int c = 0; c < 6; c++) {
                for (int t = 0; t < 60; t++) {
                    int i = 10 + rng.nextInt(size - 20);
                    int j = 10 + rng.nextInt(size - 20);
                    if (!allOpen(i, j, 4) || nearKeep(i, j, 6)) {
                        continue;
                    }
                    List<Integer> cells = new ArrayList<>();
                    for (int dj = -2; dj <= 2; dj++) {
                        for (int di = -2; di <= 2; di++) {
                            if (di * di + dj * dj <= 5) {
                                cells.add(idx(i + di, j + dj));
                            }
                        }
                    }
                    poolSets.add(cells);
                    markBlocked(cells);
                    break;
                }
            }
            return;
        } else {
            for (Room r : rooms) {
                if (r.arena() || r.w() < 11 || r.d() < 11 || rng.nextFloat() > 0.65f || nearKeep(r.cx(), r.cz(), 6)) {
                    continue;
                }
                List<Integer> cells = new ArrayList<>();
                boolean alongX = r.w() >= r.d();
                for (int j = r.z() + 3; j < r.z() + r.d() - 3; j++) {
                    for (int i = r.x() + 3; i < r.x() + r.w() - 3; i++) {
                        boolean bridge = alongX ? Math.abs(j - r.cz()) <= 1 : Math.abs(i - r.cx()) <= 1;
                        if (!bridge && open(i, j) && !nearKeep(i, j, 2)) {
                            cells.add(idx(i, j));
                        }
                    }
                }
                if (cells.size() > 12) {
                    poolSets.add(cells);
                    markBlocked(cells);
                }
            }
            return;
        }
        for (List<Integer> cells : poolSets) {
            markBlocked(cells);
        }
    }

    private boolean allOpen(int i, int j, int r) {
        for (int dj = -r; dj <= r; dj++) {
            for (int di = -r; di <= r; di++) {
                if (!open(i + di, j + dj)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void markBlocked(List<Integer> cells) {
        for (int k : cells) {
            int i = k % size;
            int j = k / size;
            for (int dj = -2; dj <= 2; dj++) {
                for (int di = -2; di <= 2; di++) {
                    if (i + di >= 0 && j + dj >= 0 && i + di < size && j + dj < size) {
                        blocked[idx(i + di, j + dj)] = true;
                    }
                }
            }
        }
    }

    private void pickRifts() {
        int want = Math.max(1, DungeonConfig.riftsPerFloor);
        List<int[]> cands = openCells();
        Collections.shuffle(cands, new java.util.Random(rng.nextLong()));
        List<int[]> picked = new ArrayList<>();
        for (int[] c : cands) {
            if (picked.size() >= want) {
                break;
            }
            int i = c[0];
            int j = c[1];
            if (blocked[idx(i, j)] || nearKeep(i, j, 14) || inArena(i, j, 5) || !allOpen(i, j, 0)
                    || i < 8 || j < 8 || i >= size - 8 || j >= size - 8) {
                continue;
            }
            boolean ok = true;
            for (int[] p : picked) {
                int dx = p[0] - i;
                int dz = p[1] - j;
                if (dx * dx + dz * dz < 18 * 18) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                picked.add(c);
            }
        }
        // Fewer well-spaced spots than wanted is fine on small caves; fall back to closer spacing once.
        if (picked.isEmpty()) {
            for (int[] c : cands) {
                if (!blocked[idx(c[0], c[1])] && !inArena(c[0], c[1], 5) && c[0] > 8 && c[1] > 8
                        && c[0] < size - 8 && c[1] < size - 8 && !nearKeep(c[0], c[1], 6)) {
                    picked.add(c);
                    break;
                }
            }
        }
        for (int[] c : picked) {
            carveCircle(c[0], c[1], 2, 5);
            addKeep(c[0], c[1], 3);
            riftPortals.add(Spire.abs(floor, c[0], Spire.FY + 1, c[1]));
            riftArrive.add(Spire.abs(floor, c[0] + 2, Spire.FY + 1, c[1]));
        }
    }

    private List<int[]> openCells() {
        List<int[]> out = new ArrayList<>();
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                if (h[idx(i, j)] > 0) {
                    out.add(new int[]{i, j});
                }
            }
        }
        return out;
    }

    private void pickMobSpots() {
        if (kind == Spire.Kind.REST) {
            return;
        }
        List<int[]> cands = openCells();
        Collections.shuffle(cands, new java.util.Random(rng.nextLong()));
        List<int[]> picked = new ArrayList<>();
        for (int[] c : cands) {
            if (picked.size() >= MAX_SPOTS) {
                break;
            }
            int i = c[0];
            int j = c[1];
            int sdx = i - startCx;
            int sdz = j - startCz;
            if (blocked[idx(i, j)] || inArena(i, j, -2) || h[idx(i, j)] < 4 || nearKeep(i, j, 8)
                    || sdx * sdx + sdz * sdz < 18 * 18) {
                continue;
            }
            boolean ok = true;
            for (int[] p : picked) {
                int dx = p[0] - i;
                int dz = p[1] - j;
                if (dx * dx + dz * dz < 16) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                picked.add(c);
            }
        }
        for (int[] c : picked) {
            mobSpots.add(Spire.abs(floor, c[0], Spire.FY + 1, c[1]));
        }
    }

    private void pickChests() {
        int want = kind == Spire.Kind.REST ? 0 : DungeonConfig.chestsPerFloor + band / 2;
        if (want <= 0) {
            return;
        }
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        Direction[] dirs = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
        List<int[]> cands = new ArrayList<>();
        for (int j = 3; j < size - 3; j++) {
            for (int i = 3; i < size - 3; i++) {
                if (h[idx(i, j)] < 4 || blocked[idx(i, j)] || nearKeep(i, j, 5) || inArena(i, j, 1)) {
                    continue;
                }
                for (int d = 0; d < 4; d++) {
                    if (!open(i + dx[d], j + dz[d]) && open(i - dx[d], j - dz[d])) {
                        // wall on side d, so the chest faces the opposite way
                        cands.add(new int[]{i, j, d ^ 1});
                        break;
                    }
                }
            }
        }
        Collections.shuffle(cands, new java.util.Random(rng.nextLong()));
        List<int[]> picked = new ArrayList<>();
        for (int[] c : cands) {
            if (picked.size() >= want) {
                break;
            }
            boolean ok = true;
            for (int[] p : picked) {
                int ddx = p[0] - c[0];
                int ddz = p[1] - c[1];
                if (ddx * ddx + ddz * ddz < 100) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                picked.add(c);
            }
        }
        for (int[] c : picked) {
            chests.add(new Chest(Spire.abs(floor, c[0], Spire.FY + 1, c[1]), dirs[c[2]]));
        }
    }

    private void computeWalls() {
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                if (h[idx(i, j)] > 0) {
                    continue;
                }
                int m = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int ni = i + di;
                        int nj = j + dj;
                        if (ni >= 0 && nj >= 0 && ni < size && nj < size) {
                            m = Math.max(m, h[idx(ni, nj)]);
                        }
                    }
                }
                wh[idx(i, j)] = (byte) m;
            }
        }
    }

    // ------------------------------------------------------------------ block choice

    public BlockState wallState(int x, int y, int z) {
        int r = (int) ((Spire.mix(seed, x * 31L + y, z) >>> 40) % 100);
        BlockState[] w = theme.wall();
        return r < 70 ? w[0] : r < 90 ? w[1] : w[2];
    }

    public BlockState floorState(int i, int j) {
        int r = hash(i, j, 3) % 100;
        BlockState[] f = theme.floor();
        return r < 55 ? f[0] : r < 85 ? f[1] : f[2];
    }

    public BlockState ceilingState(int i, int j) {
        int r = hash(i, j, 4) % 100;
        BlockState[] c = theme.ceiling();
        return r < 60 ? c[0] : r < 85 ? c[1] : c[2];
    }

    // ------------------------------------------------------------------ decoration

    private void deco(int i, int y, int j, BlockState s) {
        decos.put(((long) y << 32) | ((long) j << 16) | i, s);
    }

    private void decorate() {
        // Pools and their sealing ring (ring first so pool cells win).
        BlockState base = theme.floor()[0];
        if (theme.fluid() != null) {
            BlockState fluid = theme.fluid().defaultBlockState();
            for (List<Integer> cells : poolSets) {
                java.util.Set<Integer> set = new java.util.HashSet<>(cells);
                for (int k : cells) {
                    int i = k % size;
                    int j = k / size;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            int ni = i + di;
                            int nj = j + dj;
                            if (ni >= 0 && nj >= 0 && ni < size && nj < size && !set.contains(idx(ni, nj))) {
                                deco(ni, Spire.FY - 1, nj, base);
                                deco(ni, Spire.FY - 2, nj, base);
                            }
                        }
                    }
                }
                for (int k : cells) {
                    int i = k % size;
                    int j = k / size;
                    deco(i, Spire.FY - 2, j, base);
                    deco(i, Spire.FY - 1, j, fluid);
                    deco(i, Spire.FY, j, fluid);
                }
            }
        }

        BlockState rubble = theme.rubble();
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int hh = h[idx(i, j)];
                if (hh <= 0) {
                    continue;
                }
                if (hash(i, j, 1) % 64 == 0) {
                    if (theme.hangLight()) {
                        deco(i, Spire.FY + hh, j, theme.light());
                    } else {
                        deco(i, Spire.FY + hh + 1, j, theme.light());
                    }
                }
                if (blocked[idx(i, j)] || nearKeep(i, j, 1)) {
                    continue;
                }
                int r = hash(i, j, 2);
                switch (theme) {
                    case CATACOMBS -> {
                        if (r % 90 == 0) {
                            deco(i, Spire.FY + hh, j, Blocks.COBWEB.defaultBlockState());
                        } else if (r % 140 == 1) {
                            deco(i, Spire.FY + 1, j, Blocks.BONE_BLOCK.defaultBlockState());
                        }
                    }
                    case CAVERNS -> {
                        if (r % 37 == 3) {
                            deco(i, Spire.FY + hh, j, Blocks.DRIPSTONE_BLOCK.defaultBlockState());
                        } else if (r % 80 == 1 && hh >= 6) {
                            deco(i, Spire.FY + 1, j, Blocks.COBBLESTONE.defaultBlockState());
                        }
                    }
                    case ASHEN -> {
                        if (r % 95 == 0) {
                            deco(i, Spire.FY, j, Blocks.MAGMA_BLOCK.defaultBlockState());
                        }
                    }
                    default -> {
                    }
                }
                if (r % 61 == 5 && theme != FloorTheme.SANCTUARY) {
                    deco(i, Spire.FY + 1, j, rubble);
                }
            }
        }

        // Pillars in big rooms (and a ring in the boss arena).
        for (Room r : rooms) {
            if (r.arena()) {
                continue;
            }
            if (r.w() >= 9 && r.d() >= 9 && !nearKeep(r.cx(), r.cz(), 4)) {
                int[][] corners = {{r.x() + 2, r.z() + 2}, {r.x() + r.w() - 3, r.z() + 2},
                        {r.x() + 2, r.z() + r.d() - 3}, {r.x() + r.w() - 3, r.z() + r.d() - 3}};
                for (int[] c : corners) {
                    if (blocked[idx(c[0], c[1])] || nearKeep(c[0], c[1], 2)) {
                        continue;
                    }
                    for (int y = Spire.FY + 1; y <= Spire.FY + r.h(); y++) {
                        deco(c[0], y, c[1], theme.pillar());
                    }
                }
            }
        }
        if (arenaR > 0) {
            int pillars = kind == Spire.Kind.FINAL ? 12 : 8;
            for (int k = 0; k < pillars; k++) {
                double ang = Math.PI * 2 * k / pillars;
                int px = arenaCx + (int) Math.round(Math.cos(ang) * (arenaR - 5));
                int pz = arenaCz + (int) Math.round(Math.sin(ang) * (arenaR - 5));
                int top = Spire.FY + (kind == Spire.Kind.FINAL ? 17 : 14);
                for (int y = Spire.FY + 1; y <= top; y++) {
                    deco(px, y, pz, theme.pillar());
                }
            }
            // Sealed pedestal where the exit will appear once the guardian falls.
            deco(upPortal.getX() - ox, Spire.FY + 1, upPortal.getZ(), Blocks.CRYING_OBSIDIAN.defaultBlockState());
        }

        if (kind == Spire.Kind.REST) {
            decorateRest();
        }
    }

    private void decorateRest() {
        int cx = size / 2;
        int cz = size / 2;
        BlockState campfire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true);
        deco(cx, Spire.FY + 1, cz, campfire);
        for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
            deco(cx + o[0], Spire.FY + 1, cz + o[1], Blocks.HAY_BLOCK.defaultBlockState());
        }
        for (int[] o : new int[][]{{2, 2}, {-2, 2}, {2, -2}, {-2, -2}}) {
            deco(cx + o[0], Spire.FY + 1, cz + o[1], Blocks.LANTERN.defaultBlockState());
        }
        // Bedrolls: red carpet with a white pillow, arranged around the fire.
        for (int k = 0; k < 8; k++) {
            double ang = Math.PI * 2 * (k + 0.5) / 8;
            int bx = cx + (int) Math.round(Math.cos(ang) * 9);
            int bz = cz + (int) Math.round(Math.sin(ang) * 7);
            deco(bx, Spire.FY + 1, bz, Blocks.CARPET.red().defaultBlockState());
            deco(bx + 1, Spire.FY + 1, bz, Blocks.CARPET.red().defaultBlockState());
            deco(bx - 1, Spire.FY + 1, bz, Blocks.CARPET.white().defaultBlockState());
        }
        // Lantern posts along the hall.
        for (int k = 0; k < 10; k++) {
            double ang = Math.PI * 2 * k / 10;
            int px = cx + (int) Math.round(Math.cos(ang) * 22);
            int pz = cz + (int) Math.round(Math.sin(ang) * 17);
            if (!open(px, pz) || nearKeep(px, pz, 2)) {
                continue;
            }
            deco(px, Spire.FY + 1, pz, theme.pillar());
            deco(px, Spire.FY + 2, pz, theme.pillar());
            deco(px, Spire.FY + 3, pz, Blocks.LANTERN.defaultBlockState());
        }
        // Moss and azaleas.
        for (int j = cz - 20; j <= cz + 20; j++) {
            for (int i = cx - 26; i <= cx + 26; i++) {
                if (!open(i, j) || blocked[idx(i, j)] || nearKeep(i, j, 2)) {
                    continue;
                }
                double dx = (i - cx) / 26.0;
                double dz = (j - cz) / 20.0;
                if (dx * dx + dz * dz > 0.55 && hash(i, j, 12) % 5 == 0) {
                    deco(i, Spire.FY, j, Blocks.MOSS_BLOCK.defaultBlockState());
                    if (hash(i, j, 13) % 4 == 0) {
                        deco(i, Spire.FY + 1, j, Blocks.AZALEA.defaultBlockState());
                    }
                }
            }
        }
        // Two supply chests beside the fire.
        chests.add(new Chest(Spire.abs(floor, cx + 5, Spire.FY + 1, cz - 4), Direction.WEST));
        chests.add(new Chest(Spire.abs(floor, cx - 5, Spire.FY + 1, cz + 4), Direction.EAST));
    }
}
