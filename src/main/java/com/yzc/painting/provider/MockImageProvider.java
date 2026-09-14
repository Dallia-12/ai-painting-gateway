package com.yzc.painting.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 本地模拟供应商：无需任何外部 API Key 即可完整跑通链路。
 *
 * <p>刻意模拟真实 AI 服务的三个特性：
 * <ol>
 *   <li>耗时长——固定延迟后才返回结果，用于验证异步化与轮询</li>
 *   <li>结果乱序——每个子请求延迟随机，多图任务的完成顺序不确定</li>
 *   <li>偶发失败——按配置概率返回失败，用于验证重试与部分成功</li>
 * </ol>
 */
@Slf4j
@Component
public class MockImageProvider implements ImageProvider {

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Value("${app.provider.mock.min-latency-seconds:5}")
    private int minLatencySeconds;

    @Value("${app.provider.mock.max-latency-seconds:15}")
    private int maxLatencySeconds;

    @Value("${app.provider.mock.fail-rate:0.0}")
    private double failRate;

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String submit(ProviderSubmitRequest request) {
        String requestId = "mock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int latency = ThreadLocalRandom.current().nextInt(minLatencySeconds, maxLatencySeconds + 1);
        boolean willFail = ThreadLocalRandom.current().nextDouble() < failRate;
        jobs.put(requestId, new Job(Instant.now(), latency, willFail, request));
        log.info("[mock] 已受理子请求 requestId={}, 预计 {}s 后出图, slot={}", requestId, latency, request.slotIndex());
        return requestId;
    }

    @Override
    public ProviderQueryResult query(String providerRequestId) {
        Job job = jobs.get(providerRequestId);
        if (job == null) {
            return ProviderQueryResult.failed("供应商侧无此请求: " + providerRequestId);
        }
        if (Duration.between(job.submittedAt, Instant.now()).getSeconds() < job.latencySeconds) {
            return ProviderQueryResult.running();
        }
        if (job.willFail) {
            return ProviderQueryResult.failed("模拟生成失败");
        }
        try {
            return ProviderQueryResult.ofBytes(render(job.request, providerRequestId));
        } catch (IOException e) {
            return ProviderQueryResult.failed("图片编码失败: " + e.getMessage());
        }
    }

    /** 画一张带提示词的占位图，让演示看得见结果 */
    private byte[] render(ProviderSubmitRequest request, String requestId) throws IOException {
        int size = 512;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int hue = Math.abs(requestId.hashCode()) % 360;
        g.setColor(Color.getHSBColor(hue / 360f, 0.45f, 0.85f));
        g.fillRect(0, 0, size, size);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.drawString("slot #" + request.slotIndex(), 28, 60);
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        String prompt = request.prompt();
        g.drawString(prompt.length() > 34 ? prompt.substring(0, 34) + "..." : prompt, 28, 96);
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.drawString(requestId, 28, size - 28);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private record Job(Instant submittedAt, int latencySeconds, boolean willFail, ProviderSubmitRequest request) {
    }
}
