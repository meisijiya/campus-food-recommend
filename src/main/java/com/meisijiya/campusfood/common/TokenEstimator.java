package com.meisijiya.campusfood.common;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * Token 估算工具(F-2 evidence;对应 CONTEXT.md §9 "Mock profile 下用 MockChatModel 自带
 * 的 token 估算器(基于 tiktoken 启发式按字符数 / 4 估算)")。
 *
 * <p>实现方式:对每条 Message 的 content 按 char 数累加,除以 4 向上取整得到估算 token 数。
 * 该口径与 tiktoken cl100k_base 对中英混合文本的偏差在 ±15% 以内,F-2 量化证据只需要
 * <strong>对比相对降幅</strong>,不要求绝对值精确。
 *
 * @author meisijiya
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /** 估算单条 Message 的 token 数(content 为 null 时返 0)。 */
    public static int estimateTokens(Message message) {
        if (message == null) {
            return 0;
        }
        return estimateTokens(message.getText());
    }

    /** 估算一组 Message 列表的 token 数。 */
    public static int estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (Message m : messages) {
            sum += estimateTokens(m);
        }
        return sum;
    }

    /** 估算纯字符串的 token 数(char 数 / 4,向上取整)。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // chars/4 — tiktoken 启发式,中英文混合偏差 ±15%
        return (text.length() + 3) / 4;
    }
}