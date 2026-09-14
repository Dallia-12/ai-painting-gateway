package com.yzc.painting.storage;

import com.yzc.painting.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 本地文件实现，让项目在没有任何云存储凭证的情况下也能完整运行。
 * 生产环境替换为 S3/OBS 实现即可，上层业务无需改动。
 */
@Slf4j
@Component
public class LocalFileStorage implements ObjectStorage {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${storage.local.base-dir:./data/images}")
    private String baseDir;

    @Value("${storage.local.public-prefix:/images}")
    private String publicPrefix;

    @Override
    public String save(byte[] content, String objectKey) {
        try {
            Path target = Path.of(baseDir, objectKey);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return publicPrefix + "/" + objectKey;
        } catch (IOException e) {
            throw new BizException("保存文件失败: " + objectKey, e);
        }
    }

    @Override
    public String transfer(String remoteUrl, String objectKey) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(remoteUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new BizException("下载远端图片失败, status=" + response.statusCode());
            }
            try (InputStream in = response.body()) {
                return save(in.readAllBytes(), objectKey);
            }
        } catch (IOException e) {
            throw new BizException("下载远端图片异常: " + remoteUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("下载被中断: " + remoteUrl, e);
        }
    }
}
