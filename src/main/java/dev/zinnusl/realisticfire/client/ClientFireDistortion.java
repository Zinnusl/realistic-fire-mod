package dev.zinnusl.realisticfire.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.zinnusl.realisticfire.RealisticFire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;

/**
 * Screen-space heat distortion — captures the world framebuffer once per pass and exposes a
 * RenderType that samples the copy with noise-displaced UVs. Extends RenderType to reach the
 * package-private state-shard classes.
 */
public final class ClientFireDistortion extends RenderType {
    private static final int DISTORTION_BUFFER_SIZE = 65_536;

    // Kill switch — disabled by default because the framebuffer-blit path conflicts with
    // shader-pack pipelines (Iris/Sodium/Veil) and produces yellow billboards or render-thread
    // crashes on those setups. Re-enable once we detect/handle those pipelines properly.
    private static final boolean DISTORTION_ENABLED = false;

    private static ShaderInstance distortionShader;
    private static RenderTarget capturedScene;
    private static boolean frameCaptured;
    private static boolean captureFailureLogged;

    private static final ShaderStateShard DISTORTION_SHADER_SHARD =
            new ShaderStateShard(() -> distortionShader);
    private static final EmptyTextureStateShard CAPTURED_TEXTURE_SHARD =
            new EmptyTextureStateShard(ClientFireDistortion::bindCapturedTexture, () -> {});

    private static final RenderType DISTORTION_TYPE = create(
            RealisticFire.MOD_ID + ":distortion",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            DISTORTION_BUFFER_SIZE,
            false,
            true,
            CompositeState.builder()
                    .setShaderState(DISTORTION_SHADER_SHARD)
                    .setTextureState(CAPTURED_TEXTURE_SHARD)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(NO_LIGHTMAP)
                    .setOverlayState(NO_OVERLAY)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    private ClientFireDistortion(
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
        modEventBus.addListener(ClientFireDistortion::onRegisterShaders);
    }

    private static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            RealisticFire.id("realistic_fire_distortion"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> {
                        distortionShader = shader;
                        RealisticFire.LOGGER.info("Loaded Realistic Fire distortion shader");
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load realistic fire distortion shader", exception);
        }
    }

    public static RenderType distortionType() {
        if (!DISTORTION_ENABLED) {
            return null;
        }
        return distortionShader == null || !frameCaptured ? null : DISTORTION_TYPE;
    }

    /**
     * Copy the current main framebuffer's color buffer into a side texture so the
     * distortion shader can sample the scene without reading the buffer it's also
     * drawing into. Called once per render pass before any distortion quads are submitted.
     */
    public static void captureFrameForDistortion() {
        if (!DISTORTION_ENABLED || distortionShader == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.frameBufferId == -1 || main.width <= 0 || main.height <= 0) {
            return;
        }
        try {
            if (capturedScene == null) {
                capturedScene = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
                capturedScene.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            }
            if (capturedScene.width != main.width || capturedScene.height != main.height) {
                capturedScene.resize(main.width, main.height, Minecraft.ON_OSX);
            }

            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, capturedScene.frameBufferId);
            GlStateManager._glBlitFrameBuffer(
                    0, 0, main.width, main.height,
                    0, 0, capturedScene.width, capturedScene.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            // bindWrite rebinds GL_FRAMEBUFFER (both READ+DRAW targets) so downstream draws are unaffected.
            main.bindWrite(false);
            frameCaptured = true;
        } catch (Throwable throwable) {
            // Iris/Sodium/Veil and certain GPU drivers can fail framebuffer blits — log once and
            // disable rather than crash the render thread or spam the log every frame.
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                RealisticFire.LOGGER.warn("Realistic Fire distortion capture failed; distortion will not render", throwable);
            }
            frameCaptured = false;
        }
    }

    public static void clearFrameFlag() {
        frameCaptured = false;
    }

    private static void bindCapturedTexture() {
        if (capturedScene == null || !frameCaptured) {
            return;
        }
        RenderSystem.setShaderTexture(0, capturedScene.getColorTextureId());
    }
}
