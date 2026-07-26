package com.example.demo.module.interaction.service;

import com.example.demo.module.interaction.vo.InteractionStatusVO;

public interface InteractionService {

    InteractionStatusVO getStatus(Long videoId);

    void like(Long videoId);

    void unlike(Long videoId);

    void favorite(Long videoId);

    void unfavorite(Long videoId);
}