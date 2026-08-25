package net.craftnet.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

import net.craftnet.client.NetHooks;

/**
 * Базовый экран CraftNet: реестр кликабельных регионов + синхронизация данных.
 */
public abstract class CraftNetScreen extends Screen {

	public final String screenId;
	protected NbtCompound data = new NbtCompound();
	private final List<Region> regions = new ArrayList<>();
	protected final List<UiKit.TextInput> inputs = new ArrayList<>();

	protected record Region(int x, int y, int w, int h, Runnable onClick) {}

	protected CraftNetScreen(String screenId, Text title, NbtCompound initial) {
		super(title);
		this.screenId = screenId;
		if (initial != null) this.data = initial;
	}

	public void sync(NbtCompound d) {
		this.data = d;
		onSync(d);
	}

	protected void onSync(NbtCompound d) {}

	/** Регистрирует регион и (если под мышью) вернёт true — вызывать из render. */
	protected boolean clickable(int x, int y, int w, int h, Runnable onClick) {
		regions.add(new Region(x, y, w, h, onClick));
		return false;
	}

	@Override
	public boolean mouseClicked(Click click, boolean focused) {
		double mouseX = click.x();
		double mouseY = click.y();
		for (UiKit.TextInput in : inputs) {
			if (in.mouseDown(mouseX, mouseY)) return true;
		}
		for (int i = regions.size() - 1; i >= 0; i--) {
			Region r = regions.get(i);
			if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
				UiKit.clickSound();
				r.onClick().run();
				return true;
			}
		}
		return super.mouseClicked(click, focused);
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (input.isValidChar()) {
			char chr = (char) input.codepoint();
			for (UiKit.TextInput in : inputs) {
				if (in.focused && in.type(chr)) return true;
			}
		}
		return super.charTyped(input);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		for (UiKit.TextInput in : inputs) {
			if (!in.focused) continue;
			// L6: Ctrl+V — вставка из буфера
			if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_V && ctrlDown()) {
				in.paste();
				return true;
			}
			if (in.key(input.key())) return true;
		}
		return super.keyPressed(input);
	}

	/** Нажат ли Ctrl (левый/правый) — для Ctrl+V в инпутах. */
	protected static boolean ctrlDown() {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc == null || mc.getWindow() == null) return false;
		return net.minecraft.client.util.InputUtil.isKeyPressed(mc.getWindow(),
				net.minecraft.client.util.InputUtil.GLFW_KEY_LEFT_CONTROL)
				|| net.minecraft.client.util.InputUtil.isKeyPressed(mc.getWindow(),
						net.minecraft.client.util.InputUtil.GLFW_KEY_RIGHT_CONTROL);
	}

	/** L4/UX: колесо мыши листает списки — экраны реализуют onScroll. */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (verticalAmount != 0 && onScroll(mouseX, mouseY, verticalAmount)) return true;
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	/** @return true, если колесо обработано (листание страниц и т.п.). */
	protected boolean onScroll(double mx, double my, double dir) {
		return false;
	}

	/**
	 * Свой фон вместо ванильного blur+darkening: Screen.render с 1.20.2
	 * всегда вызывает renderBackground первым — если не переопределить,
	 * наш контент оказался бы ПОД блюром и затемнением.
	 */
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, 0xB0101014);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		regions.clear();
		// super.render рисует фон (наш renderBackground) и виджеты, затем — наш контент
		super.render(context, mouseX, mouseY, delta);
		renderContent(context, mouseX, mouseY, delta);
		for (UiKit.TextInput in : inputs) {
			in.render(context, this.textRenderer);
		}
	}

	protected abstract void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta);

	protected void send(String action, NbtCompound args) {
		NetHooks.sendAction(screenId, action, args == null ? new NbtCompound() : args);
	}

	@Override
	public void close() {
		NetHooks.sendAction(screenId, "close", new NbtCompound());
		super.close();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	protected static NbtCompound sub(NbtCompound n, String k) {
		return n.getCompound(k).orElseGet(NbtCompound::new);
	}

	protected static List<NbtCompound> rows(NbtCompound n, String k) {
		java.util.List<NbtCompound> out = new java.util.ArrayList<>();
		net.minecraft.nbt.NbtList l = n.getListOrEmpty(k);
		for (int i = 0; i < l.size(); i++) {
			if (l.get(i) instanceof NbtCompound c) out.add(c);
		}
		return out;
	}

	protected static String str(NbtCompound n, String k) {
		return n.getString(k, "");
	}

	protected static int i(NbtCompound n, String k) {
		return n.getInt(k, 0);
	}

	protected static long lng(NbtCompound n, String k) {
		return n.getLong(k, 0L);
	}

	protected static double dbl(NbtCompound n, String k) {
		return n.getDouble(k, 0.0);
	}
}
