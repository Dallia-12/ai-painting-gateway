package com.yzc.painting.storage;

/**
 * 对象存储抽象。
 *
 * <p>供应商给的地址通常是有时效的临时链接，直接存库会导致历史结果不可访问，
 * 因此所有结果都必须转存到自有存储后再落库稳定地址。
 */
public interface ObjectStorage {

    /** 保存字节流，返回可访问的稳定地址 */
    String save(byte[] content, String objectKey);

    /** 从远端地址下载后转存，返回自有存储地址 */
    String transfer(String remoteUrl, String objectKey);
}
