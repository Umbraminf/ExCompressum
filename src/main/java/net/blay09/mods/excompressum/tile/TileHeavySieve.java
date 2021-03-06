package net.blay09.mods.excompressum.tile;

import net.blay09.mods.excompressum.client.render.ParticleSieve;
import net.blay09.mods.excompressum.config.ModConfig;
import net.blay09.mods.excompressum.handler.VanillaPacketHandler;
import net.blay09.mods.excompressum.registry.ExRegistro;
import net.blay09.mods.excompressum.registry.heavysieve.HeavySieveRegistry;
import net.blay09.mods.excompressum.registry.sievemesh.SieveMeshRegistry;
import net.blay09.mods.excompressum.api.sievemesh.SieveMeshRegistryEntry;
import net.blay09.mods.excompressum.utils.StupidUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.Collection;

public class TileHeavySieve extends TileEntity implements ITickable {

    private static final float PROCESSING_INTERVAL = 0.075f;
    private static final int UPDATE_INTERVAL = 5;
    private static final int PARTICLE_TICKS = 20;
    private static final float EFFICIENCY_BOOST = 0.25f;

    private ItemStack meshStack = ItemStack.EMPTY;
    private ItemStack currentStack = ItemStack.EMPTY;

    private float progress;
    private int clicksSinceSecond;

    private boolean isDirty;
    private int ticksSinceSync;
    private int ticksSinceSecond;

    private int particleTicks;
    private int particleCount;

    public boolean addSiftable(EntityPlayer player, ItemStack itemStack) {
        if(!currentStack.isEmpty() || meshStack.isEmpty() || !HeavySieveRegistry.isSiftable(itemStack)) {
            return false;
        }
        currentStack = player.capabilities.isCreativeMode ? ItemHandlerHelper.copyStackWithSize(itemStack, 1) : itemStack.splitStack(1);
        progress = 0f;
        VanillaPacketHandler.sendTileEntityUpdate(this);
        return true;
    }

    @Override
    public void update() {
        ticksSinceSync++;
        if (ticksSinceSync >= UPDATE_INTERVAL) {
            ticksSinceSync = 0;

            if (isDirty) {
                VanillaPacketHandler.sendTileEntityUpdate(this);
                isDirty = false;
            }
        }
        ticksSinceSecond++;
        if(ticksSinceSecond >= 20) {
            clicksSinceSecond = 0;
            ticksSinceSecond = 0;
        }

        if(particleTicks > 0) {
            particleTicks--;
            if(particleTicks <= 0) {
                particleCount = 0;
            }
            if(world.isRemote) {
                spawnParticles();
            }
        }
    }

    @SideOnly(Side.CLIENT)
    public void spawnParticles() {
        if(!currentStack.isEmpty() && !ModConfig.client.disableParticles) {
            IBlockState state = StupidUtils.getStateFromItemStack(currentStack);
            if (state != null) {
                for(int i = 0; i < particleCount; i++) {
                    Minecraft.getMinecraft().effectRenderer.addEffect(new ParticleSieve(world, pos, 0.5 + world.rand.nextFloat() * 0.8 - 0.4, 0.4, 0.5 + world.rand.nextFloat() * 0.8 - 0.4, 1f, state));
                }
            }
        }
    }

    public boolean processContents(EntityPlayer player) {
        if(!currentStack.isEmpty() && !meshStack.isEmpty()) {
            if (player.capabilities.isCreativeMode) {
                progress = 1f;
            } else {
                clicksSinceSecond++;
                if (clicksSinceSecond <= ModConfig.automation.heavySieveClicksPerSecond) {
                    int efficiency = ExRegistro.getMeshEfficiency(meshStack);
                    progress = Math.min(1f, progress + PROCESSING_INTERVAL * (1f + efficiency * EFFICIENCY_BOOST));
                }
            }
            if (progress >= 1f) {
                if (!world.isRemote) {
                    SieveMeshRegistryEntry sieveMesh = getSieveMesh();
                    if(sieveMesh != null) {
                        int fortune = ExRegistro.getMeshFortune(meshStack);
                        fortune += player.getLuck();
                        Collection<ItemStack> rewards = HeavySieveRegistry.rollSieveRewards(currentStack, sieveMesh, fortune, world.rand);
                        for (ItemStack itemStack : rewards) {
                            world.spawnEntity(new EntityItem(world, pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5, itemStack));
                        }
                    } else {
                        world.spawnEntity(new EntityItem(world, pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5, currentStack));
                    }
                    currentStack = ItemStack.EMPTY;
                    if(ExRegistro.doMeshesHaveDurability() && sieveMesh != null) {
                        if(!sieveMesh.isHeavy()) {
                            getWorld().playSound(null, this.pos, SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.BLOCKS, 0.5f, 2.5f);
                            meshStack = ItemStack.EMPTY;
                        } else {
                            meshStack.damageItem(1, player);
                            if (meshStack.isEmpty()) {
                                getWorld().playSound(null, this.pos, SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.BLOCKS, 0.5f, 2.5f);
                                meshStack = ItemStack.EMPTY;
                            }
                        }
                    }
                    progress = 0f;
                    VanillaPacketHandler.sendTileEntityUpdate(this);
                }
            }
            particleTicks = PARTICLE_TICKS;
            particleCount++;
            isDirty = true;
            return true;
        }
        return false;
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound) {
        super.readFromNBT(tagCompound);
        currentStack = new ItemStack(tagCompound.getCompoundTag("Content"));
        meshStack = new ItemStack(tagCompound.getCompoundTag("Mesh"));
        progress = tagCompound.getFloat("Progress");
        particleTicks = tagCompound.getInteger("ParticleTicks");
        particleCount = tagCompound.getInteger("ParticleCount");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound) {
        super.writeToNBT(tagCompound);
        tagCompound.setTag("Content", currentStack.writeToNBT(new NBTTagCompound()));
        tagCompound.setTag("Mesh", meshStack.writeToNBT(new NBTTagCompound()));
        tagCompound.setFloat("Progress", progress);
        tagCompound.setInteger("ParticleTicks", particleTicks);
        tagCompound.setInteger("ParticleCount", particleCount);
        return tagCompound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, getBlockMetadata(), getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager networkManager, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    public ItemStack getCurrentStack() {
        return currentStack;
    }

    public ItemStack getMeshStack() {
        return meshStack;
    }

    public float getProgress() {
        return progress;
    }

    @Nullable
    public SieveMeshRegistryEntry getSieveMesh() {
        if(!meshStack.isEmpty()) {
            return SieveMeshRegistry.getEntry(meshStack);
        }
        return null;
    }

    public void setMeshStack(ItemStack meshStack) {
        this.meshStack = meshStack;
        VanillaPacketHandler.sendTileEntityUpdate(this);
    }
}
