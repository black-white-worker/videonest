package com.videonest.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 【Web 层测试】验证全局异常处理器返回的 HTTP 状态码。
 */
class GlobalExceptionHandlerHttpStatusTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void businessExceptionUsesRealHttpStatus() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("资源不存在"));
    }

    /**
     * 路由未匹配：白名单放行但 MVC 没有 handler 的路径必须是 404，不能落到兜底 500。
     * 这条用例锁住的就是 /api/health 这类“白名单里挂了没实现的路径”的错误码语义。
     */
    @Test
    void noResourceFoundUsesNotFoundInsteadOfInternalError() throws Exception {
        mockMvc.perform(get("/test/no-handler"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("接口不存在"));
    }

    /**
     * Test-only controller fixture used to exercise the exception handling path.
     */
    @RestController
    static class FailureController {

        @GetMapping("/test/not-found")
        void notFound() {
            throw new BusinessException(404, "资源不存在");
        }

        @GetMapping("/test/no-handler")
        void noHandler() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/api/health", "No static resource");
        }
    }
}
