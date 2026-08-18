package io.github.weiyongzenqi.unuplayer.danmaku

import io.github.weiyongzenqi.unuplayer.util.Crypto

/**
 * 弹弹play X-Signature 签名。
 *
 * 算法: `base64(sha256(AppId + Timestamp + Path + AppSecret))`
 * 字符串按顺序直接拼接, 无分隔符。
 *
 * @param appId      应用 ID
 * @param timestamp  Unix 时间戳(秒, 整数)
 * @param path       API 路径, 以 `/` 开头, **不含协议/域名/query 参数**
 *                   (如访问 `https://api.dandanplay.net/api/v2/comment/123?withRelated=true`
 *                   时, path = `/api/v2/comment/123`)
 * @param appSecret  应用密钥
 */
fun dandanplaySignature(
    appId: String,
    timestamp: Long,
    path: String,
    appSecret: String,
): String = Crypto.sha256Base64(appId + timestamp + path + appSecret)
