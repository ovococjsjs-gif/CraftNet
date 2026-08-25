package net.craftnet.util;

import net.minecraft.nbt.NbtCompound;

/**
 * Хелперы над NbtCompound 1.21.11 (Optional-геттеры, копии).
 * ВАЖНО: getCompound отдаёт КОПИЮ — после мутации подкомпаунд
 * обязательно кладём обратно через put().
 */
public final class Nbt2 {
	private Nbt2() {}

	public static NbtCompound sub(NbtCompound parent, String key) {
		return parent.getCompound(key).orElseGet(NbtCompound::new);
	}

	public static long lng(NbtCompound n, String key) {
		return n.getLong(key, 0L);
	}

	public static int i(NbtCompound n, String key) {
		return n.getInt(key, 0);
	}

	public static double dbl(NbtCompound n, String key) {
		return n.getDouble(key, 0.0);
	}

	public static String str(NbtCompound n, String key) {
		return n.getString(key, "");
	}

	public static boolean bool(NbtCompound n, String key) {
		return n.getInt(key, 0) != 0;
	}
}
