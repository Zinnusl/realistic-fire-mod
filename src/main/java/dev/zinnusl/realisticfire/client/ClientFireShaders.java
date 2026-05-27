package dev.zinnusl.realisticfire.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.zinnusl.realisticfire.RealisticFire;

import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;
import java.util.function.Function;

/**
 * Registers the shader pass used by the custom fire renderer.
 *
 * <p>The renderer falls back to vanilla translucent render types until the
 * resource reload completes, so startup and shader reload failures do not leave
 * the client without visible fire.</p>
 */
public final class ClientFireShaders extends RenderType {
    private static final int FIRE_BUFFER_SIZE = 262_144;

    private static ShaderInstance realisticFireShader;
    private static ShaderInstance scorchShader;
    private static ShaderInstance emberShader;

    private static final ShaderStateShard REALISTIC_FIRE_SHADER = new ShaderStateShard(() -> realisticFireShader);
    private static final ShaderStateShard SCORCH_SHADER = new ShaderStateShard(() -> scorchShader);
    private static final ShaderStateShard EMBER_SHADER = new ShaderStateShard(() -> emberShader);
    private static final Function<ResourceLocation, RenderType> FLAME_TYPES =
            Util.memoize(texture -> createFireType("realistic_fire_flame", texture, ADDITIVE_TRANSPARENCY, true));
    private static final Function<ResourceLocation, RenderType> SMOKE_TYPES =
            Util.memoize(texture -> createFireType("realistic_fire_smoke", texture, TRANSLUCENT_TRANSPARENCY, true));
    // Glowing ember overlay — custom additive shader that tiles the ember-spark texture by world
    // position (fract), drawn as the same oval as the ash so embers track the fire front.
    private static final Function<ResourceLocation, RenderType> EMBER_TYPES =
            Util.memoize(ClientFireShaders::createEmberType);
    // Ash ground mask: opaque (no blend) — the custom scorch shader discards fragments the fire
    // front hasn't reached, revealing the ash like a wiped-away mask. Tiles via world-space UV.
    private static final Function<ResourceLocation, RenderType> SCORCH_TYPES =
            Util.memoize(ClientFireShaders::createScorchType);

    private ClientFireShaders(
            String name,
            VertexFormat format,
            VertexFormat.Mode mode,
            int bufferSize,
            boolean affectsCrumbling,
            boolean sortOnUpload,
            Runnable setupState,
            Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    public static void register(IEventBus modEventBus) {
        RealisticFire.LOGGER.debug("Registering Realistic Fire client shader listener");
        modEventBus.addListener(ClientFireShaders::onRegisterShaders);
    }

    public static RenderType flame(ResourceLocation texture) {
        if (realisticFireShader == null) {
            return RenderType.entityTranslucentEmissive(texture);
        }
        return FLAME_TYPES.apply(texture);
    }

    public static RenderType smoke(ResourceLocation texture) {
        if (realisticFireShader == null) {
            return RenderType.entityTranslucent(texture);
        }
        return SMOKE_TYPES.apply(texture);
    }

    // Ash ground: a custom mask shader that reveals opaque ash behind the fire front (discards
    // un-reached fragments). Falls back to a plain translucent type until the shader is loaded.
    public static RenderType scorch(ResourceLocation texture) {
        if (scorchShader == null) {
            return RenderType.entityTranslucent(texture);
        }
        return SCORCH_TYPES.apply(texture);
    }

    public static RenderType embers(ResourceLocation texture) {
        if (emberShader == null) {
            return RenderType.entityTranslucentEmissive(texture);
        }
        return EMBER_TYPES.apply(texture);
    }

    private static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            RealisticFire.id("realistic_fire"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> {
                        realisticFireShader = shader;
                        RealisticFire.LOGGER.info("Loaded Realistic Fire client shader {}", RealisticFire.id("realistic_fire"));
                    });
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            RealisticFire.id("realistic_fire_scorch"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> {
                        scorchShader = shader;
                        RealisticFire.LOGGER.info("Loaded Realistic Fire scorch shader {}", RealisticFire.id("realistic_fire_scorch"));
                    });
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            RealisticFire.id("realistic_fire_ember"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> {
                        emberShader = shader;
                        RealisticFire.LOGGER.info("Loaded Realistic Fire ember shader {}", RealisticFire.id("realistic_fire_ember"));
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load realistic fire shader", exception);
        }
    }

    private static RenderType createFireType(
            String name,
            ResourceLocation texture,
            TransparencyStateShard transparency,
            boolean sortOnUpload) {
        CompositeState state = CompositeState.builder()
                .setShaderState(REALISTIC_FIRE_SHADER)
                .setTextureState(new TextureStateShard(texture, false, false))
                .setTransparencyState(transparency)
                .setCullState(NO_CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .setOutputState(TRANSLUCENT_TARGET)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false);

        return create(
                RealisticFire.MOD_ID + ":" + name,
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                FIRE_BUFFER_SIZE,
                true,
                sortOnUpload,
                state);
    }

    // Opaque ash-ground type driven by the custom scorch mask shader. NO blending: the shader
    // discards fragments the fire front hasn't reached, so kept fragments are written fully opaque
    // (a hard masked reveal). Writes depth so it sits cleanly on the grass it covers.
    private static RenderType createScorchType(ResourceLocation texture) {
        CompositeState state = CompositeState.builder()
                .setShaderState(SCORCH_SHADER)
                .setTextureState(new TextureStateShard(texture, false, false))
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setLightmapState(NO_LIGHTMAP)
                .setOverlayState(NO_OVERLAY)
                .setWriteMaskState(COLOR_DEPTH_WRITE)
                .createCompositeState(false);

        return create(
                RealisticFire.MOD_ID + ":realistic_fire_scorch",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                FIRE_BUFFER_SIZE,
                false,
                false,
                state);
    }

    // Additive ember-oval type driven by the custom ember mask shader (world-tiled spark texture).
    // ADDITIVE so sparks glow over the ash; no depth write so it never occludes the flames above.
    private static RenderType createEmberType(ResourceLocation texture) {
        CompositeState state = CompositeState.builder()
                .setShaderState(EMBER_SHADER)
                .setTextureState(new TextureStateShard(texture, false, false))
                .setTransparencyState(ADDITIVE_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setLightmapState(NO_LIGHTMAP)
                .setOverlayState(NO_OVERLAY)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false);

        return create(
                RealisticFire.MOD_ID + ":realistic_fire_ember",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                FIRE_BUFFER_SIZE,
                false,
                false,
                state);
    }
}
