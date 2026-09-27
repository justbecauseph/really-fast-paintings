package me.justbecause.fastpaintings.painting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Pure geometry utility for painting bounds, occupied world cells, and backing support cells.
 * Matches vanilla Minecraft 26.3 {@code Painting} and {@code HangingEntity} calculations exactly.
 */
public record PaintingFootprint(
        BlockPos anchor,
        Direction facing,
        int width,
        int height,
        AABB boundingBox,
        AABB supportBox,
        List<BlockPos> occupiedCells,
        List<BlockPos> supportCells
) {
    public static final double DEPTH = 0.0625; // 1/16 block
    public static final float WALL_OFFSET = 0.46875F; // 15/32 block

    public static final int ANCHOR_OFFSET_INDEX = 7;
    public static final int MIN_OFFSET_INDEX = 0;
    public static final int MAX_OFFSET_INDEX = 15;

    public PaintingFootprint {
        Objects.requireNonNull(anchor, "anchor cannot be null");
        Objects.requireNonNull(facing, "facing cannot be null");
        if (facing.getAxis().isVertical()) {
            throw new IllegalArgumentException("Facing must be horizontal: " + facing);
        }
        if (width < 1 || width > 16) {
            throw new IllegalArgumentException("Width must be in [1, 16], got: " + width);
        }
        if (height < 1 || height > 16) {
            throw new IllegalArgumentException("Height must be in [1, 16], got: " + height);
        }
        occupiedCells = Collections.unmodifiableList(occupiedCells);
        supportCells = Collections.unmodifiableList(supportCells);
    }

    public static PaintingFootprint of(BlockPos anchor, Direction facing, int width, int height) {
        AABB bb = calculateBoundingBox(anchor, facing, width, height);
        AABB sb = calculateSupportBox(bb, facing);
        List<BlockPos> occupied = computeCellsFromBox(bb);
        List<BlockPos> support = computeCellsFromBox(sb);
        return new PaintingFootprint(anchor, facing, width, height, bb, sb, occupied, support);
    }

    /**
     * Calculates the world-space bounding box matching {@code Painting#calculateBoundingBox}.
     */
    public static AABB calculateBoundingBox(BlockPos pos, Direction direction, int width, int height) {
        Vec3 attachedToWall = Vec3.atCenterOf(pos).relative(direction, -WALL_OFFSET);
        double horizontalOffset = (width % 2 == 0) ? 0.5 : 0.0;
        double verticalOffset = (height % 2 == 0) ? 0.5 : 0.0;
        Direction left = direction.getCounterClockWise();
        Vec3 position = attachedToWall.relative(left, horizontalOffset).relative(Direction.UP, verticalOffset);
        Direction.Axis axis = direction.getAxis();
        double xSize = axis == Direction.Axis.X ? DEPTH : width;
        double ySize = height;
        double zSize = axis == Direction.Axis.Z ? DEPTH : width;
        return AABB.ofSize(position, xSize, ySize, zSize);
    }

    /**
     * Calculates the support bounding box matching {@code HangingEntity#calculateSupportBox}.
     */
    public static AABB calculateSupportBox(AABB boundingBox, Direction direction) {
        return boundingBox.move(direction.step().mul(-0.5F)).deflate(1.0E-7);
    }

    /**
     * Extracts the discrete {@link BlockPos} cells covered by a bounding box.
     */
    public static List<BlockPos> computeCellsFromBox(AABB box) {
        AABB deflated = box.deflate(1.0E-5);
        int minX = (int) Math.floor(deflated.minX);
        int maxX = (int) Math.floor(deflated.maxX);
        int minY = (int) Math.floor(deflated.minY);
        int maxY = (int) Math.floor(deflated.maxY);
        int minZ = (int) Math.floor(deflated.minZ);
        int maxZ = (int) Math.floor(deflated.maxZ);

        List<BlockPos> cells = new ArrayList<>((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1));
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    /**
     * Returns the relative X offset index in [0, 15] for a cell relative to the anchor.
     * Anchor itself has offset index 7.
     */
    public static int getOffsetX(BlockPos cellPos, BlockPos anchorPos, Direction facing) {
        Direction left = facing.getCounterClockWise();
        int dx = (cellPos.getX() - anchorPos.getX()) * left.getStepX()
                + (cellPos.getZ() - anchorPos.getZ()) * left.getStepZ();
        return dx + ANCHOR_OFFSET_INDEX;
    }

    /**
     * Returns the relative Y offset index in [0, 15] for a cell relative to the anchor.
     * Anchor itself has offset index 7.
     */
    public static int getOffsetY(BlockPos cellPos, BlockPos anchorPos) {
        int dy = cellPos.getY() - anchorPos.getY();
        return dy + ANCHOR_OFFSET_INDEX;
    }

    /**
     * Reconstructs the anchor position from a part cell position, facing, and offset indices.
     */
    public static BlockPos getAnchorPos(BlockPos partPos, Direction facing, int offsetX, int offsetY) {
        Direction left = facing.getCounterClockWise();
        int dx = offsetX - ANCHOR_OFFSET_INDEX;
        int dy = offsetY - ANCHOR_OFFSET_INDEX;
        return partPos.relative(left, -dx).relative(Direction.UP, -dy);
    }

    /**
     * Calculates the cell position from an anchor position, facing, and offset indices.
     */
    public static BlockPos getCellPos(BlockPos anchorPos, Direction facing, int offsetX, int offsetY) {
        Direction left = facing.getCounterClockWise();
        int dx = offsetX - ANCHOR_OFFSET_INDEX;
        int dy = offsetY - ANCHOR_OFFSET_INDEX;
        return anchorPos.relative(left, dx).relative(Direction.UP, dy);
    }

    /**
     * Returns true if all backing support cells meet vanilla solid / diode requirements.
     * Matches vanilla Minecraft 26.3 {@code HangingEntity#survives} contract exactly.
     */
    @SuppressWarnings("deprecation")
    public boolean isSupported(LevelReader level) {
        for (BlockPos pos : this.supportCells) {
            BlockState state = level.getBlockState(pos);
            if (!state.isSolid() && !DiodeBlock.isDiode(state)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if all occupied cells are free (replaceable/air or matching an allowed predicate).
     */
    public boolean canOccupy(LevelReader level, Predicate<BlockPos> canOccupyPos) {
        for (BlockPos pos : this.occupiedCells) {
            if (!canOccupyPos.test(pos)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether this footprint contains the specified block position.
     */
    public boolean containsOccupied(BlockPos pos) {
        return this.occupiedCells.contains(pos);
    }

    /**
     * Returns whether this footprint's support contains the specified block position.
     */
    public boolean containsSupport(BlockPos pos) {
        return this.supportCells.contains(pos);
    }
}
