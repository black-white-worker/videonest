package com.videonest.security;

import com.videonest.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SpringSecurity 安全工具类
 * 作用：统一封装获取当前登录用户的静态方法，项目各处直接调用，避免重复代码
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static LoginUser getCurrentUser() {
        // 从Security全局上下文获取本次请求的认证对象Authentication，里面存放登录认证信息
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        // authentication为null 说明没有认证信息
        // principal不是LoginUser类型 说明未登录/匿名访问
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new BusinessException(401, "请先登录");
        }

        return loginUser;
    }
}