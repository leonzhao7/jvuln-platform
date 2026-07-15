package com.jvuln.pipeline;

/**
 * Pipeline 相关常量定义
 *
 * 避免魔法值，提高代码可维护性
 */
public final class PipelineConstants {

    private PipelineConstants() {
        // 工具类，禁止实例化
    }

    /**
     * Pipeline 总阶段数
     */
    public static final int TOTAL_STAGES = 4;

    /**
     * Stage 编号：情报收集
     */
    public static final int STAGE_INTELLIGENCE = 1;

    /**
     * Stage 编号：补丁分析
     */
    public static final int STAGE_PATCH_ANALYSIS = 2;

    /**
     * Stage 编号：AI 推理
     */
    public static final int STAGE_AI_REASONING = 3;

    /**
     * Stage 编号：制品生成
     */
    public static final int STAGE_ARTIFACTS = 4;

    /**
     * 各阶段名称（用于显示）
     */
    public static final String[] STAGE_NAMES = {
        "Intelligence Collection",
        "Patch Analysis",
        "AI Reasoning",
        "Artifacts Generation"
    };

    /**
     * SSE 连接超时时间（毫秒）
     * 默认 30 分钟
     */
    public static final long SSE_TIMEOUT_MS = 1_800_000L;

    /**
     * 第一个阶段的编号
     */
    public static final int FIRST_STAGE = 1;

    /**
     * 获取阶段名称
     *
     * @param stageNum 阶段编号 (1-4)
     * @return 阶段名称，编号无效时返回 "Unknown"
     */
    public static String getStageName(int stageNum) {
        if (stageNum >= FIRST_STAGE && stageNum <= TOTAL_STAGES) {
            return STAGE_NAMES[stageNum - 1];
        }
        return "Unknown";
    }

    /**
     * 验证阶段编号是否有效
     *
     * @param stageNum 阶段编号
     * @return 是否有效
     */
    public static boolean isValidStage(int stageNum) {
        return stageNum >= FIRST_STAGE && stageNum <= TOTAL_STAGES;
    }
}
