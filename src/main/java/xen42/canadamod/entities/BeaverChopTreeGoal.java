package xen42.canadamod.entities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SaplingBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import xen42.canadamod.CanadaBlocks;
import xen42.canadamod.CanadaMod;

public class BeaverChopTreeGoal extends Goal {
    public static Map<LeavesBlock, SaplingBlock> saplingMap = new HashMap();

    private final BeaverEntity beaver;

    @Nullable
    private Path pathToTrunk;

    @Nullable
    private SaplingBlock sapling;

    private boolean isChoppingTree;

    private final int MAX_BREAKING_TICKS = 40;
    private int choppingTicks = 0;

    // Prevent chewing through multiple touching trees
    private static final int MAX_LOGS_TO_BREAK = 32;
    private static final int MAX_TREE_SCAN_LOGS = 48;
    private static final int MAX_LEAF_SEARCH_DISTANCE = 3;

    public BeaverChopTreeGoal(BeaverEntity beaver) {
        this.beaver = beaver;
    }

    public boolean canStart() {
        if (!beaver.canChopTree()) {
            return false;
        }

        if (!(beaver.getWorld() instanceof ServerWorld serverWorld) || !serverWorld.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return false;
        }

        if (!this.beaver.isFrenzied() && this.beaver.getRandom().nextInt(toGoalTicks(20)) != 0) {
            return false;
        }

        this.beaver.setChoppingTree(findChoppableTree(5));
        if (this.beaver.getChoppingTreePos() == null) {
            return false;
        }

        pathToTrunk = beaver.getNavigation().findPathTo(this.beaver.getChoppingTreePos().getX(), this.beaver.getChoppingTreePos().getY(),
            this.beaver.getChoppingTreePos().getZ(), 0);
        if (pathToTrunk == null) {
            return false;
        }

        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (isChoppingTree) return true;

        if (this.beaver.getChoppingTreePos() == null) return false;

        return !this.beaver.getNavigation().isIdle() || this.beaver.getWorld().getBlockState(this.beaver.getChoppingTreePos()).isAir();
    }

    @Override
    public void start() {
        this.beaver.getNavigation().startMovingAlong(this.pathToTrunk, 1f);
    }

    @Override
    public void stop() {
        // In case somebody else broke it
        this.beaver.stopChopping();
        this.sapling = null;
    }

    private BlockPos findChoppableTree(int radius) {
        BlockPos beaverPos = beaver.getBlockPos();

        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = beaverPos.add(dx, dy, dz);

                    if (isTreeBase(pos) && isTreeTrunk(pos)) {
                        return pos;
                    }
                }
            }
        }

        return null;
    }

    private boolean isTreeBase(BlockPos pos) {
        var world = beaver.getWorld();
        BlockState trunk = world.getBlockState(pos);
        BlockState below = world.getBlockState(pos.down());

        if (!trunk.isIn(BlockTags.LOGS)) {
            return false;
        }

        return below.isIn(BlockTags.DIRT)
            || below.isOf(Blocks.GRASS_BLOCK)
            || below.isOf(Blocks.PODZOL);
    }

    private boolean isTreeTrunk(BlockPos startPos) {
        var world = beaver.getWorld();
        this.sapling = null;

        // Reject 2x2 / oversized trunks at the base
        if (isLargeTrunkBase(startPos)) {
            return false;
        }

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> foundLogs = new ArrayList<>();

        queue.add(startPos);

        while (!queue.isEmpty() && foundLogs.size() < MAX_TREE_SCAN_LOGS) {
            BlockPos pos = queue.removeFirst();

            if (!visited.add(pos)) {
                continue;
            }

            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }

            if (hasTreeTapAdjacent(pos)) {
                return false;
            }

            foundLogs.add(pos);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }

                        queue.add(pos.add(dx, dy, dz));
                    }
                }
            }
        }

        if (foundLogs.isEmpty()) {
            return false;
        }

        boolean hasHeight = false;
        for (BlockPos logPos : foundLogs) {
            if (logPos.getY() >= startPos.getY() + 2) {
                hasHeight = true;
                break;
            }
        }

        if (!hasHeight) {
            return false;
        }

        for (BlockPos logPos : foundLogs) {
            BlockPos naturalLeafPos = findNaturalLeafNear(logPos, MAX_LEAF_SEARCH_DISTANCE);
            if (naturalLeafPos != null) {
                this.sapling = getSaplingFromLeaf(naturalLeafPos);
                return true;
            }
        }

        return false;
    }

    private boolean isLargeTrunkBase(BlockPos startPos) {
        var world = beaver.getWorld();
        int y = startPos.getY();

        // Check the 3x3 around the chosen base log at the same Y level.
        // If multiple logs are touching at ground level, this is probably
        // a 2x2 giant tree trunk or some other oversized trunk.
        int adjacentLogs = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                BlockPos pos = new BlockPos(startPos.getX() + dx, y, startPos.getZ() + dz);
                if (world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                    adjacentLogs++;
                }
            }
        }

        if (adjacentLogs == 0) {
            return false;
        }

        // Direct side-adjacent logs are the big red flag for 2x2 trunks.
        if (world.getBlockState(startPos.north()).isIn(BlockTags.LOGS)) return true;
        if (world.getBlockState(startPos.south()).isIn(BlockTags.LOGS)) return true;
        if (world.getBlockState(startPos.east()).isIn(BlockTags.LOGS)) return true;
        if (world.getBlockState(startPos.west()).isIn(BlockTags.LOGS)) return true;

        // Also reject full 2x2 diagonal arrangements.
        if (is2x2AtLevel(startPos)) {
            return true;
        }

        return false;
    }

    private boolean is2x2AtLevel(BlockPos pos) {
        var world = beaver.getWorld();

        return isLog(world, pos)
            && (
                (isLog(world, pos.east()) && isLog(world, pos.south()) && isLog(world, pos.east().south())) ||
                (isLog(world, pos.west()) && isLog(world, pos.south()) && isLog(world, pos.west().south())) ||
                (isLog(world, pos.east()) && isLog(world, pos.north()) && isLog(world, pos.east().north())) ||
                (isLog(world, pos.west()) && isLog(world, pos.north()) && isLog(world, pos.west().north()))
            );
    }

    private boolean isLog(net.minecraft.world.World world, BlockPos pos) {
        return world.getBlockState(pos).isIn(BlockTags.LOGS);
    }

    @Nullable
    private BlockPos findNaturalLeafNear(BlockPos center, int radius) {
        var world = beaver.getWorld();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);

                    if (isNaturalLeaf(state)) {
                        return pos;
                    }
                }
            }
        }

        return null;
    }

    private boolean hasTreeTapAdjacent(BlockPos pos) {
        var world = this.beaver.getWorld();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    BlockState state = world.getBlockState(pos.add(dx, dy, dz));
                    if (state.isOf(CanadaBlocks.TREE_TAP)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
    
    private boolean isNaturalLeaf(BlockState state) {
        return state.isIn(BlockTags.LEAVES) && state.contains(Properties.PERSISTENT) && !state.get(Properties.PERSISTENT);
    }

    @Nullable
    private SaplingBlock getSaplingFromLeaf(BlockPos pos) {
        var blockState = beaver.getWorld().getBlockState(pos);
        var leafBlock = (LeavesBlock)blockState.getBlock();

        if (leafBlock == null) {
            return null;
        }

        if (saplingMap.containsKey(leafBlock)) {
            return saplingMap.get(leafBlock);
        }
        else {
            // This is straight goofy ok we need to simulate like 40 leaf drops to PROBABLY get a sapling but even then
            for (int i = 0; i < 60; i++) {
                for (var item : Block.getDroppedStacks(blockState, (ServerWorld)beaver.getWorld(), pos, null)) {
                    if (item.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof SaplingBlock sapling) {
                        saplingMap.put(leafBlock, sapling);
                        return sapling;
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void tick() {
        if (isChoppingTree) {
            // Don't let them move away
            this.beaver.getNavigation().stop();
            this.beaver.getLookControl().lookAt(this.beaver.getChoppingTreePos().toCenterPos());

            if (!this.beaver.getWorld().getBlockState(this.beaver.getChoppingTreePos()).isIn(BlockTags.LOGS) || 
                this.beaver.getChoppingTreePos().toCenterPos().distanceTo(this.beaver.getPos()) > 2f ||
                this.beaver.getRecentDamageSource() != null) {
                // If something else broke it give up, or if we moved away or got hurts
                this.beaver.stopChopping();
                isChoppingTree = false;
            }
            else {
                // Particles and block breaking effects
                var stage = 10 * (MAX_BREAKING_TICKS - choppingTicks) / MAX_BREAKING_TICKS;

                this.beaver.setChoppingProgress(stage);

                choppingTicks--;

                if (choppingTicks <= 0) {
                    breakTree();
                }

                // Make sure the beaver stays here and faces the tree
                // Null check because maybe just broke it
                if (this.beaver.getChoppingTreePos() != null) {

                    this.beaver.getNavigation().stop();
                    this.beaver.getLookControl().lookAt(this.beaver.getChoppingTreePos().toCenterPos());
                }
            }
        }
        else if (this.beaver.getChoppingTreePos().toCenterPos().distanceTo(this.beaver.getPos()) < 1.6f) {
            // Reached the tree, chop it
            this.isChoppingTree = true;
            choppingTicks = MAX_BREAKING_TICKS;
        }
        if (beaver.isDead()) {
            stop();
        }
    }

    private void breakTree() {
        BlockPos startPos = this.beaver.getChoppingTreePos();
        var world = this.beaver.getWorld();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> toCheck = new ArrayDeque<>();
        ArrayDeque<BlockPos> logsToBreak = new ArrayDeque<>();

        toCheck.add(startPos);

        while (!toCheck.isEmpty() && logsToBreak.size() < MAX_LOGS_TO_BREAK) {
            BlockPos pos = toCheck.removeFirst();

            if (!visited.add(pos)) {
                continue;
            }

            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }

            logsToBreak.add(pos);

            // Check all adjacent blocks
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }

                        toCheck.add(pos.add(dx, dy, dz));
                    }
                }
            }
        }

        while (!logsToBreak.isEmpty()) {
            world.breakBlock(logsToBreak.removeFirst(), true);
        }

        if (sapling != null) {
            world.setBlockState(startPos, sapling.getDefaultState());
        }

        this.beaver.stopChopping();
        this.beaver.onChopTree();
        this.isChoppingTree = false;
    }
}