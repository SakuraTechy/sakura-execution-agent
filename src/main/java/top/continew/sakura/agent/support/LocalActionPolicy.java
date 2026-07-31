package top.continew.sakura.agent.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本机动作的文件系统边界。
 *
 * <p>所有可读、可删、可上传的本机文件都必须是已有的绝对规范化路径，并且严格位于专用 workspace
 * 或显式配置的 allow root 下。这里在解析真实路径后再次判断，避免符号链接跳出授权目录。</p>
 */
public final class LocalActionPolicy {

    public static final String WORKSPACE_PROPERTY = "sakura.agent.workspace";
    public static final String ALLOW_ROOTS_PROPERTY = "sakura.agent.file-allow-roots";
    public static final String RUNTIME_PROPERTY_ALLOWLIST = "sakura.agent.runtime-property-allowlist";

    private final Path workspaceRoot;
    private final List<Path> allowedRoots;

    public LocalActionPolicy(Path workspaceRoot, List<Path> configuredRoots) {
        this.workspaceRoot = prepareWorkspace(workspaceRoot);
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(this.workspaceRoot);
        if (configuredRoots != null) {
            for (Path root : configuredRoots) {
                roots.add(resolveConfiguredRoot(root));
            }
        }
        this.allowedRoots = List.copyOf(roots);
    }

    /**
     * 缺省 workspace 固定为 Agent 进程目录下的 {@code workspace}，而不是当前目录、用户目录或磁盘根。
     * 这样旧部署不配置该参数时也不会把整个安装目录暴露给本机文件动作。
     */
    public static LocalActionPolicy fromSystemProperties() {
        String configuredWorkspace = System.getProperty(WORKSPACE_PROPERTY);
        Path workspace = isBlank(configuredWorkspace)
            ? Path.of(System.getProperty("user.dir", "."), "workspace")
            : Path.of(configuredWorkspace);
        return new LocalActionPolicy(workspace, parseConfiguredRoots(System.getProperty(ALLOW_ROOTS_PROPERTY)));
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /**
     * 运行时属性默认全部拒绝；部署时只能用 {@code profile:key} 或 {@code key} 精确列入白名单。
     * 禁止通配符，避免 JDBC 密码等敏感属性被“读取配置”步骤意外导出。
     */
    public boolean permitsRuntimeProperty(String profile, String propertyKey) {
        String normalizedKey = propertyKey == null ? "" : propertyKey.trim();
        String normalizedProfile = profile == null ? "" : profile.trim().toLowerCase();
        if (normalizedKey.isBlank() || normalizedKey.contains("*") || normalizedKey.contains("${")) {
            return false;
        }
        for (String configured : System.getProperty(RUNTIME_PROPERTY_ALLOWLIST, "").split("[,;]")) {
            String candidate = configured.trim();
            if (candidate.equals(normalizedKey) || candidate.equalsIgnoreCase(normalizedProfile + ":" + normalizedKey)) {
                return true;
            }
        }
        return false;
    }

    /** 解析存在的本机文件或目录，拒绝变量表达式、非绝对路径、根目录和 allow root 本身。 */
    public Path resolveExistingAllowedPath(String rawPath, String actionName) throws AgentExecutionException {
        Path normalized = normalizeRequestedPath(rawPath, actionName);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentExecutionException("HOST_FILE_NOT_FOUND", actionName + " 指定的本机路径不存在");
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new AgentExecutionException("HOST_PATH_SYMLINK_REJECTED", actionName + " 不允许直接操作符号链接");
        }
        final Path realPath;
        try {
            realPath = normalized.toRealPath();
        } catch (IOException exception) {
            throw new AgentExecutionException("HOST_PATH_UNRESOLVABLE", actionName + " 指定的本机路径无法安全解析", exception);
        }
        ensureStrictlyInsideAllowedRoot(realPath, actionName);
        return realPath;
    }

    /** 本机命令的工作目录同样只能位于 workspace 或 allow root 内。 */
    public Path resolveWorkingDirectory(String rawPath) throws AgentExecutionException {
        if (isBlank(rawPath)) {
            return workspaceRoot;
        }
        Path path = resolveExistingAllowedPath(rawPath, "host_command 工作目录");
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentExecutionException("HOST_WORKING_DIRECTORY_INVALID", "host_command 工作目录必须是目录");
        }
        return path;
    }

    private Path normalizeRequestedPath(String rawPath, String actionName) throws AgentExecutionException {
        if (isBlank(rawPath)) {
            throw new AgentExecutionException("HOST_PATH_REQUIRED", actionName + " 缺少本机路径");
        }
        String value = rawPath.trim();
        if (containsVariableExpression(value) || value.indexOf('\u0000') >= 0 || value.indexOf('*') >= 0 || value.indexOf('?') >= 0) {
            throw new AgentExecutionException("HOST_PATH_EXPRESSION_REJECTED", actionName + " 路径不能包含变量表达式或通配符");
        }
        final Path raw;
        try {
            raw = Path.of(value);
        } catch (Exception exception) {
            throw new AgentExecutionException("HOST_PATH_INVALID", actionName + " 路径格式非法", exception);
        }
        if (!raw.isAbsolute()) {
            throw new AgentExecutionException("HOST_PATH_NOT_ABSOLUTE", actionName + " 仅接受绝对路径");
        }
        if (containsParentSegment(raw)) {
            throw new AgentExecutionException("HOST_PATH_NOT_NORMALIZED", actionName + " 路径不能包含上级目录片段");
        }
        Path normalized = raw.toAbsolutePath().normalize();
        if (!normalized.equals(raw.toAbsolutePath())) {
            throw new AgentExecutionException("HOST_PATH_NOT_NORMALIZED", actionName + " 路径必须已规范化");
        }
        if (isUnsafeRoot(normalized)) {
            throw new AgentExecutionException("HOST_PATH_ROOT_REJECTED", actionName + " 不允许使用文件系统根目录或用户主目录");
        }
        return normalized;
    }

    private void ensureStrictlyInsideAllowedRoot(Path candidate, String actionName) throws AgentExecutionException {
        for (Path root : allowedRoots) {
            if (candidate.startsWith(root) && !candidate.equals(root)) {
                return;
            }
        }
        throw new AgentExecutionException("HOST_PATH_NOT_ALLOWED", actionName + " 路径不在 Agent 允许目录内");
    }

    private Path prepareWorkspace(Path workspace) {
        if (workspace == null) {
            throw new IllegalStateException("未配置安全 workspace");
        }
        Path normalized = workspace.toAbsolutePath().normalize();
        if (isUnsafeRoot(normalized)) {
            throw new IllegalStateException("sakura.agent.workspace 不能配置为文件系统根目录或用户主目录");
        }
        try {
            Files.createDirectories(normalized);
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
                throw new IllegalStateException("sakura.agent.workspace 必须是非符号链接目录");
            }
            return normalized.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化 sakura.agent.workspace", exception);
        }
    }

    private Path resolveConfiguredRoot(Path root) {
        if (root == null || !root.isAbsolute()) {
            throw new IllegalStateException("sakura.agent.file-allow-roots 只能配置绝对目录");
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (containsParentSegment(root) || isUnsafeRoot(normalized)) {
            throw new IllegalStateException("sakura.agent.file-allow-roots 不能包含根目录、用户主目录或上级目录片段");
        }
        try {
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
                throw new IllegalStateException("sakura.agent.file-allow-roots 必须是已存在的非符号链接目录");
            }
            return normalized.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("无法解析 sakura.agent.file-allow-roots", exception);
        }
    }

    private boolean isUnsafeRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = normalized.getRoot();
        if (root != null && normalized.equals(root)) {
            return true;
        }
        Path userHome = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        return normalized.equals(userHome);
    }

    private static List<Path> parseConfiguredRoots(String rawRoots) {
        if (isBlank(rawRoots)) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String part : rawRoots.split("[;,]")) {
            if (!isBlank(part)) {
                paths.add(Path.of(part.trim()));
            }
        }
        return paths;
    }

    private boolean containsParentSegment(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsVariableExpression(String path) {
        return path.startsWith("~") || path.contains("${") || path.contains("{{") || path.contains("%")
            || path.matches(".*\\$[A-Za-z_][A-Za-z0-9_]*.*");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
