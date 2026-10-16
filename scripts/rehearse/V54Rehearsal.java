import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

/**
 * V54（公司归属快照）上线预演：在一个**副本库**上走真实 Flyway 路径，量出窗口需要的时间。
 *
 * 这不是产品代码，是发布工具（见 docs/p4a-rollout-runbook.md）。用 `scripts/rehearse-v54.sh` 跑。
 *
 * 为什么用 Flyway 而不是直接执行 V54 的 SQL：上线时跑迁移的是应用启动期的 Flyway，
 * 不是驱动层的多语句。原子性、顺序、失败后的历史表状态都取决于它
 * （`D-20261010-p4a-rollout-is-a-maintenance-window-and-null-has-two-sources`）。
 *
 * 它只做两件事，**故意不做逐语句归因**：
 *   1. 完整 `Flyway.migrate()`（V53 → V54）掐总耗时 —— 这是窗口时长要的那个数；
 *   2. migrate 期间采样锁等待。
 * 逐语句计时会绕过 Flyway 事务，还要解析 `DO $$` 和多行 SQL，脆且不是权威数字。
 */
public final class V54Rehearsal {

    private static final String PROD_DB_NAME = "antflow";
    private static final String MARKER_TABLE = "rehearsal_marker";
    private static final String REQUIRED_VERSION = "53";
    private static final long SAMPLE_MS = 250;

    public static void main(String[] args) throws Exception {
        // Java 17 在 Windows 上按控制台代码页编码 stdout，中文在 UTF-8 终端里会变乱码。
        System.setOut(new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
            java.nio.charset.StandardCharsets.UTF_8));
        Options options = Options.parse(args);
        String url = options.jdbc;

        try (Connection conn = DriverManager.getConnection(url, options.user, options.password)) {
            banner(conn, url);
            guard(conn, options);
            String serverVersion = databaseVersion(conn);
            requireAtV53(conn, serverVersion);
            printScale(conn);
            duplicateFormData(conn, options.force);
            printLockContext(conn);

            timedMigrate(url, options);

            reportAfter(conn);
            if (options.oldImageCheck) {
                oldImageCheck(conn, url, options);
            }
            System.out.println("\n[done] 把这组数字连同上面的行数一起记进发布记录（窗口长度按它估）。");
        }
    }

    // ── 安全闸：靠边界，不靠约定 ────────────────────────────────────────────────

    private static void banner(Connection conn, String url) throws SQLException {
        System.out.println("=== V54 上线预演 ===");
        System.out.println("jdbc url            : " + url);
        System.out.println("current_database()  : " + str(conn, "SELECT current_database()"));
        System.out.println("current_user        : " + str(conn, "SELECT current_user"));
        System.out.println("inet_server_addr()  : " + str(conn, "SELECT coalesce(inet_server_addr()::text, 'local/socket')"));
        System.out.println("inet_server_port()  : " + str(conn, "SELECT coalesce(inet_server_port()::text, '-')"));
        System.out.println("version()           : " + str(conn, "SELECT version()"));
        System.out.println();
    }

    private static void guard(Connection conn, Options options) throws SQLException {
        String db = str(conn, "SELECT current_database()");
        if (PROD_DB_NAME.equalsIgnoreCase(db)) {
            refuse("库名是 `" + PROD_DB_NAME + "`（生产/本地栈的库名）。这个工具会**真的执行迁移**，"
                + "不接受任何 flag 绕过。请在一个副本库上跑。");
        }
        if (!options.confirm) {
            refuse("缺少 --confirm。这个工具会在目标库上真实执行 Flyway 迁移，"
                + "确认目标是副本库后加 --confirm。");
        }
        // 比库名约定硬的凭据：副本库必须带 marker 表（runbook 的克隆步骤会建）。
        long markers;
        try {
            markers = scalar(conn,
                "SELECT count(*) FROM " + MARKER_TABLE);
        } catch (SQLException e) {
            refuse("找不到 `" + MARKER_TABLE + "` 表。这是「证明它是副本」的凭据，不是命名习惯："
                + "克隆步骤必须建它，见 runbook「生产量级快照进副本库」一节。（原始错误：" + e.getMessage() + "）");
            return;
        }
        if (markers < 1) {
            refuse("`" + MARKER_TABLE + "` 表存在但没有行。请插入一行说明这份副本的来源。");
        }
        System.out.println("[闸] 库名 / --confirm / marker 表 三项通过（marker 行数=" + markers + "）");
    }

    private static String databaseVersion(Connection conn) {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_setting('server_version')")) {
            return rs.next() ? rs.getString(1) : "unknown";
        } catch (SQLException ignored) {
            // 版本探测属辅助信息，失败不影响主流程
            return "unknown";
        }
    }

    /**
     * 版本判定**按 Flyway 自己的顺序**（installed_rank 最后一行），不用 `MAX(version)`：
     * `flyway_schema_history.version` 是 varchar，`MAX` 会得出 `'9' > '53'` 这种错误结论。
     */
    private static void requireAtV53(Connection conn, String dbVersion) throws SQLException {
        long failed;
        try {
            failed = scalar(conn, "SELECT count(*) FROM flyway_schema_history WHERE success = false");
        } catch (SQLException e) {
            refuse("没有 flyway_schema_history（这个库没跑过迁移？）。预演的对象应该是**已经到 V53** 的副本库。");
            return;
        }
        if (failed > 0) {
            refuse("flyway_schema_history 里有 " + failed + " 行 success=false。"
                + "先弄清那是什么，不要在带失败记录的库上预演。");
        }
        String last = str(conn,
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1");
        System.out.println("[闸] 迁移历史最后一行 version=" + last + "（服务端 " + dbVersion + "）");
        if (!REQUIRED_VERSION.equals(last)) {
            refuse("迁移历史最后一行是 `" + last + "`，不是 `" + REQUIRED_VERSION + "`。"
                + "预演要把 V54 跑一遍，所以副本必须先停在 V53。"
                + "（若它已经在 V54，说明这份副本已经被迁移过，请重新克隆。）");
        }
    }

    // ── 输入规模：这组耗时代表什么规模，必须被证明 ──────────────────────────────

    private static void printScale(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("[规模] 迁移前的行数（拿去和源库对，对不上就别用这组耗时定窗口）：");
        for (String table : List.of("t_process_instance", "t_form_data", "t_task")) {
            System.out.printf("  %-20s %,d%n", table, scalar(conn, "SELECT count(*) FROM " + table));
        }
        System.out.printf("  %-20s %s%n", "t_process_instance 表大小",
            str(conn, "SELECT pg_size_pretty(pg_total_relation_size('t_process_instance'))"));
        System.out.printf("  %-20s %s%n", "t_form_data 表大小",
            str(conn, "SELECT pg_size_pretty(pg_total_relation_size('t_form_data'))"));
    }

    /** 复跑 V54 自己的 pre-flight（V54:30-32）。列出 offender，不是只说"有"。 */
    private static void duplicateFormData(Connection conn, boolean force) throws SQLException {
        List<String> offenders = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT form_data_id, count(*) AS n FROM t_process_instance "
                     + "GROUP BY form_data_id HAVING count(*) > 1 ORDER BY 2 DESC, 1")) {
            while (rs.next()) {
                offenders.add("form_data_id=" + rs.getLong(1) + " 挂 " + rs.getLong(2) + " 个实例");
            }
        }
        System.out.println();
        if (offenders.isEmpty()) {
            System.out.println("[预检] 重复 form_data_id：无。V54 的 pre-flight 不会中止。");
            return;
        }
        System.out.println("[预检] 重复 form_data_id：**" + offenders.size() + " 组**，V54 会中止：");
        offenders.stream().limit(50).forEach(o -> System.out.println("  - " + o));
        if (offenders.size() > 50) {
            System.out.println("  ... 还有 " + (offenders.size() - 50) + " 组");
        }
        if (!force) {
            refuse("先人工处理上面这些重复挂载（一份 form_data 挂多个实例时归属无法判定），"
                + "再回来预演。确实只想测「V54 会中止」的形态，才加 --force。");
        }
        System.out.println("[预检] --force：已确认接受 V54 会中止，继续。");
    }

    /** ALTER 的 ACCESS EXCLUSIVE 会不会等：看有没有 idle in transaction 的长会话。 */
    private static void printLockContext(Connection conn) throws SQLException {
        System.out.println();
        System.out.println("[预检] 当前未授予的锁: " + scalar(conn, "SELECT count(*) FROM pg_locks WHERE NOT granted"));
        long idle = scalar(conn,
            "SELECT count(*) FROM pg_stat_activity "
                + "WHERE state = 'idle in transaction' AND pid <> pg_backend_pid()");
        System.out.println("[预检] 其他 idle-in-transaction 会话: " + idle
            + (idle > 0 ? "  ← 这些会让 ALTER 的 ACCESS EXCLUSIVE 一直等，先清掉" : ""));
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT pid, now() - xact_start AS age, left(coalesce(query, ''), 90) AS q "
                     + "FROM pg_stat_activity WHERE state = 'idle in transaction' "
                     + "AND pid <> pg_backend_pid() ORDER BY xact_start LIMIT 10")) {
            while (rs.next()) {
                System.out.println("    pid=" + rs.getLong(1) + " 事务已开 " + rs.getString(2) + "  query=" + rs.getString(3));
            }
        }
    }

    // ── 计时 + 锁采样 ────────────────────────────────────────────────────────────

    private static void timedMigrate(String url, Options options) throws Exception {
        System.out.println();
        System.out.println("[跑] 完整 Flyway migrate（V53 → V54），锁采样每 " + SAMPLE_MS + "ms 一次 ...");

        LockSampler sampler = new LockSampler(url, options.user, options.password);
        Thread thread = new Thread(sampler, "lock-sampler");
        thread.setDaemon(true);
        thread.start();

        long started = System.nanoTime();
        String failure = null;
        try {
            var result = Flyway.configure()
                .dataSource(url, options.user, options.password)
                .locations(options.migrations)
                .load()
                .migrate();
            System.out.println("[跑] migrationsExecuted=" + result.migrationsExecuted);
        } catch (Throwable t) {
            // V54 失败不是工具的崩溃，是一个要报告的**结果**：迁移整体回滚，库与迁移前一致。
            failure = firstLine(t);
            System.out.println("[跑] **V54 中止**：" + failure);
            System.out.println("[跑] 预期：库的逻辑状态与迁移前一致（Flyway 会整体回滚，历史表不留 V54 行）——"
                + "下面的事后报告应当印证这一点。");
        } finally {
            sampler.stop();
            thread.join(5_000);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        System.out.println("[锁] 采样次数=" + sampler.samples.get()
            + "  未授予锁峰值=" + sampler.ungrantedPeak.get()
            + "  等待 Lock 的会话峰值=" + sampler.lockWaitPeak.get());
        if (sampler.longestWait.get() != null) {
            System.out.println("[锁] 等待最久的语句: " + sampler.longestWait.get());
        }
        if (sampler.ungrantedPeak.get() == 0 && sampler.lockWaitPeak.get() == 0) {
            System.out.println("[锁] 全程没采到锁等待（停机重启时应用已停，这是预期结果）");
        }
        System.out.printf("[计时] 完整 Flyway migrate: %,d ms (%.1f s)%n",
            elapsedMs, elapsedMs / 1000.0);
        if (failure != null) {
            System.out.println("[结果] 本次是**中止**路径，不计入窗口时长估算。");
        }
    }

    private static final class LockSampler implements Runnable {
        final AtomicInteger samples = new AtomicInteger();
        final AtomicInteger ungrantedPeak = new AtomicInteger();
        final AtomicInteger lockWaitPeak = new AtomicInteger();
        final AtomicReference<String> longestWait = new AtomicReference<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final String url;
        private final String user;
        private final String password;

        LockSampler(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        void stop() {
            running.set(false);
        }

        @Override
        public void run() {
            try (Connection c = DriverManager.getConnection(url, user, password)) {
                while (running.get()) {
                    int ungranted = (int) scalar(c, "SELECT count(*) FROM pg_locks WHERE NOT granted");
                    int waiting = (int) scalar(c,
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'");
                    samples.incrementAndGet();
                    ungrantedPeak.accumulateAndGet(ungranted, Math::max);
                    lockWaitPeak.accumulateAndGet(waiting, Math::max);
                    if (waiting > 0 && longestWait.get() == null) {
                        longestWait.set(str(c,
                            "SELECT left(coalesce(query, ''), 160) FROM pg_stat_activity "
                                + "WHERE wait_event_type = 'Lock' LIMIT 1"));
                    }
                    Thread.sleep(SAMPLE_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (SQLException e) {
                // 采样是尽力而为，不该影响迁移本身
                System.out.println("[锁] 采样连接出错（忽略）：" + e.getMessage());
            }
        }
    }

    // ── 事后 ────────────────────────────────────────────────────────────────────

    private static void reportAfter(Connection conn) throws SQLException {
        System.out.println();
        long columns = scalar(conn,
            "SELECT count(*) FROM information_schema.columns WHERE table_name IN"
                + " ('t_process_instance','t_form_data') AND column_name IN"
                + " ('company_id','company_inferred')");
        System.out.println("[事后] 4 个新列: " + columns + " / 4");
        System.out.println("[事后] 2 个索引: " + scalar(conn,
            "SELECT count(*) FROM pg_indexes WHERE indexname IN"
                + " ('ix_process_instance_company','ix_form_data_company')") + " / 2");
        System.out.println("[事后] flyway_schema_history 的 V54 行: " + scalar(conn,
            "SELECT count(*) FROM flyway_schema_history WHERE version = '54' AND success = true"));

        if (columns < 4) {
            // 中止路径的正常形态：迁移整体回滚，列根本没加上。别再去查它，那样只会抛"字段不存在"。
            System.out.println("[事后] 归属盘点：跳过 —— company_id 列不存在，说明迁移没生效、整体回滚了。");
            System.out.println("       这正是中止路径应有的样子（与迁移前的逻辑状态一致）。");
            return;
        }

        System.out.println("[事后] 归属盘点（与 runbook §4 同一口径）：");
        System.out.println("  t_process_instance.company_id IS NULL      : " + scalar(conn,
            "SELECT count(*) FROM t_process_instance WHERE company_id IS NULL"));
        System.out.println("  t_process_instance 按 company_inferred 拆 : "
            + str(conn, "SELECT coalesce(string_agg(coalesce(company_inferred::text,'?') || '=' || n, '  '),"
                + " '(表是空的)') FROM ("
                + "SELECT company_inferred, count(*) AS n FROM t_process_instance"
                + " GROUP BY company_inferred ORDER BY 1) t"));
        System.out.println("  t_form_data.company_id IS NULL            : " + scalar(conn,
            "SELECT count(*) FROM t_form_data WHERE company_id IS NULL"));
        System.out.println("  历史草稿（DRAFT 且无实例且 NULL）        : " + scalar(conn,
            "SELECT count(*) FROM t_form_data fd WHERE fd.status = 'DRAFT' AND fd.company_id IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM t_process_instance pi WHERE pi.form_data_id = fd.id)"));
    }

    /**
     * 旧镜像能不能配 V54 schema 起：把 Flyway 的 locations 换成**只有 V1..V53** 的目录。
     * 那个目录由本方法自己生成（拷 + 排除 V54），不依赖手工准备 —— 早先的版本依赖一个手建的目录，
     * 那是不可复现的。
     */
    private static void oldImageCheck(Connection conn, String url, Options options) throws Exception {
        System.out.println();
        System.out.println("[旧镜像] 用只含 V1..V53 的 locations 再跑一次 Flyway（模拟旧镜像的 classpath）...");
        Path dir = Files.createTempDirectory("antflow-oldmig-");
        try {
            Path source = Paths.get(options.migrations.replaceFirst("^filesystem:", ""));
            int copied = 0;
            try (Stream<Path> files = Files.list(source)) {
                for (Path file : files.filter(f -> f.getFileName().toString().matches("V\\d+__.*\\.sql")).toList()) {
                    String name = file.getFileName().toString();
                    if (name.startsWith("V54__")) {
                        continue;
                    }
                    Files.copy(file, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
            System.out.println("[旧镜像] 临时 locations 里 " + copied + " 个迁移（已排除 V54）：" + dir);
            var old = Flyway.configure()
                .dataSource(url, options.user, options.password)
                .locations("filesystem:" + dir.toAbsolutePath().toString().replace('\\', '/'))
                .load();
            try {
                old.validate();
                System.out.println("[旧镜像] validate() -> OK（Flyway 把历史表里那条 V54 当作 future migration 容忍）");
            } catch (Throwable t) {
                System.out.println("[旧镜像] validate() -> **THREW**: " + firstLine(t));
            }
            try {
                var res = old.migrate();
                System.out.println("[旧镜像] migrate() -> OK, migrationsExecuted=" + res.migrationsExecuted);
            } catch (Throwable t) {
                System.out.println("[旧镜像] migrate() -> **THREW**: " + firstLine(t));
            }
        } finally {
            deleteRecursively(dir);
        }
        System.out.println("[旧镜像] 注意：这只证了 Flyway 那道门。Hibernate `ddl-auto: validate` 那格见 runbook §5.2。");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────────

    private static void refuse(String message) {
        System.out.println();
        System.out.println("[拒绝] " + message);
        System.exit(2);
    }

    private static String firstLine(Throwable t) {
        String m = t.getMessage() == null ? t.toString() : t.getMessage();
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String str(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static final class Options {
        String jdbc;
        String user;
        String password;
        boolean confirm;
        boolean force;
        boolean oldImageCheck;
        String migrations;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--jdbc" -> o.jdbc = args[++i];
                    case "--user" -> o.user = args[++i];
                    case "--password" -> o.password = args[++i];
                    case "--migrations" -> o.migrations = args[++i];
                    case "--confirm" -> o.confirm = true;
                    case "--force" -> o.force = true;
                    case "--old-image-check" -> o.oldImageCheck = true;
                    default -> {
                        System.out.println("未知参数: " + args[i]);
                        usage();
                    }
                }
            }
            if (o.migrations == null) {
                o.migrations = "filesystem:" + Paths.get("backend", "src", "main", "resources", "db", "migration")
                    .toAbsolutePath().toString().replace('\\', '/');
            }
            if (o.jdbc == null || o.user == null || o.password == null) {
                usage();
            }
            return o;
        }

        static void usage() {
            System.out.println("""

                用法:
                  bash scripts/rehearse-v54.sh --jdbc <url> --user <u> --password <p> --confirm
                    [--old-image-check] [--force] [--migrations filesystem:<dir>]

                目标库必须是**副本**：库名不能是 antflow、必须带 rehearsal_marker 表、必须停在 V53。
                它会在目标库上真实执行 Flyway 迁移。""");
            System.exit(2);
        }
    }

    private V54Rehearsal() {
    }
}
