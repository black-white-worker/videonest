package com.videonest.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 配置属性绑定类。
 *
 * <p>令牌分成两层：
 * <ul>
 *   <li>access token：短生命周期（默认 15 分钟），只放在前端内存，靠 tokenVersion 撤销；</li>
 *   <li>refresh token：不透明随机串（默认 7 天），存在 HttpOnly Cookie 与服务端 Redis，可随时删除。</li>
 * </ul>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    @NotBlank(message = "JWT 密钥不能为空")
    @Size(min = 32, message = "JWT 密钥至少需要 32 个字符")
    private String secret;

    /** access token 有效期（分钟）。默认 15 分钟，不再使用一天以上的长令牌。 */
    @Min(value = 1, message = "access token 有效期至少 1 分钟")
    @Max(value = 720, message = "access token 有效期不能超过 720 分钟")
    private long expireMinutes = 15;

    /** refresh token 有效期（天）。 */
    @Min(value = 1, message = "refresh token 有效期至少 1 天")
    @Max(value = 90, message = "refresh token 有效期不能超过 90 天")
    private long refreshExpireDays = 7;

    /** refresh Cookie 名。 */
    private String refreshCookieName = "videonest_refresh";

    /** refresh Cookie 是否只走 HTTPS。本地 http 调试置 false，线上必须 true。 */
    private boolean cookieSecure = false;

    /** refresh Cookie SameSite 策略，默认 Lax 即可阻断跨站 POST 携带。 */
    private String cookieSameSite = "Lax";

    /**
     * 是否对每个请求额外查询 jti 黑名单。
     *
     * <p>默认关闭：access token 只有 15 分钟，撤销由 tokenVersion 负责，
     * 正常请求因此不再为“检查是否登出”付一次 Redis 往返。
     * 打开后会保留旧的秒级单令牌撤销能力，代价是每请求一次 Redis 查询。
     */
    private boolean revocationCheckEnabled = false;
}
