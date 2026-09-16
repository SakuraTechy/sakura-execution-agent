package top.continew.sakura.agent.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.support.AgentExecutionException;

/** 对成功 Shell 输出做受限的 Java 正则替换；原值只放入短时变量响应。 */
final class ServerCommandResultProcessor {

    private static final int MAX_VALUE_LENGTH = 4096;
    private static final int MAX_INPUT_BYTES = 64 * 1024;
    private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$");

    private ServerCommandResultProcessor() {
    }

    static void validate(InfrastructureTaskRequest request) throws AgentExecutionException {
        String regex = text(request.replaceRegex());
        String replacement = text(request.replaceValue());
        String name = text(request.variableName()).trim();
        if (regex.length() > 512 || replacement.length() > MAX_VALUE_LENGTH) {
            throw invalid("替换正则最多 512 字符，替换内容最多 4096 字符");
        }
        if (!name.isEmpty() && (!VARIABLE_NAME.matcher(name).matches()
            || List.of("system.", "secret.", "execution.").stream().anyMatch(name::startsWith))) {
            throw new AgentExecutionException("VARIABLE_NAME_INVALID", "全局变量名不合法或使用了保留前缀");
        }
        if (regex.contains("${") || regex.contains("{{") || replacement.contains("${") || replacement.contains("{{")) {
            throw invalid("替换字段不支持平台变量插值或命名组引用");
        }
        if (!regex.isEmpty() && name.isEmpty()) {
            throw invalid("配置结果替换后，请填写全局变量名");
        }
        if (regex.isEmpty() && !replacement.isEmpty()) {
            throw invalid("请先填写替换正则");
        }
        if (!regex.isEmpty()) {
            try {
                validateReplacement(replacement, Pattern.compile(regex).matcher("").groupCount());
            } catch (PatternSyntaxException | StackOverflowError e) {
                throw invalid("替换正则不合法或过于复杂");
            }
        }
    }

    static Map<String, Object> process(InfrastructureTaskRequest request, int exitCode, String stdout,
                                      boolean truncated) throws AgentExecutionException {
        validate(request);
        String name = text(request.variableName()).trim();
        if (exitCode != 0 || name.isEmpty()) {
            return Map.of();
        }
        String value = text(stdout);
        if (truncated || value.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw tooLarge();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new AgentExecutionException("TASK_CANCELLED", "结果处理已取消");
        }
        if (!text(request.replaceRegex()).isEmpty()) {
            try {
                // Matcher 的字符访问共享预算，灾难性回溯不能持续占用任务线程。
                Budget budget = new Budget();
                Matcher matcher = Pattern.compile(request.replaceRegex()).matcher(new BudgetText(value, budget));
                StringBuilder result = new StringBuilder();
                int appendPosition = 0;
                while (matcher.find()) {
                    budget.check();
                    // 一个大捕获组被重复引用也可能放大结果，必须在 appendReplacement 分配前检查。
                    requireResultSize(result.length() + matcher.start() - appendPosition
                        + replacementLength(matcher, text(request.replaceValue()), budget));
                    matcher.appendReplacement(result, text(request.replaceValue()));
                    appendPosition = matcher.end();
                }
                requireResultSize(result.length() + value.length() - appendPosition);
                matcher.appendTail(result);
                value = result.toString();
            } catch (BudgetExceeded | StackOverflowError e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new AgentExecutionException("TASK_CANCELLED", "结果处理已取消");
                }
                throw new AgentExecutionException("SERVER_RESULT_REGEX_LIMIT", "正则处理超过执行预算，请简化表达式");
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                throw new AgentExecutionException("SERVER_RESULT_REPLACEMENT_INVALID", "替换内容或捕获组不合法");
            }
        }
        requireResultSize(value.length());
        return Map.of("variables", Map.of(name, value));
    }

    private static void requireResultSize(int length) throws AgentExecutionException {
        if (length > MAX_VALUE_LENGTH) {
            throw tooLarge();
        }
    }

    private static int replacementLength(Matcher matcher, String replacement, Budget budget) throws AgentExecutionException {
        int length = 0;
        for (int index = 0; index < replacement.length(); index++) {
            budget.check();
            char current = replacement.charAt(index);
            if (current == '\\') {
                index++;
                length++;
            } else if (current == '$') {
                int group = replacement.charAt(++index) - '0';
                while (index + 1 < replacement.length() && isDigit(replacement.charAt(index + 1))) {
                    int next = group * 10 + replacement.charAt(index + 1) - '0';
                    if (next > matcher.groupCount()) {
                        break;
                    }
                    group = next;
                    index++;
                }
                length += matcher.end(group) - matcher.start(group);
            } else {
                length++;
            }
            requireResultSize(length);
        }
        return length;
    }

    private static AgentExecutionException tooLarge() {
        return new AgentExecutionException("SERVER_RESULT_TOO_LARGE", "命令输出已截断、超过 64 KiB 或结果超过 4096 字符");
    }

    private static AgentExecutionException invalid(String message) {
        return new AgentExecutionException("METHOD_CONFIG_INVALID", message);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void validateReplacement(String replacement, int groupCount) throws AgentExecutionException {
        for (int index = 0; index < replacement.length(); index++) {
            char current = replacement.charAt(index);
            if (current == '\\') {
                if (++index == replacement.length()) {
                    throw new AgentExecutionException("SERVER_RESULT_REPLACEMENT_INVALID", "替换内容末尾不能是未转义的反斜杠");
                }
            } else if (current == '$') {
                if (++index == replacement.length() || !isDigit(replacement.charAt(index))) {
                    throw new AgentExecutionException("SERVER_RESULT_REPLACEMENT_INVALID", "替换内容中的 $ 必须引用数字捕获组或转义");
                }
                int group = replacement.charAt(index) - '0';
                if (group > groupCount) {
                    throw new AgentExecutionException("SERVER_RESULT_REPLACEMENT_INVALID", "替换内容引用了不存在的捕获组");
                }
                while (index + 1 < replacement.length() && isDigit(replacement.charAt(index + 1))) {
                    int next = group * 10 + replacement.charAt(index + 1) - '0';
                    if (next > groupCount) {
                        break;
                    }
                    group = next;
                    index++;
                }
            }
        }
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Budget {
        private int remaining = 2_000_000;
        private final long deadline = System.nanoTime() + 250_000_000L;

        void check() {
            if (--remaining < 0 || Thread.currentThread().isInterrupted()
                || (remaining & 255) == 0 && System.nanoTime() > deadline) {
                throw new BudgetExceeded();
            }
        }
    }

    private static final class BudgetText implements CharSequence {
        private final String value;
        private final Budget budget;

        BudgetText(String value, Budget budget) {
            this.value = value;
            this.budget = budget;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            budget.check();
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            budget.check();
            return new BudgetText(value.substring(start, end), budget);
        }

        @Override
        public String toString() {
            budget.check();
            return value;
        }
    }
}
