package com.videonest.module.upload.vo;

import java.util.Map;

public record MultipartPartPresignVO(int partNumber, String uploadUrl, Map<String, String> headers,
                                     int expiresInSeconds) { }
