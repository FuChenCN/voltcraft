package com.voltcraft.electric;

import net.minecraft.util.StringRepresentable;

/**
 * 三相相位标识。
 *
 * 设计文档（2026-05-19 三相重构决策）：
 *   - LIVE / NEUTRAL：FE 数值平分流过的两根工作线。L→负载→N 形成回路。
 *   - EARTH：保护接地。平时零流量，仅承载漏电流，供 RCD 触发。
 *   - LEGACY：兼容旧硬电缆（单线 FE 模型）。在三相系统中视作合相（L+N 合一）。
 *     新代码不应主动使用 LEGACY，仅由旧 CableBlock 创建。
 */
public enum Phase implements StringRepresentable {
    LIVE("live", "L"),
    NEUTRAL("neutral", "N"),
    EARTH("earth", "E"),
    LEGACY("legacy", "—");

    private final String name;
    private final String shortLabel;

    Phase(String name, String shortLabel) {
        this.name = name;
        this.shortLabel = shortLabel;
    }

    /** 用于 Jade / Tooltip 等场景的单字符标签。 */
    public String shortLabel() {
        return shortLabel;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** L/N 是工作相，需要参与 RCD 差流核算。E/LEGACY 不参与。 */
    public boolean isWorkingPhase() {
        return this == LIVE || this == NEUTRAL;
    }
}
