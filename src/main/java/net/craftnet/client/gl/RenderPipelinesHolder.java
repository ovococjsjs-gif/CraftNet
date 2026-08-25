package net.craftnet.client.gl;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;

/** Тонкий фасад — чтобы легко править, если константа переименуется. */
public final class RenderPipelinesHolder {
	private RenderPipelinesHolder() {}

	public static RenderPipeline guiTextured() {
		return RenderPipelines.GUI_TEXTURED;
	}
}
