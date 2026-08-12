package com.videonest.security;

import com.videonest.config.SecurityConfig;
import com.videonest.config.JacksonConfig;
import com.videonest.module.video.controller.AdminVideoController;
import com.videonest.module.video.service.VideoResourceCleanupService;
import com.videonest.module.video.service.VideoService;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.category.mapper.VideoCategoryMapper;
import com.videonest.module.user.mapper.SysUserMapper;
import com.videonest.module.notification.mapper.NotificationMapper;
import com.videonest.module.follow.mapper.UserFollowMapper;
import com.videonest.module.interaction.mapper.VideoLikeMapper;
import com.videonest.module.interaction.mapper.VideoFavoriteMapper;
import com.videonest.module.interaction.mapper.VideoCommentMapper;
import com.videonest.module.interaction.mapper.AdminCommentMapper;
import com.videonest.infrastructure.mq.mapper.DeadLetterRecordMapper;
import com.videonest.infrastructure.outbox.OutboxEventMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@WebMvcTest(AdminVideoController.class)
@Import({
        SecurityConfig.class,
        JacksonConfig.class,
        SecurityErrorResponseWriter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
class AdminEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VideoService videoService;

    @MockitoBean
    private VideoResourceCleanupService cleanupService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private AuthEndpointRateLimitFilter authEndpointRateLimitFilter;

    @MockitoBean private VideoMapper videoMapper;
    @MockitoBean private VideoCategoryMapper videoCategoryMapper;
    @MockitoBean private SysUserMapper sysUserMapper;
    @MockitoBean private NotificationMapper notificationMapper;
    @MockitoBean private UserFollowMapper userFollowMapper;
    @MockitoBean private VideoLikeMapper videoLikeMapper;
    @MockitoBean private VideoFavoriteMapper videoFavoriteMapper;
    @MockitoBean private VideoCommentMapper videoCommentMapper;
    @MockitoBean private AdminCommentMapper adminCommentMapper;
    @MockitoBean private DeadLetterRecordMapper deadLetterRecordMapper;
    @MockitoBean private OutboxEventMapper outboxEventMapper;

    @BeforeEach
    void passThroughCustomFilters() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(authEndpointRateLimitFilter).doFilter(any(), any(), any());
    }

    @Test
    void anonymousUserReceivesJson401() throws Exception {
        mockMvc.perform(get("/api/admin/videos/pending"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void normalUserCannotAccessAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/videos/pending")
                        .with(user("normal-user").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
