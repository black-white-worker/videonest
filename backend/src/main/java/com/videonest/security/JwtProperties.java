package com.videonest.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT配置属性绑定类
 * 作用：读取配置文件中前缀为jwt的所有配置项，封装为Java对象统一使用
 * 并对密钥等关键配置做合法性校验，启动时配置非法直接报错，提前发现问题
 */
@Data
@Validated
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    @NotBlank(message = "JWT 密钥不能为空")
    @Size(min = 32, message = "JWT 密钥至少需要 32 个字符")
    private String secret;

    // 默认值1440分钟 = 24小时，配置文件不赋值时使用该默认值
    private long expireMinutes = 1440;
}
