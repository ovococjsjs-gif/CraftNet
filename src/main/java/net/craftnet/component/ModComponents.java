package net.craftnet.component;

import com.mojang.serialization.Codec;
import net.minecraft.component.ComponentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

import net.craftnet.CraftNet;

public final class ModComponents {
	private ModComponents() {}

	/** Номинал банкноты в CR. */
	public static final ComponentType<Integer> BANKNOTE_VALUE = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			CraftNet.id("banknote_value"),
			ComponentType.<Integer>builder().codec(Codec.INT).build());

	/** Метка «рабочего материала»: строка вида "ownerUuid:type". */
	public static final ComponentType<String> JOB_TAG = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			CraftNet.id("job_tag"),
			ComponentType.<String>builder().codec(Codec.STRING).build());

	public static void register() {
		// статическая инициализация
	}
}
