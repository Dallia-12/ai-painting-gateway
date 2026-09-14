package com.yzc.painting.provider;

import com.yzc.painting.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 供应商路由工厂。
 *
 * <p>把"用哪家供应商"变成配置而不是代码分支：Spring 启动时收集所有
 * {@link ImageProvider} 实现，按 name 建索引，运行时按配置取用。
 */
@Slf4j
@Component
public class ProviderFactory {

    private final Map<String, ImageProvider> providers;
    private final String activeProvider;

    public ProviderFactory(List<ImageProvider> providerList,
                           @Value("${app.provider.active:mock}") String activeProvider) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(ImageProvider::name, Function.identity()));
        this.activeProvider = activeProvider;
        log.info("已注册供应商={}, 当前启用={}", providers.keySet(), activeProvider);
    }

    public ImageProvider current() {
        return byName(activeProvider);
    }

    public ImageProvider byName(String name) {
        ImageProvider provider = providers.get(name);
        if (provider == null) {
            throw new BizException("未注册的供应商: " + name);
        }
        return provider;
    }
}
