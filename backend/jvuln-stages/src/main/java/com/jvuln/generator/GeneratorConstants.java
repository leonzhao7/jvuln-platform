package com.jvuln.generator;

/**
 * Generator 包常量定义
 *
 * 统一管理 ArtifactGen 阶段相关的配置常量，避免 magic numbers
 */
public final class GeneratorConstants {

    private GeneratorConstants() {
        // Utility class
    }

    // ==================== Agent 循环控制 ====================

    /** Agent 最大轮次数 */
    public static final int MAX_AGENT_TURNS = 80;

    /** 最大审查修订次数 */
    public static final int MAX_REVIEW_REVISIONS = 4;

    /** 最大空响应次数 */
    public static final int MAX_EMPTY_AGENT_RESPONSES = 2;

    /** 无进展最大轮次数 */
    public static final int MAX_NO_PROGRESS_TURNS = 6;

    /** 报告生成回退轮次数 */
    public static final int REPORT_FALLBACK_TURNS = 5;

    // ==================== 端口和网络 ====================

    /** vuln-demo 应用默认端口 */
    public static final int VULN_DEMO_PORT = 18080;

    // ==================== 超时配置 ====================

    /** 编译超时（秒） */
    public static final int COMPILE_TIMEOUT_SECONDS = 120;

    /** 启动等待时间（秒） */
    public static final int STARTUP_WAIT_SECONDS = 30;

    /** 命令执行超时（秒） */
    public static final int COMMAND_TIMEOUT_SECONDS = 60;

    // ==================== 缓冲区大小 ====================

    /** 进程输出缓冲区大小（字节） */
    public static final int PROCESS_OUTPUT_BUFFER_SIZE = 64 * 1024;

    /** 输出截断大小（字节） */
    public static final int OUTPUT_TRUNCATE_SIZE = 4000;

    // ==================== 审查相关 ====================

    /** 审查文件片段大小 */
    public static final int REVIEW_FILE_SNIPPET_SIZE = 2000;

    /** 审查历史项数量 */
    public static final int REVIEW_HISTORY_ITEMS = 6;

    /** 审查继续缓冲 */
    public static final int REVIEW_CONTINUE_BUFFER = 4;

    // ==================== 记忆管理 ====================

    /** 记忆记录限制 */
    public static final int MEMORY_RECORD_LIMIT = 8;

    /** 记忆输出截断大小 */
    public static final int MEMORY_OUTPUT_TRUNCATE_SIZE = 1200;

    // ==================== 上下文管理 ====================

    /** 上下文压缩字符限制 */
    public static final int CONTEXT_COMPACT_CHAR_LIMIT = 90000;

    /** 上下文文件总大小限制 */
    public static final int CONTEXT_FILE_TOTAL_LIMIT = 36000;

    /** 单个上下文文件大小限制 */
    public static final int CONTEXT_FILE_LIMIT = 12000;

    /** 上下文 diff 大小限制 */
    public static final int CONTEXT_DIFF_LIMIT = 16000;

    /** 转录条目大小限制 */
    public static final int TRANSCRIPT_ENTRY_LIMIT = 60000;
}
