package me.justbecause.fastpaintings.block.entity;

import me.justbecause.fastpaintings.FastPaintings;
import me.justbecause.fastpaintings.block.PaintingBlock;
import me.justbecause.fastpaintings.block.PaintingPartBlock;
import me.justbecause.fastpaintings.init.ModRegistry;
import me.justbecause.fastpaintings.painting.PaintingFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.variant.VariantUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class PaintingBlockEntity extends BlockEntity {

    private @Nullable Holder<PaintingVariant> variant;
    private boolean isRemoving = false;

    private volatile @Nullable PaintingFootprint cachedFootprint;
    private volatile @Nullable AABB cachedRenderBox;
    private volatile int @Nullable [] cachedLight;
    private volatile long lastLightUpdateTime = -1;
    private volatile boolean lightDirty = true;
    private transient @Nullable Object lastRenderLod;

    public PaintingBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.PAINTING_BLOCK_ENTITY, pos, state);
    }

    @Override
    public void setBlockState(BlockState blockState) {
        super.setBlockState(blockState);
        this.cachedFootprint = null;
        this.cachedRenderBox = null;
        this.markLightDirty();
    }

    public Direction getFacing() {
        BlockState state = this.getBlockState();
        return state.hasProperty(PaintingBlock.FACING) ? state.getValue(PaintingBlock.FACING) : Direction.NORTH;
    }

    public @Nullable Holder<PaintingVariant> getVariant() {
        return this.variant;
    }

    public void setVariant(@Nullable Holder<PaintingVariant> variant) {
        this.variant = variant;
        this.cachedFootprint = null;
        this.cachedRenderBox = null;
        this.markLightDirty();
        this.setChanged();
        if (this.level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_ALL);
        }
    }

    public boolean isRemoving() {
        return this.isRemoving;
    }

    public void setRemoving(boolean removing) {
        this.isRemoving = removing;
    }

    public void markLightDirty() {
        this.lightDirty = true;
    }

    public boolean isLightDirty(long currentTime, int expectedLength) {
        return this.lightDirty || this.cachedLight == null || this.cachedLight.length != expectedLength || (currentTime - this.lastLightUpdateTime >= 20);
    }

    public int[] getOrCreateLightCache(int length) {
        int[] current = this.cachedLight;
        if (current == null || current.length != length) {
            current = new int[length];
            this.cachedLight = current;
        }
        return current;
    }

    public int @Nullable [] getCachedLight() {
        return this.cachedLight;
    }

    public void setCachedLight(int[] light, long time) {
        this.cachedLight = light;
        this.lastLightUpdateTime = time;
        this.lightDirty = false;
    }

    @SuppressWarnings("unchecked")
    public <T> @Nullable T getLastRenderLod() {
        return (T) this.lastRenderLod;
    }

    public void setLastRenderLod(@Nullable Object lod) {
        this.lastRenderLod = lod;
    }

    public int getPaintingWidth() {
        return this.variant != null ? this.variant.value().width() : 1;
    }

    public int getPaintingHeight() {
        return this.variant != null ? this.variant.value().height() : 1;
    }

    public PaintingFootprint getFootprint() {
        PaintingFootprint fp = this.cachedFootprint;
        if (fp == null) {
            fp = PaintingFootprint.of(this.worldPosition, this.getFacing(), this.getPaintingWidth(), this.getPaintingHeight());
            this.cachedFootprint = fp;
            this.cachedRenderBox = fp.boundingBox().inflate(0.5);
        }
        return fp;
    }

    public AABB getRenderBoundingBox() {
        AABB box = this.cachedRenderBox;
        if (box == null) {
            getFootprint();
            box = this.cachedRenderBox;
        }
        return box != null ? box : new AABB(this.worldPosition);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (this.level instanceof ServerLevel serverLevel && !this.isRemoving) {
            this.removeFootprint(serverLevel, true, null);
        }
    }

    /**
     * Safely destroys all blocks in this painting's multi-block footprint,
     * preserving water sources and preventing recursive removal.
     */
    public void removeFootprint(Level level, boolean dropItem, @Nullable Entity causedBy) {
        if (this.isRemoving) {
            return;
        }
        this.isRemoving = true;

        PaintingFootprint footprint = this.getFootprint();

        if (dropItem && level instanceof ServerLevel serverLevel) {
            this.dropItem(serverLevel, causedBy);
        }

        // 1. Remove all helper parts and restore their fluid state (preserving water)
        for (BlockPos pos : footprint.occupiedCells()) {
            if (pos.equals(this.worldPosition)) {
                continue;
            }
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState cellState = level.getBlockState(pos);
            if (cellState.is(ModRegistry.PAINTING_PART_BLOCK)) {
                BlockState replacement = cellState.getFluidState().createLegacyBlock();
                level.setBlock(pos, replacement, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }

        // 2. Remove anchor and restore its fluid state
        BlockState anchorState = level.getBlockState(this.worldPosition);
        BlockState anchorReplacement = anchorState.getFluidState().createLegacyBlock();
        level.setBlock(this.worldPosition, anchorReplacement, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
    }

    public void dropItem(ServerLevel serverLevel, @Nullable Entity causedBy) {
        if (serverLevel.getGameRules().get(GameRules.ENTITY_DROPS)) {
            serverLevel.playSound(null, this.worldPosition, SoundEvents.PAINTING_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
            if (!(causedBy instanceof Player player && player.hasInfiniteMaterials())) {
                Direction facing = this.getFacing();
                Vec3 center = this.getFootprint().boundingBox().getCenter();
                ItemStack stack = new ItemStack(Items.PAINTING);
                if (FastPaintings.CONFIG.preserveVariantOnDrop && this.variant != null) {
                    stack.set(DataComponents.PAINTING_VARIANT, this.variant);
                }
                ItemEntity itemEntity = new ItemEntity(
                        serverLevel,
                        center.x + facing.getStepX() * 0.15,
                        center.y,
                        center.z + facing.getStepZ() * 0.15,
                        stack
                );
                itemEntity.setDefaultPickUpDelay();
                serverLevel.addFreshEntity(itemEntity);
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (this.variant != null) {
            VariantUtils.writeVariant(output, this.variant);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.cachedFootprint = null;
        this.cachedRenderBox = null;
        this.markLightDirty();
        VariantUtils.readVariant(input, Registries.PAINTING_VARIANT).ifPresent(this::setVariant);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }
}
