package net.craftnet.util;

import net.craftnet.jobs.JobManager;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Утилита работы с ◆-тегом рабочего имущества (выдачи крафт-заказов и «добора» у бариста).
 *
 * <p>{@link #is(Object)} принимает {@link Object} специально: весь UI телефона
 * хранит товарные строки как {@code Object[]}, где элемент может быть
 * {@link ItemStack}, строкой из истории (код {@code "TXT\u0001..."}) либо null —
 * и этот метод раньше вызывался на строках без проверки типа. Бросать или
 * падать на «чужом» типе нельзя: для UI это просто «не продаётся как предмет».
 */
public final class JobTag {
    private JobTag() {}

    /** true, если стак помечен как рабочее имущество — его нельзя продавать/выставлять/ставить. */
    public static boolean is(Object stack) {
        if (!(stack instanceof ItemStack st) || st.isEmpty()) return false;
        return JobManager.isJobTagged(st);
    }

    /** Количество «нетронутых» (не ◆) предметов того же вида в инвентаре игрока. */
    public static int countUntagged(ServerPlayerEntity player, ItemStack reference) {
        if (reference == null || reference.isEmpty()) return 0;
        return JobManager.countSellable(player, reference.getItem());
    }

    /**
     * Списывает {@code want} предметов, пропуская ◆-стаки (all-or-nothing).
     * Возвращает сколько реально списано (0 или {@code want}).
     */
    public static int removeUntagged(ServerPlayerEntity player, ItemStack reference, int want) {
        if (want <= 0 || reference == null || reference.isEmpty()) return 0;
        return JobManager.removeSellable(player, reference.getItem(), want) ? want : 0;
    }
}
