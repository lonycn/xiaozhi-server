package xiaozhi.modules.agent;

/** Formats the role prompt used for a spoken device reply. */
public final class Esp32ReplyLength {
    public static final String STANDARD = "standard";
    public static final String VALID_VALUES = "short|standard|detailed";

    private Esp32ReplyLength() {
    }

    public static String apply(String length, String rolePrompt) {
        String guidance = switch (length == null ? STANDARD : length) {
            case "short" -> """
                    先直接回答问题。默认只说一到两句，尽量不超过四十个汉字，能用一句就用一句。
                    省掉寒暄、铺垫、玩笑、重复总结和例行追问。用户明确要求详细说明时再展开。""";
            case "detailed" -> """
                    先直接给出答案，再说明有用的原因或步骤。复杂问题可用三到五句，简单问题一句即可。
                    用户明确要求简短时就缩短。避免无关内容和重复总结。""";
            default -> null;
        };
        if (guidance == null) {
            return rolePrompt;
        }
        String instruction = "[ESP32 语音回复规则]\n以下规则优先于角色设定中关于回答长短和开场方式的要求。\n"
                + guidance + "\n保留角色的自然口吻。使用适合朗读的纯文本，保留必要的安全提示。";
        return rolePrompt == null || rolePrompt.isBlank() ? instruction : rolePrompt + "\n\n" + instruction;
    }
}
