package day29;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;

/**
 * Day 29: OTel SDK 初始化
 *
 * 使用 LoggingSpanExporter 把 span 打印到控制台，方便本地学习观察。
 * 生产上换成 OtlpGrpcSpanExporter 推到 Jaeger/Tempo 即可。
 */
public class TracingConfig {

    public static final String INSTRUMENTATION_NAME = "java-agent-study";

    private static final OpenTelemetry openTelemetry = buildOpenTelemetry();

    private static OpenTelemetry buildOpenTelemetry() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    public static Tracer getTracer() {
        return openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }
}
