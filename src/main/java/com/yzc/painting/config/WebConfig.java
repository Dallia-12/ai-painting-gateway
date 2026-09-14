package com.yzc.painting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/** 把本地存储目录映射成可访问的静态资源，方便直接在浏览器里看生成结果 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${storage.local.base-dir:./data/images}")
    private String baseDir;

    @Value("${storage.local.public-prefix:/images}")
    private String publicPrefix;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + Path.of(baseDir).toAbsolutePath() + "/";
        registry.addResourceHandler(publicPrefix + "/**").addResourceLocations(location);
    }
}
