package com.openim.server.observability;

import com.openim.server.support.AbstractPostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 延迟必须以 histogram 发布
 *
 * <b>锁定一个实测发现的配置缺失。</b>
 *
 * Spring Boot 默认把 {@code http_server_requests_seconds} 发布为 <b>summary</b>
 * （只有 count / sum / max），<b>不含任何 bucket</b>。而 {@code histogram_quantile()}
 * 需要 {@code http_server_requests_seconds_bucket}。缺了它，以下全部静默失效：
 *
 * <ul>
 *   <li>SLO 里的延迟目标（p99 &lt; 500ms）</li>
 *   <li>Grafana 的"延迟分位数"与"最慢的接口"两个面板</li>
 *   <li>{@code OpenIMHighLatency} 告警</li>
 *   <li>{@code openim:request_latency_p99_5m} / {@code p50_5m} 记录规则</li>
 * </ul>
 *
 * 这些的 PromQL 语法完全正确、{@code promtool check rules} 通过、
 * Prometheus 加载无错——<b>只是永远返回空</b>。
 * {@link AlertingContractTest} 也发现不了，因为它按<b>名字</b>比对指标，
 * 而 {@code http_server_requests_seconds} 这个名字在 summary 形式下同样存在。
 *
 * 缺失是用真实 Prometheus 求值记录规则时才暴露的
 * （见 {@code deploy/observability/verify-rules.sh}）。
 *
 * <b>为什么用 RANDOM_PORT</b>：{@code http.server.requests} 这个 Timer 只在
 * 第一次真实 HTTP 请求发生时才注册。MockMvc 不经过 Servlet 容器的观测链路，
 * 拿不到它——必须起真实容器并打一个请求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpLatencyHistogramTest extends AbstractPostgresIntegrationTest {

    /** SLO 定义的延迟目标，必须有专属 bucket */
    private static final double SLO_LATENCY_SECONDS = 0.5;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void generateOneRequest() {
        // Timer 在首次请求时注册。打一个不需要认证的端点即可。
        restTemplate.getForEntity("/actuator/health", String.class);
    }

    @Test
    void httpLatencyIsPublishedAsHistogramNotSummary() {
        Timer httpTimer = meterRegistry.find("http.server.requests").timer();

        assertThat(httpTimer)
                .as("http.server.requests 计时器不存在——前置请求可能没打成功")
                .isNotNull();

        assertThat(httpTimer.takeSnapshot().histogramCounts())
                .as("必须发布 histogram bucket，否则 p99 无法计算。"
                        + "需要 management.metrics.distribution.percentiles-histogram。"
                        + "缺失时延迟 SLO、延迟告警、延迟面板全部静默失效——"
                        + "表达式语法正确、Prometheus 不报错，只是永远返回空。")
                .isNotEmpty();
    }

    /**
     * SLO 边界必须落在 bucket 上。
     *
     * 默认 bucket 是对数分布的，500ms 附近的间隔不足以支撑一个 500ms 的 SLO——
     * p99 会在相邻两个 bucket 之间线性插值得出，误差可能到几十毫秒。
     * 配置 {@code distribution.slo} 让 Micrometer 在这些点生成精确 bucket。
     */
    @Test
    void sloBoundaryHasADedicatedBucket() {
        Timer httpTimer = meterRegistry.find("http.server.requests").timer();
        assertThat(httpTimer).isNotNull();

        // 必须指定 TimeUnit。CountAtBucket.bucket() 的无参版本返回 Timer 的
        // 基础单位——对 Timer 来说是**纳秒**，于是 500ms 的 bucket 是 5.0E8。
        // 断言时不转换会得到一个看起来毫无关系的数字（写这个测试时就是这样失败的），
        // 而 Prometheus 输出的是秒（le="0.5"）。用秒断言与线上表示一致。
        double[] boundariesSeconds = Arrays.stream(httpTimer.takeSnapshot().histogramCounts())
                .mapToDouble(bucket -> bucket.bucket(TimeUnit.SECONDS))
                .toArray();

        assertThat(boundariesSeconds)
                .as("SLO 边界 %.1fs 应有专属 bucket，否则该点的 p99 由插值得出。"
                        + "当前 bucket（秒）：%s",
                        SLO_LATENCY_SECONDS, Arrays.toString(boundariesSeconds))
                .contains(SLO_LATENCY_SECONDS);
    }

    /**
     * bucket 数量必须有界。
     *
     * histogram 的每个 bucket 都是一条时间序列，而 {@code http.server.requests}
     * 已经带 uri/method/status/outcome 四个标签——bucket 数乘上标签组合数就是
     * 总序列数。不设 {@code minimum/maximum-expected-value} 时 Micrometer 会生成
     * 覆盖纳秒到分钟的完整对数刻度（数十个 bucket），序列数轻易上万。
     *
     * 这条断言防的是"为了精度不断加 bucket"最终把 Prometheus 打爆。
     */
    @Test
    void bucketCountIsBounded() {
        Timer httpTimer = meterRegistry.find("http.server.requests").timer();
        assertThat(httpTimer).isNotNull();

        int bucketCount = httpTimer.takeSnapshot().histogramCounts().length;

        assertThat(bucketCount)
                .as("bucket 数量应当有界——每个 bucket × 每个标签组合都是一条序列。"
                        + "通过 minimum/maximum-expected-value 收窄范围。")
                .isLessThanOrEqualTo(40);
    }
}
