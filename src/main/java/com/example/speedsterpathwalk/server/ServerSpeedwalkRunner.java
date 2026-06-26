package com.example.speedsterpathwalk.server;

import com.example.speedsterpathwalk.SpeedsterPathwalkMod;
import com.example.speedsterpathwalk.path.ServerAStarPathfinder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ServerSpeedwalkRunner {
    /**
     * Fixed 5x movement model.
     *
     * Version 0.2.4 changes execution from pure slice teleports to a hybrid model.
     * Most travel is now driven by server-side velocity and jump impulses so the
     * player can visibly sprint, jump gaps, step up ledges, and perform simple
     * parkour. Teleports are still kept as correction/snap tools when the player
     * drifts too far from the route, gets stuck, or reaches the final endpoint.
     */
    public static final int FIXED_TIME_SCALE = 5;

    private static final double HYBRID_SPEED_BLOCKS_PER_TICK = 1.18D;
    private static final double CAREFUL_APPROACH_SPEED_BLOCKS_PER_TICK = 0.62D;
    private static final double NODE_REACH_DISTANCE = 0.72D;
    private static final double FINAL_REACH_DISTANCE = 0.36D;
    private static final double HARD_CORRECTION_DISTANCE = 3.25D;
    private static final double SOFT_CORRECTION_DISTANCE = 1.65D;
    private static final int SOFT_CORRECTION_COOLDOWN_TICKS = 6;
    private static final int STUCK_CORRECTION_TICKS = 9;

    private static final double FLAT_JUMP_VERTICAL_VELOCITY = 0.42D;
    private static final double LEDGE_JUMP_VERTICAL_VELOCITY = 0.52D;
    private static final double MAX_PARKOUR_SEGMENT_BLOCKS = 4.35D;
    private static final double MIN_PARKOUR_SEGMENT_BLOCKS = 1.55D;

    private static final int FALL_DAMAGE_GRACE_TICKS = 40;

    private static final Map<UUID, ActiveRun> ACTIVE_RUNS = new HashMap<>();
    private static final Map<UUID, Integer> FALL_DAMAGE_GRACE = new HashMap<>();

    private ServerSpeedwalkRunner() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ServerSpeedwalkRunner::tickServer);
    }

    public static void start(ServerPlayerEntity player, List<BlockPos> path) {
        if (path.isEmpty()) {
            player.sendMessage(Text.literal("Cannot start speedwalk: path is empty."), false);
            return;
        }

        List<Vec3d> points = new ArrayList<>(path.size());
        for (BlockPos pos : path) {
            points.add(toFeetCenter(pos));
        }

        int firstTarget = Math.min(1, points.size() - 1);
        ActiveRun run = new ActiveRun(player.getUuid(), player.getServerWorld(), points, firstTarget);
        ACTIVE_RUNS.put(player.getUuid(), run);
        FALL_DAMAGE_GRACE.remove(player.getUuid());
        player.fallDistance = 0.0F;
        player.setSprinting(true);
        player.sendMessage(Text.literal("5x hybrid speedwalk started. Nodes: " + points.size()), true);
    }

    public static boolean stop(ServerPlayerEntity player, String message) {
        boolean removed = ACTIVE_RUNS.remove(player.getUuid()) != null;
        if (removed) {
            player.setVelocity(Vec3d.ZERO);
            player.fallDistance = 0.0F;
            grantFallDamageGrace(player.getUuid());
            player.setSprinting(false);
            if (ServerPlayNetworking.canSend(player, SpeedsterPathwalkMod.STOP_PATH_PACKET)) {
                ServerPlayNetworking.send(player, SpeedsterPathwalkMod.STOP_PATH_PACKET, PacketByteBufs.empty());
            }
            if (message != null) {
                player.sendMessage(Text.literal(message), true);
            }
        }
        return removed;
    }

    public static boolean isFallDamageProtected(UUID playerId) {
        return ACTIVE_RUNS.containsKey(playerId) || FALL_DAMAGE_GRACE.getOrDefault(playerId, 0) > 0;
    }

    private static void grantFallDamageGrace(UUID playerId) {
        FALL_DAMAGE_GRACE.put(playerId, FALL_DAMAGE_GRACE_TICKS);
    }

    private static void tickFallDamageGrace() {
        if (FALL_DAMAGE_GRACE.isEmpty()) {
            return;
        }

        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : FALL_DAMAGE_GRACE.entrySet()) {
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                expired.add(entry.getKey());
            } else {
                entry.setValue(remaining);
            }
        }

        for (UUID uuid : expired) {
            FALL_DAMAGE_GRACE.remove(uuid);
        }
    }

    private static void tickServer(MinecraftServer server) {
        tickFallDamageGrace();

        if (ACTIVE_RUNS.isEmpty()) {
            return;
        }

        List<UUID> toRemove = new ArrayList<>();
        for (ActiveRun run : ACTIVE_RUNS.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(run.playerId);
            if (player == null || player.isRemoved() || !player.isAlive() || player.getServerWorld() != run.world) {
                toRemove.add(run.playerId);
                continue;
            }

            TickResult result = tickRun(player, run);
            if (result.finished()) {
                toRemove.add(run.playerId);
                Vec3d finalPos = resolveFinalLandingPosition(run.world, run.points.get(run.points.size() - 1));
                correctionTeleport(player, finalPos, result.yaw());
                player.setVelocity(Vec3d.ZERO);
                player.fallDistance = 0.0F;
                grantFallDamageGrace(player.getUuid());
                player.setSprinting(false);
                player.sendMessage(Text.literal("Destination reached."), true);
                if (ServerPlayNetworking.canSend(player, SpeedsterPathwalkMod.STOP_PATH_PACKET)) {
                    ServerPlayNetworking.send(player, SpeedsterPathwalkMod.STOP_PATH_PACKET, PacketByteBufs.empty());
                }
            }
        }

        for (UUID uuid : toRemove) {
            ACTIVE_RUNS.remove(uuid);
        }
    }

    private static TickResult tickRun(ServerPlayerEntity player, ActiveRun run) {
        player.setSprinting(true);
        player.fallDistance = 0.0F;

        if (run.currentIndex >= run.points.size()) {
            return new TickResult(true, player.getYaw());
        }

        Vec3d position = player.getPos();
        advanceReachedNodes(position, run);
        if (run.currentIndex >= run.points.size()) {
            return new TickResult(true, player.getYaw());
        }

        Vec3d target = run.points.get(run.currentIndex);
        float yaw = yawToward(position, target, player.getYaw());

        double distanceToTarget = position.distanceTo(target);
        updateStuckState(run, distanceToTarget);

        if (shouldCorrect(player, run, distanceToTarget)) {
            correctionTeleport(player, target, yaw);
            run.correctionCooldownTicks = SOFT_CORRECTION_COOLDOWN_TICKS;
            run.stuckTicks = 0;
            return new TickResult(false, yaw);
        }

        driveWithVelocity(player, run, position, target, yaw);
        run.correctionCooldownTicks = Math.max(0, run.correctionCooldownTicks - 1);

        return new TickResult(false, yaw);
    }

    private static void advanceReachedNodes(Vec3d position, ActiveRun run) {
        while (run.currentIndex < run.points.size()) {
            Vec3d target = run.points.get(run.currentIndex);
            boolean finalNode = run.currentIndex == run.points.size() - 1;
            double reachDistance = finalNode ? FINAL_REACH_DISTANCE : NODE_REACH_DISTANCE;

            double horizontalDistance = horizontalDistance(position, target);
            double verticalDistance = Math.abs(position.y - target.y);
            if (horizontalDistance <= reachDistance && verticalDistance <= 0.95D) {
                run.currentIndex++;
                run.lastDistanceToTarget = Double.MAX_VALUE;
                run.stuckTicks = 0;
                continue;
            }
            return;
        }
    }

    private static void updateStuckState(ActiveRun run, double distanceToTarget) {
        if (run.lastDistanceToTarget < Double.MAX_VALUE && distanceToTarget > run.lastDistanceToTarget - 0.035D) {
            run.stuckTicks++;
        } else {
            run.stuckTicks = 0;
        }
        run.lastDistanceToTarget = distanceToTarget;
    }

    private static boolean shouldCorrect(ServerPlayerEntity player, ActiveRun run, double distanceToTarget) {
        if (distanceToTarget >= HARD_CORRECTION_DISTANCE) {
            return true;
        }

        if (run.correctionCooldownTicks > 0) {
            return false;
        }

        if (run.stuckTicks >= STUCK_CORRECTION_TICKS) {
            return true;
        }

        return distanceToTarget >= SOFT_CORRECTION_DISTANCE && player.isOnGround();
    }

    private static void driveWithVelocity(ServerPlayerEntity player, ActiveRun run, Vec3d position, Vec3d target, float yaw) {
        Vec3d delta = target.subtract(position);
        double horizontalDistance = Math.sqrt((delta.x * delta.x) + (delta.z * delta.z));
        Vec3d oldVelocity = player.getVelocity();

        double speed = chooseHorizontalSpeed(position, target, horizontalDistance, run.currentIndex == run.points.size() - 1);
        double vx = 0.0D;
        double vz = 0.0D;
        if (horizontalDistance > 0.001D) {
            vx = (delta.x / horizontalDistance) * speed;
            vz = (delta.z / horizontalDistance) * speed;
        }

        double vy = oldVelocity.y;
        boolean nearRunnableWater = ServerAStarPathfinder.isStandable(run.world, player.getBlockPos())
                && ServerAStarPathfinder.isRunnableWaterSurface(run.world, player.getBlockPos());
        boolean groundedEnough = player.isOnGround() || nearRunnableWater;
        boolean upwardTarget = target.y > position.y + 0.35D;
        boolean parkourGap = horizontalDistance >= MIN_PARKOUR_SEGMENT_BLOCKS
                && horizontalDistance <= MAX_PARKOUR_SEGMENT_BLOCKS
                && Math.abs(target.y - position.y) <= 1.25D;

        if (groundedEnough && (upwardTarget || parkourGap)) {
            vy = upwardTarget ? LEDGE_JUMP_VERTICAL_VELOCITY : FLAT_JUMP_VERTICAL_VELOCITY;
        } else if (nearRunnableWater && position.y < target.y + 0.08D) {
            vy = Math.max(vy, 0.05D);
        } else if (player.isOnGround() && Math.abs(target.y - position.y) < 0.30D) {
            vy = Math.max(0.0D, Math.min(vy, 0.08D));
        } else if (target.y < position.y - 0.75D) {
            vy = Math.min(vy, -0.12D);
        }

        Vec3d desiredVelocity = new Vec3d(vx, vy, vz);
        Vec3d velocityDelta = desiredVelocity.subtract(oldVelocity);
        player.addVelocity(velocityDelta);
        player.setSprinting(true);
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.fallDistance = 0.0F;
    }

    private static double chooseHorizontalSpeed(Vec3d position, Vec3d target, double horizontalDistance, boolean finalNode) {
        if (horizontalDistance < 0.001D) {
            return 0.0D;
        }

        double verticalDifference = Math.abs(target.y - position.y);
        double base = finalNode || verticalDifference > 0.75D
                ? CAREFUL_APPROACH_SPEED_BLOCKS_PER_TICK
                : HYBRID_SPEED_BLOCKS_PER_TICK;
        return MathHelper.clamp(horizontalDistance * 0.78D, 0.18D, base);
    }

    private static float yawToward(Vec3d from, Vec3d to, float fallbackYaw) {
        Vec3d delta = to.subtract(from);
        if (Math.abs(delta.x) <= 0.0001D && Math.abs(delta.z) <= 0.0001D) {
            return fallbackYaw;
        }
        return (float) (Math.atan2(delta.z, delta.x) * (180.0D / Math.PI)) - 90.0F;
    }

    private static double horizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt((dx * dx) + (dz * dz));
    }

    private static void correctionTeleport(ServerPlayerEntity player, Vec3d newPos, float yaw) {
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0.0F;
        player.setSprinting(true);
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.networkHandler.requestTeleport(newPos.x, newPos.y, newPos.z, yaw, player.getPitch());
    }

    private static Vec3d resolveFinalLandingPosition(ServerWorld world, Vec3d desired) {
        BlockPos desiredFeet = BlockPos.ofFloored(desired.x, desired.y, desired.z);
        if (ServerAStarPathfinder.isStandable(world, desiredFeet)) {
            return toFeetCenter(desiredFeet);
        }

        for (int offset = 1; offset <= 3; offset++) {
            BlockPos up = desiredFeet.up(offset);
            if (ServerAStarPathfinder.isStandable(world, up)) {
                return toFeetCenter(up);
            }

            BlockPos down = desiredFeet.down(offset);
            if (ServerAStarPathfinder.isStandable(world, down)) {
                return toFeetCenter(down);
            }
        }

        return desired;
    }

    private static Vec3d toFeetCenter(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    private static final class ActiveRun {
        private final UUID playerId;
        private final ServerWorld world;
        private final List<Vec3d> points;
        private int currentIndex;
        private double lastDistanceToTarget = Double.MAX_VALUE;
        private int stuckTicks = 0;
        private int correctionCooldownTicks = 0;

        private ActiveRun(UUID playerId, ServerWorld world, List<Vec3d> points, int currentIndex) {
            this.playerId = playerId;
            this.world = world;
            this.points = points;
            this.currentIndex = currentIndex;
        }
    }

    private record TickResult(boolean finished, float yaw) {
    }
}
