package com.example.demo.module.interaction.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class InteractionStatusVO {

    private boolean liked;

    private boolean favorited;

    private Long likeCount;

    private Long favoriteCount;
}