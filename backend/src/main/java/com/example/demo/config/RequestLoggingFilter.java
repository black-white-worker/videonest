package com.example.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路追踪过滤器
 * 功能：生成traceId（追踪ID），打印HTTP请求起止日志，实现日志链路追踪
 * 给每一条 HTTP 请求分配唯一编号 traceId，
 * 让同一次请求产生的所有日志都带上这个编号，方便检索整条调用链路日志
 */
/**
 * 请求进入过滤器后先输出请求开始日志，随后执行 filterChain.doFilter() 阻塞等待业务处理，业务正常执行或抛出异常都会进入 finally 块打印请求结束日志。
 * 请求开始、请求结束是两条独立日志，依靠 MDC 绑定的 traceId，就能将同一次请求的所有日志相互关联。
 * */

@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    // 请求头名称，用来传递追踪ID
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * OncePerRequestFilter：保证一次请求只会执行一次过滤器逻辑
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // 尝试从请求头获取上游传递的traceId
        String traceId = request.getHeader(TRACE_ID_HEADER);

        // 如果没有传入traceId，则本地生成全新唯一ID
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        long startedAt = System.nanoTime();
        // 把traceId放入映射诊断上下文MDC，日志框架自动携带该字段输出
        //  MDC 是一个绑定在「当前线程」上的小型 Map 容器。
        //  往里面放 key=traceId，value=xxx，当前线程所有日志打印时，自动带上这个值，不用每次打日志手动传参。
        MDC.put("traceId", traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            // 请求进入时打印日志：请求方式、地址、客户端IP
            log.info(
                    "HTTP 请求开始，method={}，uri={}，remoteAddress={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getRemoteAddr()
            );
            // 放行请求，继续执行后续过滤器、Controller业务代码
            filterChain.doFilter(request, response);
        } finally {
            long durationMilliseconds =
                    (System.nanoTime() - startedAt) / 1_000_000;
            // 请求结束日志：响应状态码、耗时
            log.info(
                    "HTTP 请求结束，method={}，uri={}，status={}，durationMs={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMilliseconds
            );
            // 清除MDC数据，防止线程池复用导致traceId错乱、污染下一次请求
            MDC.remove("traceId");
        }
    }
}
