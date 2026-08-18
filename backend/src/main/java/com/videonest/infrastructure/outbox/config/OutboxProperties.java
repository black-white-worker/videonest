package com.videonest.infrastructure.outbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox 配置属性实体类
 * 作用：映射application.yml/application.properties中前缀为outbox的配置项
 * 把配置文件里的字符串配置自动封装为Java对象，供程序代码读取使用
 */
// 绑定yml中outbox: 下面的所有配置，prefix = "outbox"对应yml最外层key
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    private boolean enabled = true;
    private long dispatchIntervalMilliseconds = 1000;

    // 外部类获取是否开启outbox的状态
    public boolean isEnabled() {
        return enabled;
    }

    // SpringBoot配置绑定反射赋值必须依赖setter，缺少会导致yml配置无法注入
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // 获取定时任务轮询数据库的间隔时长
    public long getDispatchIntervalMilliseconds() {
        return dispatchIntervalMilliseconds;
    }

    // 框架反射注入配置值使用
    public void setDispatchIntervalMilliseconds(long dispatchIntervalMilliseconds) {
        this.dispatchIntervalMilliseconds = dispatchIntervalMilliseconds;
    }
}
