package com.voltcraft.electric.protection;

import net.minecraft.util.StringRepresentable;

/**
 * 接线端子的内部接线状态。对应设计文档第八章接线规范。
 *
 * 端子实体是火/零/地的唯一持有者；电缆/网络对此无感。
 * 不同状态影响：
 * - 是否导电（短路时空开跳闸，电完全不流）
 * - 漏保是否能正常工作（地线未接 → 漏保失效）
 * - 设备是否能正常接收 FE
 */
public enum WiringState implements StringRepresentable {
    /** 火/零/地全部正确连接，正常工作。 */
    CORRECT("correct"),
    /** 火零接反：通电但漏保检测会异常。设计文档 8.2 "漏保误跳或不跳"。 */
    HOT_NEUTRAL_SWAPPED("hot_neutral_swapped"),
    /** 地线未连接：电流正常，但漏保失效（设计文档 4.1 "漏保失效"）。 */
    MISSING_GROUND("missing_ground"),
    /** 火线接到零线端子：直接短路，触发空开（设计文档 4.2）。 */
    SHORT_HOT_NEUTRAL("short_hot_neutral"),
    /** 火线接到地线端子：直接短路。 */
    SHORT_HOT_GROUND("short_hot_ground");

    private final String name;

    WiringState(String name) {
        this.name = name;
    }

    /** 是否构成短路。短路状态下空开应立即跳闸。 */
    public boolean isShort() {
        return this == SHORT_HOT_NEUTRAL || this == SHORT_HOT_GROUND;
    }

    /** 在不考虑短路跳闸的情况下，是否有电流流动。 */
    public boolean conducts() {
        return !isShort();
    }

    /** 漏保能否在该接线下正常工作。 */
    public boolean rcdProtects() {
        return this == CORRECT;
    }

    /** UI 上是否显示为"故障"。 */
    public boolean isFault() {
        return this != CORRECT;
    }

    /** 玩家右键端子 → 循环切换到下一状态（demo/调试用）。 */
    public WiringState next() {
        WiringState[] vs = values();
        return vs[(ordinal() + 1) % vs.length];
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
