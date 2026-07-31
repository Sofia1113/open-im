package com.openim.server.observability;

import com.openim.server.support.AbstractPostgresIntegrationTest;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警契约测试
 *
 * <b>告警规则引用一个不存在的指标名时，它不会报错——它只是永远不触发。</b>
 * 这是监控系统最危险的失败模式：仪表盘一片绿，因为查询压根没匹配到任何序列。
 * 一次指标重命名、一次 Micrometer 版本升级改变了命名转换规则，都能让整套告警
 * 悄无声息地失效，而没有任何迹象表明出了问题。
 *
 * 因此这里把 {@code deploy/observability/prometheus-rules.yaml} 里引用的每一个
 * {@code openim_*} 指标名，与 {@code /actuator/prometheus} 实际输出的指标做比对。
 * 改了指标名而忘了改告警规则（或反过来），这个测试会失败。
 *
 * <b>不校验的部分</b>：{@code http_server_requests_*}、{@code hikaricp_*}、
 * {@code jvm_*} 由 Spring Boot 与 Micrometer 提供，它们的存在性不是本项目的契约；
 * {@code openim:*} 记录规则是 Prometheus 侧计算出来的，不出现在应用输出里。
 */
@SpringBootTest
class AlertingContractTest extends AbstractPostgresIntegrationTest {

    /** 从仓库根向上找，测试的工作目录是 apps/server */
    private static final Path RULES_FILE =
            Path.of("..", "..", "deploy", "observability", "prometheus-rules.yaml");

    private static final Path DASHBOARD_FILE =
            Path.of("..", "..", "deploy", "observability", "grafana-dashboard.json");

    /** 匹配 openim_ 开头的指标名。Prometheus 输出里的名字已经过 micrometer 的 . → _ 转换 */
    private static final Pattern OPENIM_METRIC = Pattern.compile("\\b(openim_[a-z0-9_]+)\\b");

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void everyOpenImMetricReferencedByAlertRulesActuallyExists() throws Exception {
        Set<String> exported = exportedMetricNames();
        Set<String> referenced = openImMetricsIn(RULES_FILE);

        assertThat(referenced)
                .as("告警规则里应当引用到 openim_* 指标，读不到说明路径或解析有问题")
                .isNotEmpty();

        List<String> missing = referenced.stream()
                .filter(name -> !matchesExported(name, exported))
                .sorted()
                .toList();

        assertThat(missing)
                .as("""
                        这些指标被 prometheus-rules.yaml 引用，但应用并没有暴露它们。
                        引用不存在的指标不会报错，只会让对应告警永远不触发。
                        要么修正规则里的名字，要么补上缺失的指标。
                        当前实际暴露的 openim 指标见 /actuator/prometheus。
                        """)
                .isEmpty();
    }

    @Test
    void everyOpenImMetricReferencedByTheDashboardActuallyExists() throws Exception {
        Set<String> exported = exportedMetricNames();
        Set<String> referenced = openImMetricsIn(DASHBOARD_FILE);

        assertThat(referenced).isNotEmpty();

        List<String> missing = referenced.stream()
                .filter(name -> !matchesExported(name, exported))
                .sorted()
                .toList();

        assertThat(missing)
                .as("这些指标被 grafana-dashboard.json 引用但不存在，对应面板会一直空白")
                .isEmpty();
    }

    /**
     * 降级相关指标必须存在。
     *
     * 这几个是 Redis 故障期间唯一的可观测信号——就绪探针已经刻意不再包含 Redis，
     * 所以运维完全依赖它们判断服务处于降级状态。少了任何一个，Redis 故障就是静默的。
     */
    @Test
    void degradationSignalsAreExported() throws Exception {
        Set<String> exported = exportedMetricNames();

        assertThat(exported).contains("openim_redis_available");
        assertThat(matchesExported("openim_redis_availability_transitions_total", exported))
                .as("Redis 抖动检测指标缺失")
                .isTrue();
        assertThat(matchesExported("openim_rate_limit_degraded_total", exported))
                .as("限流降级指标缺失，Redis 故障期间无法判断配额是否还跨实例共享")
                .isTrue();
        assertThat(matchesExported("openim_realtime_stream_publish_failures_total", exported))
                .as("事件流发布失败指标缺失，跨实例投递故障将不可见")
                .isTrue();
    }

    /**
     * Micrometer 的 Prometheus 输出会给 Counter 加 {@code _total} 后缀，
     * 但不同版本对已带后缀的名字处理不同。两种形式都接受，避免测试与版本细节耦合。
     */
    private static boolean matchesExported(String name, Set<String> exported) {
        if (exported.contains(name)) {
            return true;
        }
        if (name.endsWith("_total")) {
            return exported.contains(name.substring(0, name.length() - "_total".length()));
        }
        return exported.contains(name + "_total");
    }

    /**
     * 从 MeterRegistry 读取已注册的指标名，并按 Prometheus 的命名约定转换。
     *
     * 不走 {@code /actuator/prometheus}：{@code micrometer-registry-prometheus} 是
     * runtime 依赖，测试代码编译期引用不到 {@code PrometheusMeterRegistry}；
     * 而 MockMvc 环境下 actuator 的 web 端点也未必映射。直接读注册表更稳定，
     * 且测的正是同一份数据——Prometheus 导出器就是遍历这个注册表生成输出的。
     *
     * 转换规则（对应 micrometer 的 PrometheusNamingConvention）：
     * <ul>
     *   <li>{@code .} → {@code _}：{@code openim.redis.available} → {@code openim_redis_available}</li>
     *   <li>Counter 追加 {@code _total}</li>
     * </ul>
     */
    private Set<String> exportedMetricNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Meter meter : meterRegistry.getMeters()) {
            String base = meter.getId().getName().replace('.', '_');
            names.add(base);
            if (meter.getId().getType() == Meter.Type.COUNTER) {
                names.add(base + "_total");
            }
        }
        return names;
    }

    private static Set<String> openImMetricsIn(Path file) throws IOException {
        String content = Files.readString(file);
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = OPENIM_METRIC.matcher(content);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        names.addAll(found);
        return names;
    }
}
