package net.craftnet.village;

/** Уровни сотовой связи. */
public enum SignalLevel {
	NONE(0, "—", 0xFF5555),
	G2(1, "2G", 0xFFAA00),
	G3(2, "3G", 0xFFFF55),
	G4(3, "4G", 0x55FF55);

	public final int tier;
	public final String label;
	public final int colorRgb;

	SignalLevel(int tier, String label, int colorRgb) {
		this.tier = tier;
		this.label = label;
		this.colorRgb = colorRgb;
	}

	/** Множитель времени доставки. */
	public int travelMultiplier() {
		return switch (this) {
			case G4 -> 1;
			case G3 -> 2;
			case G2 -> 3;
			case NONE -> 0;
		};
	}

	public static SignalLevel byTier(int t) {
		for (SignalLevel s : values()) if (s.tier == t) return s;
		return NONE;
	}
}
