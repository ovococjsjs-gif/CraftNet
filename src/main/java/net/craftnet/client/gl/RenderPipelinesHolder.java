package net.craftnet.client.gl;

import net.minecraft.client.gl.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;

/** Тонкий фасад — чтобы легко править, если константа переименуется. */
public final class RenderPipelinesHolder {
	private RenderPipelinesHolder() {}

	public static RenderPipeline guiTextured() {
		return RenderPipelines.GUI_TEXTURED;
	}
}
