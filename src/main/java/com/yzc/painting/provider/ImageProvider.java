package com.yzc.painting.provider;

/**
 * 图片生成供应商的统一抽象（Strategy）。
 *
 * <p>新接入一家供应商只需实现本接口并注册为 Bean，
 * {@link ProviderFactory} 会自动纳入路由，核心业务流程无需改动。
 */
public interface ImageProvider {

    /** 供应商标识，与配置 app.provider.active 对应 */
    String name();

    /**
     * 提交生成请求，立刻返回供应商侧的子请求ID（不等待出图）。
     * 内部任务ID与这个ID分开存储，两者解耦。
     */
    String submit(ProviderSubmitRequest request);

    /** 查询子请求当前状态 */
    ProviderQueryResult query(String providerRequestId);
}
