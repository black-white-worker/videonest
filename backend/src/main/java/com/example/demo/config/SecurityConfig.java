package com.example.demo.config;

import com.example.demo.security.JwtAuthenticationFilter;
import com.example.demo.security.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * SpringSecurity 安全核心配置类
 * 适配前后端分离 + JWT无登录态架构
 */
/**
 * securityFilterChain：请求鉴权主干
 * 配置接口 URL 访问权限（放行 / 登录 / 管理员角色）、关闭 CSRF、无状态 JWT 模式、插入 JWT 解析过滤器、开启跨域支持；
 * corsConfigurationSource：跨域详细规则 Bean
 * 定义允许哪些前端地址、请求方式、请求头、支持携带 Token 凭证
 * */


@Configuration//开启 web 安全拦截
@EnableWebSecurity//支持方法上权限注解 @PreAuthorize("hasRole('ADMIN')")
@EnableMethodSecurity//读取 yml 中的 jwt 秘钥、过期时间
@EnableConfigurationProperties(JwtProperties.class)//配置类
public class SecurityConfig {

    // 自定义JWT校验过滤器，构造注入
    /**作用和字段注入一样@Autowired
      private JwtAuthenticationFilter jwtAuthenticationFilter;
    * */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * 安全过滤链Bean，核心配置
     * 定义所有请求的认证规则、跨域、session策略、过滤器顺序
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                // 关闭 CSRF，前后端分离项目使用 JWT，不使用 Session 表单登录
                .csrf(AbstractHttpConfigurer::disable)

                // 开启跨域，使用下方自定义的CorsConfigurationSource
                .cors(Customizer.withDefaults())

                // JWT 项目不使用 Session
                //不创建Session，依靠请求头携带JWT完成身份认证
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                // 请求权限规则配置
                .authorizeHttpRequests(auth -> auth

                        // 登录注册和健康检查，不需要登录
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/health"
                        ).permitAll()

                        /*
                        * requestMatchers匹配的请求路径
                        * permitAll()直接放行
                        * */
                        // 首页分类和视频浏览，不需要登录
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/categories",
                                "/api/videos",
                                "/api/videos/**"
                        ).permitAll()

                        // 上传封面、上传视频：只要求已经登录
                        .requestMatchers("/api/files/**")
                        .authenticated()//不区分角色只要登录就行

                        // 视频投稿：只要求已经登录
                        .requestMatchers("/api/creator/**")
                        .authenticated()

                        /*.hasRole("ADMIN")必须登录 并且 用户拥有 ADMIN 角色*/
                        // 管理员审核接口：必须是 ADMIN 角色
                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")

                        // 其他接口默认必须登录
                        .anyRequest().authenticated()
                )

                // 在用户名密码过滤器之前解析 JWT
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    /**
     * 自定义跨域配置Bean
     * 允许前端Vue地址跨域访问后端接口
     */

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        //跨域规则实体类
        CorsConfiguration config = new CorsConfiguration();

        // 允许的前端域名
        config.setAllowedOrigins(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173"
        ));

        // 允许的请求方式
        config.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "DELETE",
                "OPTIONS"
        ));

        //允许所有请求头header
        config.setAllowedHeaders(List.of("*"));

        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        //后端全部接口统一启用这套跨域放行规则
        source.registerCorsConfiguration("/**", config);

        return source;
    }
}