package com.yzc.painting.dto;

/**
 * 单个图片槽位的展示状态。
 * 未出图时 url 为 null，progress 是推算值；出图后 progress 固定 100。
 */
public record ImageView(int slotIndex, String url, int progress) {
}
