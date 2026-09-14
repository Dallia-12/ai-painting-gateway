package com.yzc.painting.provider;

/**
 * 供应商结果查询的统一返回。
 *
 * <p>不同供应商的返回形态差异很大：有的给临时 URL，有的给 Base64，
 * 有的用 "SUCCEEDED" 有的用 "finished"。统一收敛到这个结构，
 * 上层业务不感知任何供应商协议细节。
 */
public record ProviderQueryResult(State state,
                                  String imageUrl,
                                  byte[] imageBytes,
                                  String failReason) {

    public enum State {
        RUNNING, SUCCESS, FAILED
    }

    public static ProviderQueryResult running() {
        return new ProviderQueryResult(State.RUNNING, null, null, null);
    }

    public static ProviderQueryResult ofUrl(String url) {
        return new ProviderQueryResult(State.SUCCESS, url, null, null);
    }

    public static ProviderQueryResult ofBytes(byte[] bytes) {
        return new ProviderQueryResult(State.SUCCESS, null, bytes, null);
    }

    public static ProviderQueryResult failed(String reason) {
        return new ProviderQueryResult(State.FAILED, null, null, reason);
    }
}
