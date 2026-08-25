package com.breakinblocks.aeroportals.portal;

import com.breakinblocks.aeroportals.AeroPortals;
import com.breakinblocks.aeroportals.api.AeroPortalType;
import com.breakinblocks.aeroportals.api.AeroPortalsApi;
import com.breakinblocks.aeroportals.api.PortalDestination;
import com.breakinblocks.aeroportals.compat.AetherCompat;
import com.breakinblocks.aeroportals.compat.ArsNouveauCompat;
import com.breakinblocks.aeroportals.compat.CreateEnderGatewayCompat;
import com.breakinblocks.aeroportals.compat.CreateTeleportersCompat;
import com.breakinblocks.aeroportals.compat.DeeperAndDarkerCompat;
import com.breakinblocks.aeroportals.compat.DraconicEvolutionCompat;
import com.breakinblocks.aeroportals.util.PortalGeom;
import com.breakinblocks.aeroportals.util.PortalRect;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class BuiltinPortalTypes {
    private BuiltinPortalTypes() {}

    public static void register() {
        AeroPortalsApi.registerPortal(rect("nether", state -> state.is(Blocks.NETHER_PORTAL),
                () -> Blocks.NETHER_PORTAL, PortalTeleport::resolveNether));
        AeroPortalsApi.registerPortal(point("end", () -> Blocks.END_PORTAL,
                state -> state.is(Blocks.END_PORTAL), PortalTeleport::resolveEnd));
        AeroPortalsApi.registerPortal(point("ars_nouveau", ArsNouveauCompat::portalBlock,
                ArsNouveauCompat::isPortalBlock, PortalTeleport::resolveArsNouveau));
        AeroPortalsApi.registerPortal(rect("aether", AetherCompat::isPortalBlock,
                AetherCompat::portalBlock, PortalTeleport::resolveAether));
        AeroPortalsApi.registerPortal(point("draconic", DraconicEvolutionCompat::portalBlock,
                DraconicEvolutionCompat::isPortalBlock, PortalTeleport::resolveDraconic));
        AeroPortalsApi.registerPortal(rect("deeper_darker", DeeperAndDarkerCompat::isPortalBlock,
                DeeperAndDarkerCompat::portalBlock, PortalTeleport::resolveDeeperDarker));
        AeroPortalsApi.registerPortal(points("create_teleporters", CreateTeleportersCompat::portalBlocks,
                CreateTeleportersCompat::isPortalBlock, PortalTeleport::resolveCreateTeleporters));
        AeroPortalsApi.registerPortal(new EnderGatewayType());
    }

    private static AeroPortalType point(String name, Supplier<Block> block, Predicate<BlockState> matcher,
                                        PointResolver resolver) {
        return points(name, oneOf(block), matcher, resolver);
    }

    private static AeroPortalType points(String name, Supplier<Collection<Block>> blocks,
                                         Predicate<BlockState> matcher, PointResolver resolver) {
        ResourceLocation id = AeroPortals.id(name);
        Supplier<Collection<Block>> cached = memoize(blocks);
        return new AeroPortalType() {
            @Override
            public ResourceLocation id() {
                return id;
            }

            @Override
            public boolean isEnabled() {
                return !cached.get().isEmpty();
            }

            @Override
            public Collection<Block> matchedBlocks() {
                return cached.get();
            }

            @Override
            public boolean matches(BlockState state) {
                return matcher.test(state);
            }

            @Override
            public PortalDestination resolve(ServerLevel srcLevel, ServerSubLevel sub, BlockPos hitPos) {
                return resolver.resolve(srcLevel, sub, hitPos);
            }
        };
    }

    private static AeroPortalType rect(String name, Predicate<BlockState> matcher,
                                       Supplier<Block> portalBlock, RectResolver resolver) {
        ResourceLocation id = AeroPortals.id(name);
        Supplier<Collection<Block>> cached = memoize(oneOf(portalBlock));
        return new AeroPortalType() {
            @Override
            public ResourceLocation id() {
                return id;
            }

            @Override
            public boolean isEnabled() {
                return !cached.get().isEmpty();
            }

            @Override
            public Collection<Block> matchedBlocks() {
                return cached.get();
            }

            @Override
            public boolean matches(BlockState state) {
                return matcher.test(state);
            }

            @Override
            public PortalDestination resolve(ServerLevel srcLevel, ServerSubLevel sub, BlockPos hitPos) {
                PortalRect srcRect = measure(srcLevel, hitPos, portalBlock.get(), id);
                return srcRect == null ? null : resolver.resolve(srcLevel, sub, srcRect);
            }
        };
    }

    private static final class EnderGatewayType implements AeroPortalType {
        private static final ResourceLocation ID = AeroPortals.id("ender_gateway");
        private static final Supplier<Collection<Block>> BLOCKS = memoize(oneOf(CreateEnderGatewayCompat::portalBlock));

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public boolean isEnabled() {
            return !BLOCKS.get().isEmpty();
        }

        @Override
        public Collection<Block> matchedBlocks() {
            return BLOCKS.get();
        }

        @Override
        public boolean matches(BlockState state) {
            return CreateEnderGatewayCompat.isPortalBlock(state);
        }

        @Override
        public PortalDestination resolve(ServerLevel srcLevel, ServerSubLevel sub, BlockPos hitPos) {
            PortalRect srcRect = measure(srcLevel, hitPos, CreateEnderGatewayCompat.portalBlock(), ID);
            return srcRect == null ? null : PortalTeleport.resolveEnderGateway(srcLevel, sub, srcRect, hitPos);
        }
    }

    private static Supplier<Collection<Block>> oneOf(Supplier<Block> block) {
        return () -> {
            Block resolved = block.get();
            return resolved == null ? List.of() : List.of(resolved);
        };
    }

    private static Supplier<Collection<Block>> memoize(Supplier<Collection<Block>> blocks) {
        return new Supplier<>() {
            private Collection<Block> resolved;

            @Override
            public Collection<Block> get() {
                Collection<Block> current = resolved;
                if (current != null) return current;
                current = blocks.get();
                if (!current.isEmpty()) resolved = current;
                return current;
            }
        };
    }

    private static PortalRect measure(ServerLevel level, BlockPos hitPos, Block portalBlock, ResourceLocation id) {
        PortalRect rect = portalBlock == Blocks.NETHER_PORTAL
                ? PortalGeom.measureFromBlock(level, hitPos)
                : PortalGeom.measureFromBlock(level, hitPos, portalBlock);
        if (rect == null) {
            AeroPortals.LOGGER.warn("[AeroPortals] {} portal block at {} but rect measurement failed; skipping", id, hitPos);
        }
        return rect;
    }

    @FunctionalInterface
    private interface PointResolver {
        PortalDestination resolve(ServerLevel srcLevel, ServerSubLevel sub, BlockPos hitPos);
    }

    @FunctionalInterface
    private interface RectResolver {
        PortalDestination resolve(ServerLevel srcLevel, ServerSubLevel sub, PortalRect srcRect);
    }
}
